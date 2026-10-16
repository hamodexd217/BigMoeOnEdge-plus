package com.bigmoe.onedge.core

import android.content.Context
import com.bigmoe.onedge.data.repository.ChatRepository
import com.bigmoe.onedge.data.repository.ChatSessionDomainModel
import com.bigmoe.onedge.tools.ConfirmationHandler
import com.bigmoe.onedge.tools.ConfirmationRequest
import com.bigmoe.onedge.tools.ToolManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
        val pendingConfirmation: ConfirmationRequest? = null
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
        _state.value = State(session.id, session.title, true, null, contextTotal = total)
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
                            contextUsed = progress?.contextUsed ?: contextUsed
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
                                pendingText.append(event.delta)
                                flushIfDue()
                            }
                            is AgentEvent.Reasoning -> {
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
                                    AgentEvent.GenerationStarted -> update { copy(startedAt = startedAt ?: System.currentTimeMillis()) }
                                    is AgentEvent.ToolStarted -> update { copy(liveStatus = event.name + "…") }
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

    fun updateTokenProgress(generatedTokens: Int, contextUsed: Int? = null) = update {
        copy(generatedTokens = maxOf(this.generatedTokens, generatedTokens), contextUsed = contextUsed ?: this.contextUsed)
    }

    fun resolveConfirmation(approved: Boolean) { confirmation?.complete(approved) }
    fun stop() { if (generationJob?.isActive == true) agentController.requestStop(); confirmation?.complete(false) }
    private inline fun update(crossinline transform: State.() -> State) { _state.value = transform(_state.value) }
}
