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
import com.bigmoe.onedge.tools.ImageSearchTool
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val note: String? = null,
    /** Tokens the prompt processing had to read for this turn. */
    val prefillTokens: Int? = null,
    /** How long the model spent in its reasoning span. */
    val thinkingMs: Long? = null,
    /** Wall-clock time the tools of this round took (tool-result messages). */
    val toolMs: Long? = null
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
    /** The tools of one round are done. [ms] is the wall-clock time (parallel calls overlap). */
    data class ToolsFinished(val names: List<String>, val ms: Long) : AgentEvent
    /**
     * One generation round ended: how many prompt tokens it had to read and how long that took, the decode speed,
     * and how long the model reasoned (null when it did not).
     */
    data class RoundStats(val prefillTokens: Int, val prefillMs: Long, val tokensPerSecond: Double, val thinkingMs: Long?) : AgentEvent
    /** A visible, non-fatal problem (malformed call, limit reached, engine error). */
    data class Notice(val text: String) : AgentEvent
    /** The live buffers of the message that was just persisted can be cleared. */
    data class MessageSaved(val id: String, val role: MessageRole) : AgentEvent
    data object Done : AgentEvent
}

/**
 * One user turn = as many engine generations as the model needs: there is no cap on tool rounds (only the Stop button,
 * the context size and a guard against the model repeating one identical call). Talks to the engine ONLY through
 * [EngineController.generate]; never builds a prompt string. See docs/prompt-and-kv-design.md and
 * docs/tool-calling.md.
 */
