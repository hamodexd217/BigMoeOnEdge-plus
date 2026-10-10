package com.bigmoe.onedge.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The API-server side of EngineController: the queue, the in-app priority rule and the chat flow. These use real
 * threads (runBlocking + Dispatchers.IO), like the engine itself, because arbitration is about wall-clock waiting.
 */
class EngineControllerApiTest {

    private val cfg = testLoadConfig()

    private fun ready(fake: FakeEngineBackend): EngineController {
        val c = EngineController({ fake }, Dispatchers.IO)
        val f = File.createTempFile("model", ".gguf").apply { deleteOnExit() }
        assertNull(runBlocking { c.loadModel(f.path, cfg) })
        return c
    }

    private val req = ChatGenRequest(messagesJson = "[]", maxTokens = 64, think = false)

    @Test
    fun chatFlowStreamsTextAndReportsToolCallsAndReuse() = runBlocking {
        val fake = FakeEngineBackend()
        fake.chatScript.add(
            ScriptedChat(
                answerTokens = listOf("Hel", "lo"),
                toolCalls = listOf(ChatToolCall("call_1", "read_file", "{\"path\":\"a.txt\"}")),
                stopReason = "tool_use"
            )
        )
        val c = ready(fake)
        val lease = c.acquireForApi(1000)
        assertNotNull(lease)
        val events = c.generateChat(lease!!, req).toList()
        lease.release()
        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<ChatEvent.Answer>().map { it.delta })
        val fin = events.last() as ChatEvent.Finished
        assertTrue(fin.ok)
        assertEquals("tool_use", fin.stopReason)
        assertEquals(1, fin.toolCalls.size)
        assertEquals("read_file", fin.toolCalls[0].name)
        assertEquals(60, fin.stats.nReused)
        assertEquals(100, fin.stats.nPromptTotal)
        assertFalse(fin.preempted)
    }

    @Test
    fun secondApiRequestWaitsThenGets529WhenTheEngineStaysBusy() = runBlocking {
        val c = ready(FakeEngineBackend())
        val first = c.acquireForApi(1000)
        assertNotNull(first)
        val t0 = System.currentTimeMillis()
        val second = c.acquireForApi(300)
        val waited = System.currentTimeMillis() - t0
        assertNull(second)
        assertTrue("waited $waited ms", waited >= 250)
        first!!.release()
        // Once released the engine is available again.
        val third = c.acquireForApi(300)
        assertNotNull(third)
        third!!.release()
    }

    @Test
    fun aQueuedApiRequestRunsAfterTheFirstIsReleased() = runBlocking {
        val c = ready(FakeEngineBackend())
        val first = c.acquireForApi(1000)!!
        val queued = async(Dispatchers.IO) { c.acquireForApi(3000) }
        Thread.sleep(150)
        first.release()
        val second = queued.await()
        assertNotNull(second)
        second!!.release()
    }

    @Test
    fun inAppChatPreemptsARunningApiGeneration() = runBlocking {
        val fake = FakeEngineBackend()
        fake.chatScript.add(ScriptedChat(answerTokens = listOf("x"), blockUntilStopped = true))
        fake.script.add(ScriptedTurn(tokens = listOf("hi")))
        val c = ready(fake)
        val lease = c.acquireForApi(1000)!!
        val apiRun = async(Dispatchers.IO) { c.generateChat(lease, req).toList() }
        assertTrue(fake.chatStarted.await(5, TimeUnit.SECONDS))

        val appEvents = async(Dispatchers.IO) {
            c.generate(GenerationRequest("sys", emptyList(), "hello", "k1", 64, think = false)).toList()
        }
        val apiEvents = apiRun.await()
        lease.release() // the server handler releases once its request has ended
        val api = apiEvents.last() as ChatEvent.Finished
        assertTrue(api.preempted)
        assertTrue(api.cancelled)

        val app = appEvents.await().last() as EngineEvent.Finished
        assertTrue(app.ok)
        assertEquals("hi", app.answer)
    }

    @Test
    fun queuedApiRequestsAreRefusedWhileTheAppHoldsTheEngine() = runBlocking {
        val fake = FakeEngineBackend()
        fake.script.add(ScriptedTurn(tokens = listOf("a"), blockUntilStopped = true))
        val c = ready(fake)
        val app = async(Dispatchers.IO) {
            c.generate(GenerationRequest("sys", emptyList(), "hello", "k1", 64, think = false)).toList()
        }
        assertTrue(fake.started.await(5, TimeUnit.SECONDS))
        assertNull(c.acquireForApi(200))
        c.stopGeneration()
        app.await()
    }

    @Test
    fun apiRequestIsRefusedWhenTooManyAreAlreadyWaiting() = runBlocking {
        val c = ready(FakeEngineBackend())
        val first = c.acquireForApi(1000)!!
        assertNull(c.acquireForApi(100, maxQueued = 0))
        first.release()
    }
}
