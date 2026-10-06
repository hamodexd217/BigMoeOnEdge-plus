package com.bigmoe.onedge.core

import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.WebSearchMode
import com.bigmoe.onedge.data.local.entity.MessageRole
import com.bigmoe.onedge.parser.ArtifactModel
import com.bigmoe.onedge.parser.ParsedChunk
import com.bigmoe.onedge.parser.StreamOutputParser
import com.bigmoe.onedge.parser.ToolCallParseResult
import com.bigmoe.onedge.parser.ToolProtocol
import com.bigmoe.onedge.tools.CODE_TOOL_NAMES
import com.bigmoe.onedge.tools.FILE_TOOL_NAMES
import com.bigmoe.onedge.tools.ToolContext
import com.bigmoe.onedge.tools.ToolManager
import com.bigmoe.onedge.tools.UTILITY_TOOL_NAMES
import com.bigmoe.onedge.tools.WEB_PAGE_TOOL_NAMES
import com.bigmoe.onedge.tools.WebSearchTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A committed message as the agent needs it (decoupled from Room). */
data class HistoryMessage(val id: String, val role: MessageRole, val content: String)

data class AgentRequest(
    val sessionId: String,
    /** Messages BEFORE [prompt], oldest first. The prompt's own user message must not be in here. */
    val history: List<HistoryMessage>,
    val prompt: String,
    val settings: EngineSettings,
    /** Hidden text appended to the prompt: the attached files' contents (already saved as a CONTEXT message). */
    val contextText: String = "",
    /** Pictures (or video frames) for the first engine round of this turn. */
    val images: List<ImageData> = emptyList()
)

/** A message the agent wants persisted. */
data class NewMessage(
    val role: MessageRole,
    val content: String,
    val reasoning: String? = null,
    val stats: GenerationStats? = null,
    /** Shown under the bubble: "Stopped", an engine error, ... */
    val note: String? = null
)

/** Persists a message and returns its id. */
fun interface MessageSink {
    suspend fun save(message: NewMessage): String
}

sealed interface AgentEvent {
    data class Text(val delta: String) : AgentEvent
    data class Reasoning(val delta: String) : AgentEvent
    data object GenerationStarted : AgentEvent
    data class GenerationProgress(val generatedTokens: Int, val contextUsed: Int? = null) : AgentEvent
    data class Artifact(val artifact: ArtifactModel) : AgentEvent
    data class ToolStarted(val name: String, val arguments: String) : AgentEvent
    /** A visible, non-fatal problem (malformed call, limit reached, engine error). */
    data class Notice(val text: String) : AgentEvent
    /** The live buffers of the message that was just persisted can be cleared. */
    data class MessageSaved(val id: String, val role: MessageRole) : AgentEvent
    data object Done : AgentEvent
}

/**
 * One user turn = 1..(1+MAX_TOOL_ROUNDS) engine generations. Talks to the engine ONLY through
 * [EngineController.generate]; never builds a prompt string. See docs/prompt-and-kv-design.md and
 * docs/tool-calling.md.
 */
