package com.bigmoe.onedge.core

import android.content.Context
import com.bigmoe.onedge.data.repository.ChatRepository
import com.bigmoe.onedge.data.repository.ChatSessionDomainModel
import com.bigmoe.onedge.tools.ConfirmationHandler
import com.bigmoe.onedge.tools.ConfirmationRequest
import com.bigmoe.onedge.tools.ToolManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** What the running turn is doing right now (drives the live status line). */
enum class GenerationPhase { PROMPT, THINKING, ANSWERING, TOOL, COMPACTING }

/** Application-owned owner of the single active agent generation. */
class GenerationManager(
    private val context: Context,
    private val repository: ChatRepository,
    private val agentController: AgentController,
    private val engineController: EngineController,
    private val toolManager: ToolManager
) {
    data class State(
        val sessionId: String? = null,
        val chatTitle: String = "",
        val active: Boolean = false,
        val startedAt: Long? = null,
        val elapsedMs: Long = 0L,
        val generatedTokens: Int = 0,
        val contextUsed: Int = 0,
        val contextTotal: Int = 0,
        val liveText: String = "",
        val liveReasoning: String = "",
        val liveStatus: String? = null,
        val notice: String? = null,
        val pendingConfirmation: ConfirmationRequest? = null,
        // ---- live timing (see AgentEvent.RoundStats / ToolsFinished) ----
        val phase: GenerationPhase = GenerationPhase.PROMPT,
        /** Wall clock (ms) when [phase] began; the UI ticks the elapsed time from it. */
        val phaseStartedAt: Long = 0L,
        /** Names of the tools running while [phase] is TOOL. */
        val toolNames: List<String> = emptyList(),
        /** Prompt tokens read and time taken by the last finished round (0 = none finished yet). */
        val prefillTokens: Int = 0,
        val prefillMs: Long = 0L,
        /** Decode speed: live while streaming, the engine's figure once the round ends. 0 = unknown. */
        val decodeTokensPerSecond: Double = 0.0,
        val decodeStartedAt: Long = 0L,
        val decodeStartTokens: Int = 0,
        /** How long the model reasoned in this turn (null = it did not, or not finished yet). */
        val thinkingMs: Long? = null,
        /** How long the last tool round took. */
        val toolMs: Long? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var generationJob: Job? = null
    private var clockJob: Job? = null
    private var persistenceJob: Job? = null
    private var terminalState: String = "FINISHED"
    private var lastAssistantMessageId: String? = null

    private var confirmation: CompletableDeferred<Boolean>? = null

    init {
        toolManager.confirmationHandler = ConfirmationHandler { request ->
            val answer = CompletableDeferred<Boolean>()
            confirmation = answer
            update { copy(pendingConfirmation = request) }
            try { answer.await() } finally {
                confirmation = null
                update { copy(pendingConfirmation = null) }
            }
        }
        scope.launch(Dispatchers.IO) { repository.clearTransientGenerationStates() }
    }

    fun start(session: ChatSessionDomainModel, request: AgentRequest, sink: MessageSink): Boolean {
        if (generationJob?.isActive == true) return false
        val started = System.currentTimeMillis()
        val total = (engineController.state.value as? EngineState.Ready)?.info?.nCtx ?: 0
        terminalState = "FINISHED"
        lastAssistantMessageId = null
        _state.value = State(session.id, session.title, true, null, contextTotal = total, phaseStartedAt = started)
        generationJob = scope.launch {
            GenerationForegroundService.start(context, session.id)
            // Batch streaming UI snapshots to roughly 30 Hz. Text was previously concatenated and
            // published once per token, forcing repeated copies of the whole response and extra redraws.
            val pendingText = StringBuilder()
            val pendingReasoning = StringBuilder()
            var pendingProgress: AgentEvent.GenerationProgress? = null
            var lastUiFlushNanos = System.nanoTime()
            fun flushPending() {
                val textChunk = pendingText.toString()
                val reasoningChunk = pendingReasoning.toString()
                val progress = pendingProgress
                pendingText.setLength(0)
                pendingReasoning.setLength(0)
                pendingProgress = null
                if (textChunk.isNotEmpty() || reasoningChunk.isNotEmpty() || progress != null) {
                    update {
                        copy(
                            liveText = liveText + textChunk,
                            liveReasoning = liveReasoning + reasoningChunk,
                            liveStatus = if (textChunk.isNotEmpty()) null else liveStatus,
                            generatedTokens = progress?.let { maxOf(generatedTokens, it.generatedTokens) } ?: generatedTokens,
                            contextUsed = progress?.contextUsed ?: contextUsed,
                            decodeTokensPerSecond = liveDecodeSpeed(progress?.generatedTokens) ?: decodeTokensPerSecond
                        )
                    }
                }
                lastUiFlushNanos = System.nanoTime()
            }
            fun flushIfDue() {
                if (System.nanoTime() - lastUiFlushNanos >= 33_000_000L) flushPending()
            }
            try {
                try {
                    agentController.runAgentLoop(request, sink).collect { event ->
                        when (event) {
                            is AgentEvent.Text -> {
                                if (_state.value.phase != GenerationPhase.ANSWERING) { flushPending(); enterPhase(GenerationPhase.ANSWERING) }
                                pendingText.append(event.delta)
                                flushIfDue()
                            }
                            is AgentEvent.Reasoning -> {
                                if (_state.value.phase != GenerationPhase.THINKING) { flushPending(); enterPhase(GenerationPhase.THINKING) }
                                pendingReasoning.append(event.delta)
                                flushIfDue()
                            }
                            is AgentEvent.GenerationProgress -> {
                                pendingProgress = event
                                flushIfDue()
                            }
                            else -> {
                                // Keep status, tool and persistence boundaries ordered after all pending text.
                                flushPending()
                                when (event) {
                                    AgentEvent.GenerationStarted -> update {
                                        val now = System.currentTimeMillis()
                                        // A new round: reading the prompt again; decode speed restarts with its first token.
                                        copy(
                                            startedAt = startedAt ?: now, phase = GenerationPhase.PROMPT, phaseStartedAt = now,
                                            toolNames = emptyList(), decodeStartedAt = 0L, decodeTokensPerSecond = 0.0
                                        )
                                    }
                                    is AgentEvent.ToolStarted -> update {
                                        val now = System.currentTimeMillis()
                                        copy(
                                            liveStatus = event.name + "…",
                                            phase = GenerationPhase.TOOL,
                                            phaseStartedAt = if (phase == GenerationPhase.TOOL) phaseStartedAt else now,
                                            toolNames = if (phase == GenerationPhase.TOOL) (toolNames + event.name).distinct() else listOf(event.name)
                                        )
                                    }
                                    is AgentEvent.ToolsFinished -> update {
                                        copy(toolMs = event.ms, phase = GenerationPhase.PROMPT, phaseStartedAt = System.currentTimeMillis(), toolNames = emptyList())
                                    }
                                    is AgentEvent.RoundStats -> update {
                                        copy(
                                            prefillTokens = event.prefillTokens, prefillMs = event.prefillMs,
                                            thinkingMs = event.thinkingMs ?: thinkingMs,
                                            decodeTokensPerSecond = event.tokensPerSecond.takeIf { it > 0.0 } ?: decodeTokensPerSecond
                                        )
                                    }
                                    is AgentEvent.Notice -> {
                                        terminalState = when {
                                            event.text == AgentController.NOTE_STOPPED -> "CANCELLED"
                                            Regex("(?i)\\b(error|failed|crashed|unexpected|could not)\\b").containsMatchIn(event.text) -> "ERROR"
                                            else -> "FINISHED"
                                        }
                                        update { copy(notice = event.text) }
                                    }
                                    is AgentEvent.MessageSaved -> {
                                        if (event.role == com.bigmoe.onedge.data.local.entity.MessageRole.ASSISTANT) {
                                            lastAssistantMessageId = event.id
                                        }
                                        update { copy(liveText = "", liveReasoning = "") }
                                    }
                                    AgentEvent.Compacting -> update {
                                        copy(phase = GenerationPhase.COMPACTING, phaseStartedAt = System.currentTimeMillis(), toolNames = emptyList())
                                    }
                                    is AgentEvent.Compacted -> update {
                                        copy(phase = GenerationPhase.PROMPT, phaseStartedAt = System.currentTimeMillis())
                                    }
                                    is AgentEvent.Artifact, AgentEvent.Done -> Unit
                                    is AgentEvent.Text, is AgentEvent.Reasoning, is AgentEvent.GenerationProgress -> Unit
                                }
                            }
                        }
                    }
                } finally {
                    // Never lose the last partial chunk, including when cancellation interrupts collection.
                    flushPending()
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    terminalState = "ERROR"
                    update { copy(notice = t.message ?: t.javaClass.simpleName) }
                }
            } finally {
                withContext(NonCancellable) {
                    val final = _state.value
                    val elapsed = final.startedAt?.let { System.currentTimeMillis() - it } ?: final.elapsedMs
                    lastAssistantMessageId?.let { messageId ->
                        repository.updateGenerationTelemetry(
                            messageId = messageId,
                            elapsedMs = elapsed,
                            contextUsed = final.contextUsed,
                            contextTotal = final.contextTotal,
                            generatedTokens = final.generatedTokens
                        )
                    }
                    repository.finishGeneration(session.id, elapsed, final.generatedTokens, final.contextUsed, final.contextTotal, final.notice, terminalState)
                    _state.value = final.copy(active = false, elapsedMs = elapsed)
                    clockJob?.cancel()
                    persistenceJob?.cancel()
                    GenerationForegroundService.stop(context)
                }
            }
        }
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive) {
                delay(250)
                val s = _state.value
                val st = s.startedAt
                if (s.active && st != null) update { copy(elapsedMs = System.currentTimeMillis() - st) }
            }
        }
        persistenceJob?.cancel()
        persistenceJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                val s = _state.value
                if (s.active && s.sessionId == session.id) repository.updateGenerationProgress(session.id, s.startedAt ?: started, s.elapsedMs, s.generatedTokens, s.contextUsed, s.contextTotal)
            }
        }
        return true
    }

    /** Moves to [next], remembering how long the reasoning phase lasted when it ends and when decoding began. */
    private fun enterPhase(next: GenerationPhase) = update {
        val now = System.currentTimeMillis()
        copy(
            thinkingMs = if (phase == GenerationPhase.THINKING && next != GenerationPhase.THINKING) now - phaseStartedAt else thinkingMs,
            phase = next,
            phaseStartedAt = now,
            decodeStartedAt = if (decodeStartedAt == 0L) now else decodeStartedAt,
            decodeStartTokens = if (decodeStartedAt == 0L) generatedTokens else decodeStartTokens
        )
    }

    /** Tokens per second since the first token of this round, or null before there is anything to measure. */
    private fun State.liveDecodeSpeed(totalTokens: Int?): Double? {
        if (totalTokens == null || decodeStartedAt == 0L) return null
        val seconds = (System.currentTimeMillis() - decodeStartedAt) / 1000.0
        val tokens = totalTokens - decodeStartTokens
        return if (seconds > 0.2 && tokens > 0) tokens / seconds else null
    }

    fun updateTokenProgress(generatedTokens: Int, contextUsed: Int? = null) = update {
        copy(generatedTokens = maxOf(this.generatedTokens, generatedTokens), contextUsed = contextUsed ?: this.contextUsed)
    }

    fun resolveConfirmation(approved: Boolean) { confirmation?.complete(approved) }
    fun stop() { if (generationJob?.isActive == true) agentController.requestStop(); confirmation?.complete(false) }
    private inline fun update(crossinline transform: State.() -> State) { _state.value = transform(_state.value) }
}
