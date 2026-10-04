package com.bigmoe.onedge.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

    val isReady: Boolean get() = _state.value is EngineState.Ready

    /** Build-time features of the native library; all false if the library cannot be loaded. */
    val capabilities: EngineCaps by lazy {
        try { backend.capabilities() } catch (_: Throwable) { EngineCaps(overlapHook = false, multimodal = false, npuPrefill = false) }
    }

    /**
     * Loads [modelPath]; blocks (on the IO dispatcher) for as long as the engine takes. Any previously
     * loaded model is released first. Returns null on success or the error message.
     */
    suspend fun loadModel(modelPath: String, requested: LoadConfig, mmprojPath: String? = null): String? = withContext(ioDispatcher) {
        lifecycleMutex.withLock {
            val file = File(modelPath)
            if (!file.isFile || !file.canRead()) {
                val msg = "Model file is not readable: $modelPath"
                _state.value = EngineState.Failed(modelPath, msg)
                return@withLock msg
            }
            // Expert streaming only makes sense for a Mixture-of-Experts model, so the mode follows the model:
            // MoE -> streaming, regular (dense) model -> the ordinary mmap load. There is no user switch for it.
            val config = applyAutoMode(requested, GgufInspector.inspect(file).kind)
            engineStateKey = null
            _state.value = EngineState.Loading(modelPath, System.currentTimeMillis())
            val error = try {
                backend.load(modelPath, config, if (config.metricsCsv) csvPathProvider() else null, mmprojPath)
            } catch (e: UnsatisfiedLinkError) {
                "Native engine library failed to load: ${e.message}"
            } catch (e: Throwable) {
                "Unexpected error while loading: ${e.message ?: e.javaClass.simpleName}"
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
                    nCtx = info["n_ctx"]?.toIntOrNull() ?: config.nCtx,
                    nExpertUsed = info["n_expert_used"]?.toIntOrNull() ?: 0,
                    loadSeconds = info["load_seconds"]?.toDoubleOrNull() ?: 0.0,
                    thinkControl = info["think_control"] ?: "template",
                    overlapActive = info["overlap"] == "1",
                    visionActive = info["vision"] == "1",
                    mmprojPath = mmprojPath,
                    config = config
                )
            )
            null
        }
    }

    companion object {
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
        if (!generating.compareAndSet(false, true)) {
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
                            override fun onToken(answerDelta: ByteArray, reasoningDelta: ByteArray): Boolean {
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