class AgentController(
    private val engineController: EngineController,
    private val toolManager: ToolManager
) {
    @Volatile
    private var stopRequested = false

    /**
     * Stop button. Interrupts a running generation (the engine then rolls the turn back and the loop
     * ends with a cancelled result) and also ends the tool loop when pressed while a tool is running,
     * where the engine has nothing to cancel.
     */
    fun requestStop() {
        stopRequested = true
        engineController.stopGeneration()
    }

    fun runAgentLoop(request: AgentRequest, sink: MessageSink): Flow<AgentEvent> = flow {
        stopRequested = false
        val settings = request.settings
        // Web search has two modes (docs/tool-calling.md):
        //  AUTO   - the model is told about the tool and decides to emit <tool_call> itself;
        //  ALWAYS - the app searches for every message and hands the results to the model, so it works even
        //           with small models that never call tools.
        val searchOn = settings.isWebSearchEnabled && toolManager.getTool("web_search") != null
        val alwaysSearch = searchOn && settings.webSearchMode == WebSearchMode.ALWAYS
        val enabledNames = enabledToolNames(settings, toolManager.toolNames())
        val toolsEnabled = enabledNames.isNotEmpty()
        (toolManager.getTool("web_search") as? WebSearchTool)?.let {
            it.braveApiKey = settings.braveApiKey
            it.searxngInstanceUrl = settings.searxngUrl
        }
        val system = buildSystemPrompt(
            settings, toolsEnabled, toolManager.getToolsDescriptionPrompt(enabledNames), alwaysSearch
        )

        val turns = historyToTurns(request.history).toMutableList()
        var lastEngineMsgId: String? = request.history.lastOrNull()?.id
        var prompt = request.prompt
        if (request.contextText.isNotBlank()) prompt += "\n\n" + request.contextText
        var toolRounds = 0
        var engineRound = 0
        var generatedTotal = 0

        if (alwaysSearch) {
            val query = searchQueryFrom(request.prompt)
            val args = org.json.JSONObject().put("query", query).toString()
            emit(AgentEvent.ToolStarted("web_search", args))
            val result = toolManager.executeToolCall("web_search", args)
            val wrapped = ToolProtocol.wrapResult("web_search", "Query: $query\n$result")
            // Stored as a TOOL message right after the user's message; replay merges the two user-side turns
            // with the same "\n\n" join used below, so a replayed chat looks exactly like the live one.
            val id = sink.save(NewMessage(MessageRole.TOOL, wrapped))
            emit(AgentEvent.MessageSaved(id, MessageRole.TOOL))
            prompt = prompt + "\n\n" + wrapped
            if (stopRequested) { // Stop pressed while searching: nothing is running in the engine to cancel
                emit(AgentEvent.Notice(NOTE_STOPPED))
                emit(AgentEvent.Done)
                return@flow
            }
        }

        while (true) {
            val expectedKey = ContextPlanner.stateKey(request.sessionId, lastEngineMsgId, system)
            val parser = StreamOutputParser()
            val rawAnswer = StringBuilder()
            val reasoning = StringBuilder()
            var finished: EngineEvent.Finished? = null

            suspend fun show(chunks: List<ParsedChunk>) {
                for (c in chunks) when (c) {
                    is ParsedChunk.Text -> emit(AgentEvent.Text(c.content))
                    is ParsedChunk.ArtifactDetected -> emit(AgentEvent.Artifact(c.artifact))
                    else -> Unit // tool calls are decided from the final saved text, see below
                }
            }

            emit(AgentEvent.GenerationStarted)
            engineController.generate(
                GenerationRequest(
                    system = system,
                    history = turns.toList(),
                    prompt = prompt,
                    expectedKey = expectedKey,
                    maxTokens = settings.maxTokens,
                    think = settings.thinkingEnabled,
                    images = if (engineRound == 0) request.images else emptyList()
                )
            ).collect { ev ->
                when (ev) {
                    is EngineEvent.Progress -> emit(AgentEvent.GenerationProgress(generatedTotal + ev.generatedTokens))
                    is EngineEvent.Answer -> {
                        rawAnswer.append(ev.delta)
                        show(parser.processToken(ev.delta))
                    }
                    is EngineEvent.Reasoning -> {
                        reasoning.append(ev.delta)
                        emit(AgentEvent.Reasoning(ev.delta))
                    }
                    is EngineEvent.Finished -> finished = ev
                }
            }
            show(parser.flush())

            val fin = finished
            if (fin != null) {
                generatedTotal += fin.stats.nGenerated
                emit(AgentEvent.GenerationProgress(generatedTotal, fin.stats.nPast))
            }
            if (fin == null || !fin.ok) {
                val message = fin?.error?.ifBlank { null } ?: "Generation ended unexpectedly"
                emit(AgentEvent.Notice(message))
                saveIfAny(sink, rawAnswer, reasoning, null, note = message)?.let {
                    emit(AgentEvent.MessageSaved(it, MessageRole.ASSISTANT))
                }
                emit(AgentEvent.Done)
                return@flow
            }
            if (fin.cancelled) {
                // The engine rolled the whole turn back, so it does NOT hold this partial answer.
                // The saved partial message makes the next request's expected key differ -> replay.
                saveIfAny(sink, rawAnswer, reasoning, fin.stats, note = NOTE_STOPPED)?.let {
                    emit(AgentEvent.MessageSaved(it, MessageRole.ASSISTANT))
                }
                emit(AgentEvent.Done)
                return@flow
            }

            val content = fin.answer.ifBlank { rawAnswer.toString() }
            val reasoningText = fin.reasoning.ifBlank { reasoning.toString() }.ifBlank { null }
            val assistantId = sink.save(
                NewMessage(MessageRole.ASSISTANT, content, reasoningText, fin.stats)
            )
            emit(AgentEvent.MessageSaved(assistantId, MessageRole.ASSISTANT))
            // The engine now holds exactly: ... + this prompt + this answer. After a turn with pictures it does
            // not (its KV holds image positions), so the next turn must re-send the history as text.
            if (engineRound == 0 && request.images.isNotEmpty()) {
                engineController.invalidateState()
            } else {
                engineController.commitState(ContextPlanner.stateKey(request.sessionId, assistantId, system))
            }
            engineRound++
            lastEngineMsgId = assistantId
            turns.add(ChatTurn(ChatTurn.USER, prompt))
            turns.add(ChatTurn(ChatTurn.ASSISTANT, content))

            val payloads = ToolProtocol.extractPayloads(content)
            if (payloads.isEmpty()) {
                emit(AgentEvent.Done)
                return@flow
            }

            if (toolRounds >= MAX_TOOL_ROUNDS) {
                val text = "Tool call limit reached ($MAX_TOOL_ROUNDS rounds). No further tools were run."
                emit(AgentEvent.Notice(text))
                val id = sink.save(NewMessage(MessageRole.TOOL, text))
                emit(AgentEvent.MessageSaved(id, MessageRole.TOOL))
                emit(AgentEvent.Done)
                return@flow
            }
            toolRounds++

            val results = ArrayList<Pair<String, String>>()
            var malformedReason: String? = null
            for ((payload, _) in payloads.take(MAX_CALLS_PER_ROUND)) {
                when (val parsed = ToolProtocol.parseCall(payload)) {
                    is ToolCallParseResult.Malformed -> malformedReason = malformedReason ?: parsed.reason
                    is ToolCallParseResult.Ok -> {
                        emit(AgentEvent.ToolStarted(parsed.call.name, parsed.call.arguments))
                        val result = if (parsed.call.name in enabledNames) {
                            toolManager.executeToolCall(
                                parsed.call.name, parsed.call.arguments, ToolContext(request.sessionId, assistantId)
                            )
                        } else if (!toolsEnabled) {
                            "Error: tools are disabled. Answer without them."
                        } else {
                            "Error: tool '${parsed.call.name}' is not enabled. Enabled tools: ${enabledNames.sorted().joinToString(", ")}."
                        }
                        results.add(parsed.call.name to result)
                    }
                }
            }

            prompt = when {
                results.isEmpty() -> {
                    val reason = malformedReason ?: "unknown error"
                    emit(AgentEvent.Notice("The model produced an invalid tool call ($reason); asking it to retry."))
                    ToolProtocol.malformedFeedback(reason)
                }
                malformedReason != null -> {
                    ToolProtocol.wrapResults(results + ("tool_call" to "Error: another call could not be parsed ($malformedReason)."))
                }
                else -> ToolProtocol.wrapResults(results)
            }
            val toolId = sink.save(NewMessage(MessageRole.TOOL, prompt))
            emit(AgentEvent.MessageSaved(toolId, MessageRole.TOOL))
            if (stopRequested) {
                emit(AgentEvent.Notice(NOTE_STOPPED))
                emit(AgentEvent.Done)
                return@flow
            }
            // Loop: `prompt` (the tool response) is sent as the next user turn. lastEngineMsgId stays the
            // assistant message, which is what the engine holds.
        }
    }

    private suspend fun saveIfAny(
        sink: MessageSink,
        answer: CharSequence,
        reasoning: CharSequence,
        stats: GenerationStats?,
        note: String
    ): String? {
        if (answer.isBlank() && reasoning.isBlank()) return null
        return sink.save(
            NewMessage(
                MessageRole.ASSISTANT,
                answer.toString(),
                reasoning.toString().ifBlank { null },
                stats,
                note
            )
        )
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 6
        const val MAX_CALLS_PER_ROUND = 4
        const val NOTE_STOPPED = "Stopped"

        fun buildSystemPrompt(
            settings: EngineSettings,
            toolsEnabled: Boolean,
            toolPrompt: String = "",
            alwaysSearch: Boolean = false
        ): String {
            val base = settings.systemPrompt.trim()
            val extra = when {
                toolsEnabled && toolPrompt.isNotBlank() -> toolPrompt
                alwaysSearch -> ALWAYS_SEARCH_HINT
                else -> ""
            }
            return if (extra.isEmpty()) base else "$base\n\n$extra".trim()
        }

        const val ALWAYS_SEARCH_HINT =
            "Web search results for the user's latest message are attached after it inside <tool_response> tags. " +
                "Use them when they are relevant and mention the source URLs you rely on. " +
                "If they are empty, irrelevant or an error, say so and answer from your own knowledge."

        /** The user's message as a search query: first 200 characters, one line. */
        fun searchQueryFrom(message: String): String =
            message.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").take(200)

        /**
         * Tools the model may use this turn: file, code and utility tools by their switches, the web-page tools
         * whenever web search is on (plus web_search itself in AUTO mode, in ALWAYS mode the app searches).
         * Only tools that are registered count.
         */
        fun enabledToolNames(settings: EngineSettings, registered: Set<String>): Set<String> {
            val names = HashSet<String>()
            if (settings.toolsFilesEnabled) names.addAll(FILE_TOOL_NAMES)
            if (settings.toolsCodeEnabled) names.addAll(CODE_TOOL_NAMES)
            if (settings.toolsUtilitiesEnabled) names.addAll(UTILITY_TOOL_NAMES)
            if (settings.isWebSearchEnabled) {
                names.addAll(WEB_PAGE_TOOL_NAMES)
                if (settings.webSearchMode == WebSearchMode.AUTO) names.add("web_search")
            }
            return names.intersect(registered)
        }

        /** DB messages -> engine turns. Tool results are user turns; SYSTEM messages are not replayed. */
        fun historyToTurns(history: List<HistoryMessage>): List<ChatTurn> = history.mapNotNull {
            when (it.role) {
                MessageRole.USER, MessageRole.TOOL, MessageRole.CONTEXT -> ChatTurn(ChatTurn.USER, it.content)
                MessageRole.ASSISTANT -> ChatTurn(ChatTurn.ASSISTANT, it.content)
                MessageRole.SYSTEM -> null
            }
        }
    }
}

/** Where messages are persisted (implemented by ChatRepository; faked in tests). */
interface MessageStore {
    suspend fun addMessage(sessionId: String, message: NewMessage): String
}
