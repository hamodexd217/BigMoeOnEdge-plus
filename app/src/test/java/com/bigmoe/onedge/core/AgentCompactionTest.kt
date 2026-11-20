package com.bigmoe.onedge.core

import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.entity.MessageRole
import com.bigmoe.onedge.tools.ToolManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AgentCompactionTest {

    private class RecordingSink : MessageSink {
        val saved = ArrayList<NewMessage>()
        override suspend fun save(message: NewMessage): String {
            saved.add(message)
            return "id${saved.size}"
        }
    }

    private class Env(scope: TestScope) {
        val fake = FakeEngineBackend() // reports n_ctx = 4096
        val engine = EngineController({ fake }, UnconfinedTestDispatcher(scope.testScheduler))
        val agent = AgentController(engine, ToolManager())
        val sink = RecordingSink()
    }

    private suspend fun env(scope: TestScope): Env {
        val e = Env(scope)
        e.engine.loadModel(File.createTempFile("m", ".gguf").apply { deleteOnExit() }.path, testLoadConfig())
        return e
    }

    private val filler = "word ".repeat(300) // about 500 estimated tokens per message

    private fun longHistory() = (1..12).flatMap {
        listOf(
            HistoryMessage("u$it", MessageRole.USER, "q$it $filler"),
            HistoryMessage("a$it", MessageRole.ASSISTANT, "r$it $filler")
        )
    }

    private val summaryTokens = listOf("The user asked many ", "questions about gradle.")
    private val summaryText = "The user asked many questions about gradle."

    @Test
    fun aLongChatIsSummarizedBeforeTheAnswerAndTheSummaryIsSaved() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = summaryTokens))
        e.fake.script.add(ScriptedTurn(tokens = listOf("done")))
        val events = e.agent.runAgentLoop(AgentRequest("s1", longHistory(), "next question", EngineSettings()), e.sink).toList()

        assertEquals(2, e.fake.calls.size)
        assertEquals(Compaction.SUMMARIZER_SYSTEM, e.fake.calls[0].system)
        assertTrue(e.fake.calls[1].system.contains(summaryText))
        assertTrue("the old messages must not be replayed", e.fake.calls[1].history.size < 24)
        assertTrue(e.fake.calls[1].clearKv)
        assertEquals("next question", e.fake.calls[1].prompt)

        assertEquals(listOf(MessageRole.SUMMARY, MessageRole.ASSISTANT), e.sink.saved.map { it.role })
        val stored = Compaction.decode(e.sink.saved[0].content)!!
        assertEquals(summaryText, stored.text)
        assertTrue(longHistory().any { it.id == stored.uptoId })
        assertTrue(events.any { it is AgentEvent.Compacting })
        assertTrue(events.filterIsInstance<AgentEvent.Compacted>().single().messages > 0)
        assertEquals("done", events.filterIsInstance<AgentEvent.Text>().joinToString("") { it.delta })
    }

    @Test
    fun nothingIsSummarizedWhenTheSettingIsOff() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = listOf("done")))
        e.agent.runAgentLoop(AgentRequest("s1", longHistory(), "next", EngineSettings(autoCompactEnabled = false)), e.sink).toList()
        assertEquals(1, e.fake.calls.size)
        assertEquals(listOf(MessageRole.ASSISTANT), e.sink.saved.map { it.role })
        assertFalse(e.fake.calls[0].system.contains("summarized"))
    }

    @Test
    fun aShortChatIsLeftAlone() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = listOf("done")))
        val hist = listOf(HistoryMessage("u1", MessageRole.USER, "hi"), HistoryMessage("a1", MessageRole.ASSISTANT, "hello"))
        e.agent.runAgentLoop(AgentRequest("s1", hist, "more", EngineSettings()), e.sink).toList()
        assertEquals(1, e.fake.calls.size)
    }

    @Test
    fun aStoredSummaryReplacesTheMessagesItCovers() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(tokens = listOf("ok")))
        val hist = listOf(
            HistoryMessage("u1", MessageRole.USER, "old question"),
            HistoryMessage("a1", MessageRole.ASSISTANT, "old answer"),
            HistoryMessage("s1", MessageRole.SUMMARY, Compaction.encode("a1", "OLD SUMMARY TEXT")),
            HistoryMessage("u2", MessageRole.USER, "newer question"),
            HistoryMessage("a2", MessageRole.ASSISTANT, "newer answer")
        )
        e.agent.runAgentLoop(AgentRequest("s1", hist, "now", EngineSettings()), e.sink).toList()
        val call = e.fake.calls.single()
        assertTrue(call.system.contains("OLD SUMMARY TEXT"))
        assertEquals(listOf(ChatTurn("user", "newer question"), ChatTurn("assistant", "newer answer")), call.history)
    }

    @Test
    fun aFailedSummaryDoesNotStopTheTurn() = runTest {
        val e = env(this)
        e.fake.script.add(ScriptedTurn(ok = false, error = "boom"))
        e.fake.script.add(ScriptedTurn(tokens = listOf("still answered")))
        val events = e.agent.runAgentLoop(AgentRequest("s1", longHistory(), "next", EngineSettings()), e.sink).toList()
        assertEquals(listOf(MessageRole.ASSISTANT), e.sink.saved.map { it.role })
        assertEquals("still answered", events.filterIsInstance<AgentEvent.Text>().joinToString("") { it.delta })
        assertEquals(0, events.filterIsInstance<AgentEvent.Compacted>().single().messages)
    }
}
