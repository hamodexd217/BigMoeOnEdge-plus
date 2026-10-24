package com.bigmoe.onedge.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the model lifecycle and turns the blocking native generation into a cancellable [Flow].
 *
 * Conversation state: the engine keeps its own chat history + KV. [engineStateKey] records which
 * conversation that is (see [ContextPlanner.stateKey]); when a request's expected key differs the engine
 * is reset (clear_kv) and the saved turns are replayed. See docs/prompt-and-kv-design.md.
 */
class EngineController(
    private val backendProvider: () -> EngineBackend,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Where to write this session's metrics CSV (null = no CSV). Only asked when metrics CSV is enabled. */
    private val csvPathProvider: () -> String? = { null }
) {
    private val backend: EngineBackend by lazy(backendProvider)

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Serialises load/unload/generate on the app side (the native layer is single-flight too). */
    private val lifecycleMutex = Mutex()
    private val generating = AtomicBoolean(false)

    @Volatile
    private var engineStateKey: String? = null

    // ---- Local API server arbitration -------------------------------------------------------------------
    // The engine is single-flight. API requests queue up here (one at a time, FIFO, bounded wait); the in-app chat
    // has priority: if it starts while an API generation runs, that generation is cancelled (see acquireForApp), and
    // API requests that are still queued keep waiting until the app is idle or their wait runs out.
    private val apiQueue = Mutex()
    private val apiWaiting = AtomicInteger(0)
    private val appPending = AtomicInteger(0)
    @Volatile
    private var activeApiLease: ApiLease? = null

    val isReady: Boolean get() = _state.value is EngineState.Ready

    /** The loaded model, or null when none is ready. */
    fun loadedModel(): ModelInfo? = (_state.value as? EngineState.Ready)?.info

    /** Build-time features of the native library; all false if the library cannot be loaded. */
    val capabilities: EngineCaps by lazy {
        try { backend.capabilities() } catch (_: Throwable) { EngineCaps(overlapHook = false, multimodal = false, npuPrefill = false) }
    }

    /** Detailed runtime state for settings. The native implementation caches the probe result. */
    val npuPrefillStatus: String
        get() = try {
            backend.npuPrefillStatus()
        } catch (t: Throwable) {
            "Unavailable: native engine status could not be read (${t.message ?: t.javaClass.simpleName})."
        }

    /**
     * Loads [modelPath]; blocks (on the IO dispatcher) for as long as the engine takes. Any previously
     * loaded model is released first. Returns null on success or the error message.
     */
    suspend fun loadModel(
        modelPath: String,
        requested: LoadConfig,
        mmprojPath: String? = null,
        draftPath: String? = null
    ): String? = withContext(ioDispatcher) {
        lifecycleMutex.withLock {
            val file = File(modelPath)
            if (!file.isFile || !file.canRead()) {
                val msg = "Model file is not readable: $modelPath"
                _state.value = EngineState.Failed(modelPath, msg)
                return@withLock msg
            }
            val kind = GgufInspector.inspect(file).kind
            val wantsSeparateDraft = requested.spec == SpecSource.MTP && !draftPath.isNullOrBlank()
            // A separate MTP sidecar must not disable streaming for the target MoE model. Keep the
            // normal auto policy: MoE target experts stream from storage; the independent sidecar
            // uses mmap because its tensors belong to a different GGUF/offset map.
            val config = applyAutoMode(requested, kind)
            engineStateKey = null
            _state.value = EngineState.Loading(modelPath, System.currentTimeMillis())
            val csv = if (config.metricsCsv) csvPathProvider() else null
            var activeConfig = config
            var activeDraftPath: String? = if (wantsSeparateDraft) draftPath else null
            var warning: String? = null
            var error = try {
                backend.load(modelPath, activeConfig, csv, mmprojPath, activeDraftPath)
            } catch (e: UnsatisfiedLinkError) {
                "Native engine library failed to load: ${e.message}"
            } catch (e: Throwable) {
                "Unexpected error while loading: ${e.message ?: e.javaClass.simpleName}"
            }

            // A broken/mismatched sidecar must not brick the target model. Retry a plain decode and
            // surface the failure rather than claiming MTP is active.
            if (error != null && wantsSeparateDraft) {
                val draftError = error
                val plainConfig = config.copy(spec = SpecSource.OFF)
                val plainError = try {
                    backend.load(modelPath, plainConfig, csv, mmprojPath, null)
                } catch (e: Throwable) {
                    "Unexpected error while retrying without the MTP draft: ${e.message ?: e.javaClass.simpleName}"
                }
                if (plainError == null) {
                    activeConfig = plainConfig
                    activeDraftPath = null
                    warning = "Separate MTP draft failed to load ($draftError). Loaded with normal decoding; speculation is OFF."
                    error = null
                } else {
                    error = "Separate MTP draft failed: $draftError\nFallback without speculation also failed: $plainError"
                }
            }
            if (error != null) {
                _state.value = EngineState.Failed(modelPath, error)
                return@withLock error
            }
            val info = backend.modelInfo()
            _state.value = EngineState.Ready(
                ModelInfo(
                    path = modelPath,
                    name = file.name,
                    arch = info["arch"].orEmpty(),
                    nCtx = info["n_ctx"]?.toIntOrNull() ?: activeConfig.nCtx,
                    nExpertUsed = info["n_expert_used"]?.toIntOrNull() ?: 0,
                    loadSeconds = info["load_seconds"]?.toDoubleOrNull() ?: 0.0,
                    thinkControl = info["think_control"] ?: "template",
                    overlapActive = info["overlap"] == "1",
                    visionActive = info["vision"] == "1",
                    mmprojPath = mmprojPath,
                    config = activeConfig,
                    draftModelPath = activeDraftPath,
                    loadWarning = warning,
                    npuPrefillReady = info["npu_prefill"] == "ready"
                )
            )
            null
        }
    }

    companion object {
        /** How long an in-app generation waits for a cancelled API generation to unwind. */
        const val PREEMPT_WAIT_MS = 30_000L
        const val API_MAX_QUEUED = 8
        private const val API_POLL_MS = 50L

        /** MoE = stream experts, dense = ordinary load. An unreadable header keeps the requested value. */
        fun applyAutoMode(config: LoadConfig, kind: ModelKind): LoadConfig = when (kind) {
            ModelKind.MOE -> config.copy(mmapBaseline = false)
            ModelKind.DENSE -> config.copy(mmapBaseline = true)
            ModelKind.UNKNOWN -> config
        }
    }

    suspend fun unload() = withContext(ioDispatcher) {
        // stop() first so a running generation unwinds quickly; the native free then waits for it.
        try { backend.stop() } catch (_: Throwable) {}
        lifecycleMutex.withLock {
            engineStateKey = null
            try { backend.free() } catch (_: Throwable) {}
            _state.value = EngineState.Unloaded
        }
    }

    /** Scores a fixed set of choices with one prompt prefill and no decode. */
    suspend fun decide(request: DecisionRequest): DecisionResult = withContext(ioDispatcher) {
        val ready = _state.value as? EngineState.Ready
            ?: return@withContext DecisionResult(ok = false, error = "No model is loaded. Load a model first.")
        if (request.choices.size < 2) {
            return@withContext DecisionResult(ok = false, error = "Give at least two options.")
        }
        if (!generating.compareAndSet(false, true)) {
            return@withContext DecisionResult(ok = false, error = "The engine is already generating.")
        }
        try {
            val result = lifecycleMutex.withLock {
                backend.decide(request)
            }
            // decide() deliberately leaves only its optional prefix cache state. Normal generation should
            // replay the chat transcript rather than assuming that state is a normal assistant turn.
            engineStateKey = null
            result
        } catch (t: Throwable) {
            engineStateKey = null
            DecisionResult(ok = false, error = "Native decide crashed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            generating.set(false)
        }
    }

    fun stopGeneration() {
        try { backend.stop() } catch (_: Throwable) {}
    }

    /** Call after the turn's assistant message has been saved: the engine now holds exactly that state. */
    fun commitState(key: String) {
        engineStateKey = key
    }

    /** Forget what the engine holds; the next turn will reset and replay. */
    fun invalidateState() {
        engineStateKey = null
    }

    /**
     * Streams one generation. Cancelling the collector stops the native generation and waits for it to
     * unwind. The flow always ends with exactly one [EngineEvent.Finished] (unless the collector cancels).
     */
    fun generate(request: GenerationRequest): Flow<EngineEvent> = flow {
        val ready = _state.value as? EngineState.Ready
        if (ready == null) {
            emit(failed("No model is loaded. Load a model first."))
            return@flow
        }
        if (!acquireForApp()) {
            emit(failed("The engine is already generating."))
            return@flow
        }
        var completed = false
        try {
            var attempt = 0
            var shrink = 1.0
            while (true) {
                val replay = engineStateKey != request.expectedKey || shrink < 1.0
                var streamed = false
                var fin: EngineEvent.Finished? = null
                runOnce(request, ready.info, shrink, replay).collect { ev ->
                    if (ev is EngineEvent.Finished) {
                        fin = ev
                    } else {
                        streamed = true
                        emit(ev)
                    }
                }
                val result = fin ?: failed("Generation ended without a result")
                if (!result.ok && !streamed && attempt < 2 && ContextPlanner.isContextOverflow(result.error)) {
                    // The replayed history did not fit; retry with half of it.
                    attempt++
                    shrink *= 0.5
                    continue
                }
                // A completed turn leaves the engine ahead of the caller's saved state until the caller
                // saves the turn and calls commitState(). A cancelled or failed turn is rolled back by the
                // engine: state is unchanged after a continued turn, and only the seeded replay after a
                // replayed one, so a replayed turn forgets the key.
                engineStateKey = if (result.ok && result.cancelled && !replay) request.expectedKey else null
                emit(result)
                completed = true
                return@flow
            }
        } finally {
            if (!completed) engineStateKey = null // collector cancelled mid-flight: be conservative
            generating.set(false)
        }
    }

    /**
     * The in-app chat's way to take the engine. Same as before (a plain compare-and-set) unless the engine is busy
     * with an API request: that one is cancelled and the chat waits for it to unwind. Anything else (another chat
     * generation, a decide()) is refused exactly as it always was.
     */
    private suspend fun acquireForApp(): Boolean {
        if (generating.compareAndSet(false, true)) return true
        val lease = activeApiLease ?: return false
        appPending.incrementAndGet()
        try {
            lease.markPreempted()
            try { backend.stop() } catch (_: Throwable) {}
            val deadline = System.currentTimeMillis() + PREEMPT_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (generating.compareAndSet(false, true)) return true
                delay(25)
            }
            return false
        } finally {
            appPending.decrementAndGet()
        }
    }

    /**
     * Waits (at most [timeoutMs], at most [maxQueued] callers at once) for the engine and returns a lease, or null
     * when the caller should answer "overloaded" (HTTP 529). Release the lease when the request is finished, whatever
     * happened; the engine is not free for anyone else until then.
     */
    suspend fun acquireForApi(timeoutMs: Long, maxQueued: Int = API_MAX_QUEUED): ApiLease? {
        if (apiWaiting.incrementAndGet() > maxQueued) {
            apiWaiting.decrementAndGet()
            return null
        }
        var locked = false
        try {
            val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
            locked = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { apiQueue.lock(); true } ?: false
            if (!locked) return null
            while (true) {
                if (appPending.get() == 0 && generating.compareAndSet(false, true)) {
                    val lease = ApiLease {
                        activeApiLease = null
                        generating.set(false)
                        apiQueue.unlock()
                    }
                    activeApiLease = lease
                    locked = false // the lease owns the queue slot now
                    return lease
                }
                if (System.currentTimeMillis() >= deadline) return null
                delay(API_POLL_MS)
            }
        } finally {
            if (locked) apiQueue.unlock()
            apiWaiting.decrementAndGet()
        }
    }

    /**
     * Streams one stateless chat generation for the local API server; [lease] comes from [acquireForApi]. Always ends
     * with exactly one [ChatEvent.Finished] (unless the collector cancels, which stops the native generation). The
     * engine reuses the KV prefix left by the previous request on its own. Afterwards the engine no longer holds the
     * in-app chat's state, so the next in-app turn replays its history (clear_kv), as it does after decide().
     */
    fun generateChat(lease: ApiLease, request: ChatGenRequest): Flow<ChatEvent> = flow {
        if (_state.value !is EngineState.Ready) {
            emit(chatFailed("No model is loaded. Load a model first.", lease.preempted))
            return@flow
        }
        // The in-app chat may have asked for the engine between the lease being granted and this call; a stop() sent
        // then would be lost (the native side clears its cancel flag when a generation starts), so check here.
        if (lease.preempted) {
            emit(chatFailed("The in-app chat took the model.", true))
            return@flow
        }
        engineStateKey = null
        try {
            coroutineScope {
                val events = Channel<ChatEvent>(Channel.UNLIMITED)
                val stopped = AtomicBoolean(false)
                val worker = launch(ioDispatcher) {
                    try {
                        backend.generateChat(request, object : NativeChatCallback {
                            override fun onToken(answerDelta: ByteArray, reasoningDelta: ByteArray): Boolean {
                                if (reasoningDelta.isNotEmpty()) events.trySend(ChatEvent.Reasoning(decodeUtf8(reasoningDelta)))
                                if (answerDelta.isNotEmpty()) events.trySend(ChatEvent.Answer(decodeUtf8(answerDelta)))
                                // Also covers a preemption that arrived after the check above but before the native start.
                                return !stopped.get() && !lease.preempted
                            }

                            override fun onFinished(
                                ok: Boolean, cancelled: Boolean, error: ByteArray, answer: ByteArray,
                                reasoning: ByteArray, toolCalls: Array<ByteArray>, stopReason: ByteArray,
                                stopSequence: ByteArray, nGenerated: Int, nPromptTotal: Int, nReused: Int,
                                nPast: Int, tokensPerSecond: Double, prefillSeconds: Double, cacheHitPct: Double
                            ) {
                                events.trySend(
                                    ChatEvent.Finished(
                                        ok = ok, cancelled = cancelled, error = decodeUtf8(error),
                                        answer = decodeUtf8(answer), reasoning = decodeUtf8(reasoning),
                                        toolCalls = parseNativeToolCalls(toolCalls),
                                        stopReason = decodeUtf8(stopReason), stopSequence = decodeUtf8(stopSequence),
                                        stats = ChatStats(nGenerated, nPromptTotal, nReused, nPast, tokensPerSecond, prefillSeconds, cacheHitPct),
                                        preempted = lease.preempted
                                    )
                                )
                            }
                        })
                    } catch (t: Throwable) {
                        events.trySend(chatFailed("Native generation crashed: ${t.message ?: t.javaClass.simpleName}", lease.preempted))
                    } finally {
                        events.close()
                    }
                }
                try {
                    for (ev in events) emit(ev)
                } finally {
                    if (worker.isActive) {
                        stopped.set(true)
                        try { backend.stop() } catch (_: Throwable) {}
                    }
                }
            }
        } finally {
            engineStateKey = null
        }
    }

    private fun chatFailed(message: String, preempted: Boolean = false) = ChatEvent.Finished(
        ok = false, cancelled = false, error = message, answer = "", reasoning = "", toolCalls = emptyList(),
        stopReason = "", stopSequence = "", stats = ChatStats(), preempted = preempted
    )

    private fun failed(message: String) = EngineEvent.Finished(
        ok = false, cancelled = false, error = message, answer = "", reasoning = "", stats = GenerationStats()
    )

    /** One native call. [shrink] scales the replay history budget down after a context overflow. */
    private fun runOnce(
        request: GenerationRequest,
        info: ModelInfo,
        shrink: Double,
        needsReplay: Boolean
    ): Flow<EngineEvent> = flow {
        var history = emptyList<ChatTurn>()
        var prompt = request.prompt
        if (needsReplay) {
            val budget = (ContextPlanner.historyBudget(
                nCtx = info.nCtx,
                systemTokens = ContextPlanner.estimateTokens(request.system),
                promptTokens = ContextPlanner.estimateTokens(request.prompt),
                maxTokens = request.maxTokens
            ) * shrink).toInt()
            val prepared = ContextPlanner.prepare(request.history, request.prompt, budget)
            history = prepared.history
            prompt = prepared.prompt
        }
        val maxTokens = ContextPlanner.effectiveMaxTokens(request.maxTokens, info.nCtx)

        coroutineScope {
            val events = Channel<EngineEvent>(Channel.UNLIMITED)
            val stopped = AtomicBoolean(false)
            val worker = launch(ioDispatcher) {
                try {
                    backend.generate(
                        system = request.system,
                        historyFlat = history,
                        prompt = prompt,
                        maxTokens = maxTokens,
                        clearKv = needsReplay,
                        think = request.think,
                        images = request.images,
                        callback = object : NativeGenerationCallback {
                            private var generated = 0
                            override fun onToken(answerDelta: ByteArray, reasoningDelta: ByteArray): Boolean {
                                generated++
                                events.trySend(EngineEvent.Progress(generated))
                                if (reasoningDelta.isNotEmpty()) events.trySend(EngineEvent.Reasoning(decodeUtf8(reasoningDelta)))
                                if (answerDelta.isNotEmpty()) events.trySend(EngineEvent.Answer(decodeUtf8(answerDelta)))
                                return !stopped.get()
                            }

                            override fun onFinished(
                                ok: Boolean, cancelled: Boolean, error: ByteArray, answer: ByteArray,
                                reasoning: ByteArray, nGenerated: Int, nPrompt: Int, nPast: Int,
                                tokensPerSecond: Double, prefillSeconds: Double, cacheHitPct: Double
                            ) {
                                events.trySend(
                                    EngineEvent.Finished(
                                        ok = ok, cancelled = cancelled, error = decodeUtf8(error),
                                        answer = decodeUtf8(answer), reasoning = decodeUtf8(reasoning),
                                        stats = GenerationStats(nGenerated, nPrompt, nPast, tokensPerSecond, prefillSeconds, cacheHitPct)
                                    )
                                )
                            }
                        }
                    )
                } catch (t: Throwable) {
                    events.trySend(failed("Native generation crashed: ${t.message ?: t.javaClass.simpleName}"))
                } finally {
                    events.close()
                }
            }
            try {
                for (ev in events) emit(ev)
            } finally {
                // Collector cancelled (or finished): make sure native stops, then wait for it to unwind.
                if (worker.isActive) {
                    stopped.set(true)
                    try { backend.stop() } catch (_: Throwable) {}
                }
            }
        }
    }
}

/**
 * Exclusive hold on the engine for one API request (see [EngineController.acquireForApi]). [release] is idempotent;
 * [preempted] turns true when the in-app chat took the engine back and the request was cancelled.
 */
class ApiLease internal constructor(private val onRelease: () -> Unit) {
    @Volatile
    var preempted: Boolean = false
        private set
    private val released = AtomicBoolean(false)

    internal fun markPreempted() {
        preempted = true
    }

    fun release() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}
