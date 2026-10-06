package com.bigmoe.onedge.core

import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.WebSearchMode
import com.bigmoe.onedge.data.local.entity.MessageRole
import com.bigmoe.onedge.tools.Tool
import com.bigmoe.onedge.tools.ToolManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AgentControllerTest {

    private val cfg = testLoadConfig()

    private class RecordingSink : MessageSink {
        val saved = ArrayList<NewMessage>()
        override suspend fun save(message: NewMessage): String {
            saved.add(message)
            return "id${saved.size}"
        }
    }

    private class FakeSearchTool(private val reply: String = "RESULT-TEXT") : Tool {
        val calls = ArrayList<JSONObject>()
        override val name = "web_search"
        override val description = "search"
        override val parametersSchema = JSONObject("""{"type":"object"}""")
        override suspend fun execute(arguments: JSONObject): String {
            calls.add(arguments)
            return reply
        }
    }

    private class Env(scope: TestScope) {
        val fake = FakeEngineBackend()
        val engine = EngineController({ fake }, UnconfinedTestDispatcher(scope.testScheduler))
        val tool = FakeSearchTool()
        val tools = ToolManager().apply { registerTool(tool) }
        val agent = AgentController(engine, tools)
        val sink = RecordingSink()
    }

    private suspend fun env(scope: TestScope): Env {
        val e = Env(scope)
        e.engine.loadModel(File.createTempFile("m", ".gguf").apply { deleteOnExit() }.path, cfg)
        return e
    }

    private val call = """<tool_call>{"name":"web_search","arguments":{"query":"cats"}}</tool_call>"""
    private val withSearch = EngineSettings(isWebSearchEnabled = true, webSearchMode = WebSearchMode.AUTO)

    private fun req(history: List<HistoryMessage> = emptyList(), prompt: String = "hi", s: EngineSettings = withSearch, session: String = "s1") =
        AgentRequest(session, history, prompt, s)

    @Test
    fun plainAnswerIsStreamedSavedWithRealStatsAndCommitted() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = listOf("Hel", "lo")))
        val events = e.agent.runAgentLoop(req(), e.sink).toList()

        assertEquals("Hello", events.filterIsInstance<AgentEvent.Text>().joinToString("") { it.delta })
        val saved = e.sink.saved.single()
        assertEquals(MessageRole.ASSISTANT, saved.role)
        assertEquals("Hello", saved.content)
        assertEquals(2.5, saved.stats!!.tokensPerSecond, 0.0)
        assertEquals(40.0, saved.stats!!.cacheHitPct, 0.0)
        assertTrue(events.last() is AgentEvent.Done)

        // second turn in the same chat continues the engine's KV
        e.fake.script.add(ScriptedTurn(tokens = listOf("again")))
        val hist = listOf(HistoryMessage("u1", MessageRole.USER, "hi"), HistoryMessage("id1", MessageRole.ASSISTANT, "Hello"))
        e.agent.runAgentLoop(req(hist, "more"), e.sink).toList()
        assertTrue(e.fake.calls[0].clearKv)
        assertFalse(e.fake.calls[1].clearKv)
        assertEquals("more", e.fake.calls[1].prompt)
    }

    @Test
    fun editedMessageIsRegeneratedFromTheHistoryBeforeItAndReplaysTheEngine() = runTest {
        val e = env(this)
        // original two-turn conversation; the engine holds all of it
        e.fake.script.add(ScriptedTurn(tokens = listOf("first answer")))
        e.agent.runAgentLoop(req(prompt = "first question"), e.sink).toList()
        val hist = listOf(HistoryMessage("u1", MessageRole.USER, "first question"), HistoryMessage("id1", MessageRole.ASSISTANT, "first answer"))
        e.fake.script.add(ScriptedTurn(tokens = listOf("second answer")))
        e.agent.runAgentLoop(req(hist, "second question"), e.sink).toList()

        // the person edits the SECOND question: history is what came before it, the engine state is invalidated
        e.engine.invalidateState()
        e.fake.script.add(ScriptedTurn(tokens = listOf("new ", "answer")))
        val before = e.sink.saved.size
        val events = e.agent.runAgentLoop(req(hist, "second question, edited"), e.sink).toList()

        val call = e.fake.calls.last()
        assertTrue("engine state must be rebuilt, not continued", call.clearKv)
        assertEquals("second question, edited", call.prompt)
        assertEquals(listOf(ChatTurn("user", "first question"), ChatTurn("assistant", "first answer")), call.history)
        assertEquals("new answer", events.filterIsInstance<AgentEvent.Text>().joinToString("") { it.delta })
        val saved = e.sink.saved.drop(before).single()
        assertEquals(MessageRole.ASSISTANT, saved.role)
        assertEquals("new answer", saved.content)
        assertNotNull("telemetry is stored with the regenerated answer", saved.stats)
    }

    @Test
    fun editingTheFirstMessageSendsNoHistory() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = listOf("old")))
        e.agent.runAgentLoop(req(prompt = "old question"), e.sink).toList()
        e.engine.invalidateState()
        e.fake.script.add(ScriptedTurn(tokens = listOf("fresh")))
        e.agent.runAgentLoop(req(emptyList(), "edited question"), e.sink).toList()
        val call = e.fake.calls.last()
        assertTrue(call.clearKv)
        assertTrue(call.history.isEmpty())
        assertEquals("edited question", call.prompt)
    }

    @Test
    fun switchingChatsResetsAndReplaysSavedTurns() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = listOf("a")))
        e.agent.runAgentLoop(req(session = "chatA"), e.sink).toList()

        e.fake.script.add(ScriptedTurn(tokens = listOf("b")))
        val hist = listOf(
            HistoryMessage("x1", MessageRole.USER, "old question"),
            HistoryMessage("x2", MessageRole.ASSISTANT, "old answer")
        )
        e.agent.runAgentLoop(req(hist, "new question", session = "chatB"), e.sink).toList()
        val c = e.fake.calls[1]
        assertTrue(c.clearKv)
        assertEquals(listOf(ChatTurn("user", "old question"), ChatTurn("assistant", "old answer")), c.history)
        assertEquals("new question", c.prompt)
    }

    @Test
    fun systemPromptChangeForcesReplay() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = listOf("a")))
        e.agent.runAgentLoop(req(s = EngineSettings(systemPrompt = "v1")), e.sink).toList()
        e.fake.script.add(ScriptedTurn(tokens = listOf("b")))
        val hist = listOf(HistoryMessage("u", MessageRole.USER, "hi"), HistoryMessage("id1", MessageRole.ASSISTANT, "a"))
        e.agent.runAgentLoop(req(hist, "x", EngineSettings(systemPrompt = "v2")), e.sink).toList()
        assertTrue(e.fake.calls[1].clearKv)
        assertEquals("v2", e.fake.calls[1].system)
    }

    @Test
    fun toolCallRunsToolAndFeedsResultBackAsFollowUpTurn() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = listOf("Let me check. ", call)))
        e.fake.script.add(ScriptedTurn(tokens = listOf("Cats are great.")))
        val events = e.agent.runAgentLoop(req(prompt = "tell me about cats"), e.sink).toList()

        assertEquals("cats", e.tool.calls.single().getString("query"))
        assertTrue(events.any { it is AgentEvent.ToolStarted })
        // tool call is never shown as text
        assertFalse(events.filterIsInstance<AgentEvent.Text>().joinToString("") { it.delta }.contains("tool_call"))

        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT), e.sink.saved.map { it.role })
        assertTrue(e.sink.saved[0].content.contains("<tool_call>"))
        assertTrue(e.sink.saved[1].content.contains("RESULT-TEXT"))

        val second = e.fake.calls[1]
        assertFalse("tool follow-up continues the KV instead of replaying", second.clearKv)
        assertTrue(second.prompt.startsWith("<tool_response>"))
        assertTrue(second.prompt.contains("RESULT-TEXT"))
        assertTrue("tools described in the system prompt", e.fake.calls[0].system.contains("<tool_call>"))
    }

    @Test
    fun afterToolRoundNextUserTurnStillContinues() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = listOf(call)))
        e.fake.script.add(ScriptedTurn(tokens = listOf("final")))
        e.agent.runAgentLoop(req(), e.sink).toList()   // saves id1 (assistant), id2 (tool), id3 (assistant)

        e.fake.script.add(ScriptedTurn(tokens = listOf("more")))
        val hist = listOf(
            HistoryMessage("u1", MessageRole.USER, "hi"),
            HistoryMessage("id1", MessageRole.ASSISTANT, call),
            HistoryMessage("id2", MessageRole.TOOL, e.sink.saved[1].content),
            HistoryMessage("id3", MessageRole.ASSISTANT, "final")
        )
        e.agent.runAgentLoop(req(hist, "thanks"), e.sink).toList()
        assertFalse(e.fake.calls[2].clearKv)
    }

    @Test
    fun replayedHistoryMapsToolMessagesToUserTurns() {
        val turns = AgentController.historyToTurns(
            listOf(
                HistoryMessage("1", MessageRole.USER, "q"),
                HistoryMessage("2", MessageRole.ASSISTANT, "call"),
                HistoryMessage("3", MessageRole.TOOL, "<tool_response>r</tool_response>"),
                HistoryMessage("4", MessageRole.SYSTEM, "ignored")
            )
        )
        assertEquals(listOf("user", "assistant", "user"), turns.map { it.role })
    }

    @Test
    fun malformedToolCallGetsCorrectiveFeedbackAndRecovers() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = listOf("<tool_call>{nope</tool_call>")))
        e.fake.script.add(ScriptedTurn(tokens = listOf("Sorry, answering directly.")))
        val events = e.agent.runAgentLoop(req(), e.sink).toList()
        assertTrue(e.tool.calls.isEmpty())
        assertTrue(events.any { it is AgentEvent.Notice })
        assertTrue(e.fake.calls[1].prompt.contains("could not be parsed"))
        assertEquals(MessageRole.ASSISTANT, e.sink.saved.last().role)
        assertEquals("Sorry, answering directly.", e.sink.saved.last().content)
    }

    @Test
    fun toolLoopStopsAfterMaxRounds() = runTest {
        val e = env(this)
        repeat(AgentController.MAX_TOOL_ROUNDS + 1) { e.fake.script.add(ScriptedTurn(tokens = listOf(call))) }
        val events = e.agent.runAgentLoop(req(), e.sink).toList()
        assertEquals(AgentController.MAX_TOOL_ROUNDS, e.tool.calls.size)
        assertEquals(AgentController.MAX_TOOL_ROUNDS + 1, e.fake.calls.size)
        assertTrue(events.filterIsInstance<AgentEvent.Notice>().any { it.text.contains("limit") })
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun toolsDisabledDoesNotRunTheTool() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = listOf(call)))
        e.fake.script.add(ScriptedTurn(tokens = listOf("ok")))
        e.agent.runAgentLoop(req(s = EngineSettings(isWebSearchEnabled = false, webSearchMode = WebSearchMode.AUTO)), e.sink).toList()
        assertTrue(e.tool.calls.isEmpty())
        assertTrue(e.fake.calls[1].prompt.contains("tools are disabled"))
        assertFalse(e.fake.calls[0].system.contains("<tool_call>"))
    }

    @Test
    fun cancelledTurnSavesPartialWithNoteAndSkipsTools() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = listOf("part", "ial ", call), cancelled = true))
        val events = e.agent.runAgentLoop(req(), e.sink).toList()
        val saved = e.sink.saved.single()
        assertEquals(AgentController.NOTE_STOPPED, saved.note)
        assertTrue(e.tool.calls.isEmpty())
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun engineErrorIsShownAndNothingEmptyIsSaved() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(ok = false, error = "decode failed"))
        val events = e.agent.runAgentLoop(req(), e.sink).toList()
        assertEquals("decode failed", events.filterIsInstance<AgentEvent.Notice>().single().text)
        assertTrue(e.sink.saved.isEmpty())
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun afterAnErrorTheNextTurnReplays() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = listOf("a")))
        e.agent.runAgentLoop(req(), e.sink).toList()
        e.fake.script.add(ScriptedTurn(ok = false, error = "decode failed"))
        val hist = listOf(HistoryMessage("u", MessageRole.USER, "hi"), HistoryMessage("id1", MessageRole.ASSISTANT, "a"))
        e.agent.runAgentLoop(req(hist, "q2"), e.sink).toList()
        e.fake.script.add(ScriptedTurn(tokens = listOf("c")))
        e.agent.runAgentLoop(req(hist + HistoryMessage("u2", MessageRole.USER, "q2"), "q2 again"), e.sink).toList()
        assertTrue(e.fake.calls[2].clearKv)
        // the unanswered "q2" is folded into the new prompt so roles keep alternating
        assertEquals("q2\n\nq2 again", e.fake.calls[2].prompt)
    }

    @Test
    fun enabledToolNamesFollowFileCodeAndWebModes() {
        val registered = setOf("read_file", "run_javascript", "web_search", "read_webpage", "extract_webpage", "calculate", "get_datetime")
        val base = EngineSettings(toolsFilesEnabled = false, toolsCodeEnabled = false, toolsUtilitiesEnabled = false, isWebSearchEnabled = false)
        assertTrue(AgentController.enabledToolNames(base, registered).isEmpty())
        assertEquals(setOf("calculate", "get_datetime"), AgentController.enabledToolNames(base.copy(toolsUtilitiesEnabled = true), registered))
        assertTrue(AgentController.enabledToolNames(base.copy(toolsFilesEnabled = true), registered).contains("read_file"))
        assertTrue(AgentController.enabledToolNames(base.copy(toolsCodeEnabled = true), registered).contains("run_javascript"))
        val auto = base.copy(isWebSearchEnabled = true, webSearchMode = WebSearchMode.AUTO)
        assertTrue(AgentController.enabledToolNames(auto, registered).contains("web_search"))
        val always = auto.copy(webSearchMode = WebSearchMode.ALWAYS)
        assertFalse(AgentController.enabledToolNames(always, registered).contains("web_search"))
        assertTrue(AgentController.enabledToolNames(always, registered).contains("read_webpage"))
    }

    @Test
    fun systemPromptBuilderAppendsToolsOnlyWhenEnabled() {
        val s = EngineSettings(systemPrompt = "Base")
        assertEquals("Base", AgentController.buildSystemPrompt(s, false, "TOOLS"))
        assertEquals("Base\n\nTOOLS", AgentController.buildSystemPrompt(s, true, "TOOLS"))
        assertEquals("Base", AgentController.buildSystemPrompt(s, true, ""))
    }

    @Test
    fun alwaysModeSearchesFirstAndHandsResultsToTheModelInOneTurn() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = listOf("According to the results, yes.")))
        val always = EngineSettings(isWebSearchEnabled = true, webSearchMode = WebSearchMode.ALWAYS)
        val events = e.agent.runAgentLoop(req(prompt = "is the sky blue?", s = always), e.sink).toList()

        assertEquals("is the sky blue?", e.tool.calls.single().getString("query"))
        assertTrue(events.any { it is AgentEvent.ToolStarted })
        // saved: TOOL (search results) then the answer
        assertEquals(listOf(MessageRole.TOOL, MessageRole.ASSISTANT), e.sink.saved.map { it.role })
        val call = e.fake.calls.single()
        assertTrue(call.prompt.startsWith("is the sky blue?"))
        assertTrue(call.prompt.contains("<tool_response>"))
        assertTrue(call.prompt.contains("RESULT-TEXT"))
        assertTrue("always-mode system prompt explains the attached results", call.system.contains("Web search results"))
        assertFalse("no tool protocol in always mode", call.system.contains("<tool_call>"))
    }

    @Test
    fun alwaysModeReplayMatchesTheLiveTurn() {
        // live prompt = message + "\n\n" + tool result; replay merges USER + TOOL turns with the same join
        val hist = listOf(
            HistoryMessage("u1", MessageRole.USER, "q"),
            HistoryMessage("t1", MessageRole.TOOL, "<tool_response>r</tool_response>"),
            HistoryMessage("a1", MessageRole.ASSISTANT, "ans")
        )
        val turns = ContextPlanner.normalize(AgentController.historyToTurns(hist))
        assertEquals(listOf(ChatTurn("user", "q\n\n<tool_response>r</tool_response>"), ChatTurn("assistant", "ans")), turns)
    }

    @Test
    fun searchQueryIsOneLineAndCapped() {
        assertEquals("a b c", AgentController.searchQueryFrom("a\n  b \n\nc"))
        assertEquals(200, AgentController.searchQueryFrom("x".repeat(500)).length)
    }
}