private const val PROGRESS_TOKEN_INTERVAL = 8

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
        toolManager.syncExternalTools(settings.externalToolsJson)
        val enabledNames = enabledToolNames(settings, toolManager.toolNames(), toolManager.externalToolNames())
        val toolsEnabled = enabledNames.isNotEmpty()
        (toolManager.getTool("web_search") as? WebSearchTool)?.let {
            it.braveApiKey = settings.braveApiKey
            it.searxngInstanceUrl = settings.searxngUrl
        }
        // Image search shares the configured search credentials/instance and otherwise uses its keyless backend.
        (toolManager.getTool("image_search") as? ImageSearchTool)?.let {
            it.braveApiKey = settings.braveApiKey
            it.searxngInstanceUrl = settings.searxngUrl
        }
        toolManager.maxResultChars = settings.toolResultChars
        val system = buildSystemPrompt(
            settings, toolsEnabled, toolManager.getToolsDescriptionPrompt(enabledNames), alwaysSearch
        )

        val turns = historyToTurns(request.history).toMutableList()
        var lastEngineMsgId: String? = request.history.lastOrNull()?.id
        var prompt = request.prompt
        if (request.contextText.isNotBlank()) prompt += "\n\n" + request.contextText
        var lastCallSignature: String? = null
        var identicalRounds = 0
        var engineRound = 0
        var generatedTotal = 0

        if (alwaysSearch) {
            // Ask the loaded model to formulate a search query instead of sending the user's raw message
            // directly to the search provider. This is a separate, hidden short generation; the main answer
            // generation replays the real conversation and the search results as usual.
            val generatedQuery = generateSearchQuery(request, turns)
            val query = searchQueryFrom(generatedQuery).ifBlank { searchQueryFrom(request.prompt) }
            val args = org.json.JSONObject().put("query", query).toString()
            val searchStarted = System.currentTimeMillis()
            emit(AgentEvent.ToolStarted("web_search", args))
            val result = toolManager.executeToolCall("web_search", args)
            val searchMs = System.currentTimeMillis() - searchStarted
            emit(AgentEvent.ToolsFinished(listOf("web_search"), searchMs))
            val wrapped = ToolProtocol.wrapResult("web_search", "Query: $query\n$result")
            // Stored as a TOOL message right after the user's message; replay merges the two user-side turns
            // with the same "\n\n" join used below, so a replayed chat looks exactly like the live one.
            val id = sink.save(NewMessage(MessageRole.TOOL, wrapped, toolMs = searchMs))
            emit(AgentEvent.MessageSaved(id, MessageRole.TOOL))
            prompt = prompt + "\n\n" + wrapped
            if (settings.imageSearchEnabled && settings.autoImageSearchAfterWebSearch && !stopRequested) {
                val imageArgs = org.json.JSONObject().put("query", query).toString()
                val imageStarted = System.currentTimeMillis()
                emit(AgentEvent.ToolStarted("image_search", imageArgs))
                val imageResult = toolManager.executeToolCall("image_search", imageArgs)
                val imageMs = System.currentTimeMillis() - imageStarted
                emit(AgentEvent.ToolsFinished(listOf("image_search"), imageMs))
                val imageWrapped = ToolProtocol.wrapResult("image_search", "Query: $query\n$imageResult")
                val imageId = sink.save(NewMessage(MessageRole.TOOL, imageWrapped, toolMs = imageMs))
                emit(AgentEvent.MessageSaved(imageId, MessageRole.TOOL))
                prompt = prompt + "\n\n" + imageWrapped
            }
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
            // How long the model reasoned: from its first reasoning token to its first answer token (or the end).
            var thinkStartedAt = 0L
            var thinkingMs: Long? = null

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
                    // After a tool result the model usually just has to use it: reasoning again costs minutes at
                    // decode speed, so it only happens when the user asked for it.
                    think = settings.thinkingEnabled && (engineRound == 0 || settings.thinkAfterTools),
                    images = if (engineRound == 0) request.images else emptyList(),
                    sampling = SamplingOverride(
                        temperature = if (settings.spec != SpecSource.OFF) 0f else settings.temperature,
                        topP = settings.topP,
                        topK = settings.topK,
                        seed = settings.seed,
                        minP = settings.minP,
                        // Guess ahead verifies against the greedy argmax, so the penalties are off while it is on.
                        repeatPenalty = if (settings.spec != SpecSource.OFF) 1f else settings.repeatPenalty,
                        repeatLastN = settings.repeatLastN,
                        frequencyPenalty = if (settings.spec != SpecSource.OFF) 0f else settings.frequencyPenalty,
                        presencePenalty = if (settings.spec != SpecSource.OFF) 0f else settings.presencePenalty
                    )
                )
            ).collect { ev ->
                when (ev) {
                    is EngineEvent.Progress -> {
                        // Progress is informational, unlike answer deltas. Emitting it for every token adds
                        // cross-coroutine/channel work and UI bookkeeping to a CPU-bound decode. Text still streams
                        // token-by-token; refresh the counter every 8 tokens and always send the final exact total.
                        val total = generatedTotal + ev.generatedTokens
                        if (total % PROGRESS_TOKEN_INTERVAL == 0) {
                            emit(AgentEvent.GenerationProgress(total))
                        }
                    }
                    is EngineEvent.Answer -> {
                        if (thinkStartedAt != 0L && thinkingMs == null) thinkingMs = System.currentTimeMillis() - thinkStartedAt
                        rawAnswer.append(ev.delta)
                        show(parser.processToken(ev.delta))
                    }
                    is EngineEvent.Reasoning -> {
                        if (thinkStartedAt == 0L) thinkStartedAt = System.currentTimeMillis()
                        reasoning.append(ev.delta)
                        emit(AgentEvent.Reasoning(ev.delta))
                    }
                    is EngineEvent.Finished -> finished = ev
                }
            }
            show(parser.flush())
            if (thinkStartedAt != 0L && thinkingMs == null) thinkingMs = System.currentTimeMillis() - thinkStartedAt

            val fin = finished
            if (fin != null) {
                emit(
                    AgentEvent.RoundStats(
                        prefillTokens = fin.stats.nPrompt,
                        prefillMs = (fin.stats.prefillSeconds * 1000.0).toLong(),
                        tokensPerSecond = fin.stats.tokensPerSecond,
                        thinkingMs = thinkingMs
                    )
                )
                generatedTotal += fin.stats.nGenerated
                emit(AgentEvent.GenerationProgress(generatedTotal, fin.stats.nPast.takeIf { it > 0 })) // 0 = unknown: never overwrite a good value
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
                NewMessage(
                    MessageRole.ASSISTANT, content, reasoningText, fin.stats,
                    prefillTokens = fin.stats.nPrompt.takeIf { it > 0 },
                    thinkingMs = thinkingMs.takeIf { reasoningText != null }
                )
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

            val results = ArrayList<Pair<String, String>>()
            var malformedReason: String? = null
            val calls = ArrayList<Pair<String, String>>() // (name, arguments JSON) of the calls that parsed
            for ((payload, _) in payloads.take(MAX_CALLS_PER_ROUND)) {
                when (val parsed = ToolProtocol.parseCall(payload)) {
                    is ToolCallParseResult.Malformed -> malformedReason = malformedReason ?: parsed.reason
                    is ToolCallParseResult.Ok -> calls.add(parsed.call.name to parsed.call.arguments)
                }
            }
            // No limit on how many rounds a turn may use. The one thing that is stopped is a model that is stuck:
            // the exact same call (same tool, same arguments) many rounds in a row never gets anywhere new.
            val signature = calls.joinToString("\n") { it.first + "\u0000" + it.second }
            if (signature.isNotEmpty() && signature == lastCallSignature) identicalRounds++ else identicalRounds = 0
            lastCallSignature = signature.ifEmpty { null }
            if (identicalRounds >= MAX_IDENTICAL_ROUNDS) {
                val text = "The model made the same tool call ${identicalRounds + 1} times in a row, so it was stopped. " +
                    "Send a message (for example \"continue\") to let it try again."
                emit(AgentEvent.Notice(text))
                val id = sink.save(NewMessage(MessageRole.TOOL, text))
                emit(AgentEvent.MessageSaved(id, MessageRole.TOOL))
                emit(AgentEvent.Done)
                return@flow
            }
            for ((name, args) in calls) emit(AgentEvent.ToolStarted(name, args))

            val toolsStartedAt = System.currentTimeMillis()
            val toolCtx = ToolContext(request.sessionId, assistantId)
            suspend fun runOne(name: String, args: String): String = when {
                name in enabledNames -> toolManager.executeToolCall(name, args, toolCtx)
                !toolsEnabled -> "Error: tools are disabled. Answer without them."
                else -> "Error: tool '$name' is not enabled. Enabled tools: ${enabledNames.sorted().joinToString(", ")}."
            }
            // Calls that only read from the network do not depend on each other: run them side by side, so a round
            // with three page reads costs the slowest one, not the sum. Anything else keeps its order.
            val outputs: List<String> =
                if (calls.size > 1 && calls.all { it.first in enabledNames && toolManager.isParallelSafe(it.first) }) {
                    coroutineScope { calls.map { c -> async { runOne(c.first, c.second) } }.awaitAll() }
                } else {
                    calls.map { c -> runOne(c.first, c.second) }
                }
            for (i in calls.indices) results.add(calls[i].first to outputs[i])

            // In AUTO mode, follow a successful/attempted web search with image results only when explicitly enabled.
            // Reuse the model's web query rather than spending another generation just to formulate an image query.
            if (settings.isWebSearchEnabled && settings.imageSearchEnabled && settings.autoImageSearchAfterWebSearch &&
                calls.any { it.first == "web_search" } && calls.none { it.first == "image_search" } &&
                "image_search" in enabledNames && !stopRequested
            ) {
                val webCall = calls.first { it.first == "web_search" }
                val query = runCatching { org.json.JSONObject(webCall.second).optString("query") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: searchQueryFrom(request.prompt)
                val imageArgs = org.json.JSONObject().put("query", query).toString()
                emit(AgentEvent.ToolStarted("image_search", imageArgs))
                results.add("image_search" to toolManager.executeToolCall("image_search", imageArgs, toolCtx))
            }

            val toolMs = System.currentTimeMillis() - toolsStartedAt
            emit(AgentEvent.ToolsFinished(results.map { it.first }, toolMs))

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
            val toolId = sink.save(NewMessage(MessageRole.TOOL, prompt, toolMs = toolMs))
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


    /**
     * Uses the current model to turn the user's intent into a concise web-search query.
     * The result is not shown as an assistant message and is never saved to chat history.
     * On generation failure/empty output, the caller safely falls back to the user's text.
     */
    private suspend fun generateSearchQuery(request: AgentRequest, turns: List<ChatTurn>): String {
        val prompt = """Create the best concise web search query for the user's latest request.
Use the conversation context only to resolve what the user means. Do not answer the request.
Return ONLY the search query, with no quotes, explanation, prefix, or markdown.
Prefer specific names, current/recent intent, and useful keywords. Keep it under 20 words.

Latest user request:
${request.prompt}""".trim()
        var finished: EngineEvent.Finished? = null
        return try {
            engineController.generate(
                GenerationRequest(
                    system = "You are a search-query writer. Output only one useful search query.",
                    history = turns.toList(),
                    prompt = prompt,
                    expectedKey = "${request.sessionId}:search-query:${System.nanoTime()}",
                    maxTokens = 48,
                    think = false,
                    images = emptyList()
                )
            ).collect { event -> if (event is EngineEvent.Finished) finished = event }
            val result = finished
            if (result != null && result.ok && !result.cancelled) result.answer else ""
        } catch (_: Exception) {
            ""
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
        /** Identical consecutive tool rounds after which the model is considered stuck. Not a cap on tool use. */
        const val MAX_IDENTICAL_ROUNDS = 8
        const val MAX_CALLS_PER_ROUND = 16
        const val NOTE_STOPPED = "Stopped"

        fun buildSystemPrompt(
            settings: EngineSettings,
            toolsEnabled: Boolean,
            toolPrompt: String = "",
            alwaysSearch: Boolean = false
        ): String {
            val base = settings.systemPrompt.trim()
            val extras = buildList {
                when {
                    toolsEnabled && toolPrompt.isNotBlank() -> add(toolPrompt)
                    alwaysSearch -> add(ALWAYS_SEARCH_HINT)
                }
                if (settings.interactiveUiEnabled) add(INTERACTIVE_UI_HINT)
            }
            return (listOf(base).filter { it.isNotBlank() } + extras).joinToString("\n\n").trim()
        }

        const val ALWAYS_SEARCH_HINT =
            "Web search results for the user's latest message are attached after it inside <tool_response> tags. " +
                "Use them when they are relevant and mention the source URLs you rely on. " +
                "If they are empty, irrelevant or an error, say so and answer from your own knowledge."

        /**
         * System-prompt text for Native UI. It is part of the cached prompt prefix, so it is short and never
         * changes per message: the model has to decide on its own, from the request, when a visual card fits.
         */
        const val INTERACTIVE_UI_HINT =
            "Native UI cards are ON. Use one WITHOUT being asked whenever the answer is a comparison (X vs Y, \"compare\", \"difference between\"), pros and cons, a table, a ranking, a timeline, steps or a plan, key numbers, a summary card, or slides: " +
                "write one short sentence, then ONE fenced ```bmoe-ui block. For normal chat, explanations, code and maths answer in plain text with no block. " +
                "The block is rendered with native Jetpack Compose; never use HTML, CSS or JavaScript, and never say you cannot make visuals.\n" +
                "Block = one compact, valid JSON object with short text. title is required, other fields optional: " +
                "{\"type\":\"rich\",\"title\":\"..\",\"description\":\"..\"," +
                "\"badges\":[{\"label\":\"..\",\"tone\":\"success|warning|danger|info|neutral\"}]," +
                "\"metrics\":[{\"label\":\"..\",\"value\":\"..\",\"detail\":\"..\"}]," +
                "\"comparison\":{\"leftTitle\":\"A\",\"rightTitle\":\"B\",\"rows\":[{\"label\":\"..\",\"left\":\"..\",\"right\":\"..\",\"highlight\":false}]}," +
                "\"slides\":[{\"title\":\"..\",\"subtitle\":\"..\",\"body\":\"..\"}]," +
                "\"lists\":[{\"title\":\"..\",\"style\":\"bullet|ordered\",\"items\":[{\"title\":\"..\",\"detail\":\"..\"}]}]," +
                "\"cards\":[{\"title\":\"..\",\"subtitle\":\"..\",\"body\":\"..\"}]," +
                "\"bars\":[{\"label\":\"..\",\"value\":70,\"valueLabel\":\"..\"}]," +
                "\"timeline\":[{\"time\":\"..\",\"title\":\"..\",\"detail\":\"..\",\"status\":\"done|current|upcoming\"}]," +
                "\"sections\":[{\"title\":\"..\",\"body\":\"..\"}]," +
                "\"actions\":[{\"label\":\"..\",\"prompt\":\"..\"}]}.\n" +
                "Example, \"compare cats and dogs\":\n```bmoe-ui\n" +
                "{\"type\":\"rich\",\"title\":\"Cats vs Dogs\",\"comparison\":{\"leftTitle\":\"Cats\",\"rightTitle\":\"Dogs\",\"rows\":[{\"label\":\"Care\",\"left\":\"Low\",\"right\":\"High\"},{\"label\":\"Exercise\",\"left\":\"Little\",\"right\":\"Daily walks\"}]}}\n```\n" +
                "Limits: 8 metrics/cards/slides, 12 rows/items/badges, 10 bars (0-100), 5 lists, 8 sections, 6 actions. Never invent measurements; label estimates. Actions send their prompt as a new user message."

        /** The user's message as a search query: first 200 characters, one line. */
        fun searchQueryFrom(message: String): String {
            var query = message.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            query = query.replace(Regex("^(search query|query|web search|search for)\\s*[:：-]\\s*", RegexOption.IGNORE_CASE), "")
                .trim().trim('"', '\'', '`')
            return query.take(200)
        }

        /**
         * Tools the model may use this turn: file, code and utility tools by their switches, the web-page tools
         * whenever web search is on (plus web_search itself in AUTO mode, in ALWAYS mode the app searches).
         * Only tools that are registered count.
         */
        fun enabledToolNames(settings: EngineSettings, registered: Set<String>, external: Set<String> = emptySet()): Set<String> {
            val names = HashSet<String>()
            if (settings.toolsExternalEnabled) names.addAll(external)
            if (settings.toolsFilesEnabled) names.addAll(FILE_TOOL_NAMES)
            if (settings.toolsCodeEnabled) names.addAll(CODE_TOOL_NAMES)
            if (settings.toolsUtilitiesEnabled) names.addAll(UTILITY_TOOL_NAMES)
            if (settings.isWebSearchEnabled) {
                names.addAll(WEB_PAGE_TOOL_NAMES)
                // Image search is independently switchable so it can be removed from the model's tool list.
                if (settings.imageSearchEnabled) names.add("image_search")
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
