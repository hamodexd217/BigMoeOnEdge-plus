package com.bigmoe.onedge.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class EngineControllerTest {

    private val cfg = testLoadConfig()

    private fun modelFile(): File = File.createTempFile("model", ".gguf").apply { deleteOnExit() }

    private fun request(prompt: String, key: String, history: List<ChatTurn> = emptyList(), maxTokens: Int = 256) =
        GenerationRequest("sys", history, prompt, key, maxTokens, think = true)

    @Test
    fun loadFailureBecomesFailedState() = runTest {
        val fake = FakeEngineBackend().apply { loadError = "unsupported architecture" }
        val c = EngineController({ fake }, UnconfinedTestDispatcher(testScheduler))
        val err = c.loadModel(modelFile().path, cfg)
        assertEquals("unsupported architecture", err)
        assertTrue(c.state.value is EngineState.Failed)
        assertFalse(c.isReady)
    }

    @Test
    fun missingFileIsReportedWithoutTouchingTheEngine() = runTest {
        val fake = FakeEngineBackend()
        val c = EngineController({ fake }, UnconfinedTestDispatcher(testScheduler))
        val err = c.loadModel("/definitely/not/here.gguf", cfg)
        assertNotNull(err)
        assertTrue(c.state.value is EngineState.Failed)
    }

    @Test
    fun successfulLoadPublishesModelInfo() = runTest {
        val f = modelFile()
        val c = EngineController({ FakeEngineBackend() }, UnconfinedTestDispatcher(testScheduler))
        assertNull(c.loadModel(f.path, cfg))
        val ready = c.state.value as EngineState.Ready
        assertEquals("fake", ready.info.arch)
        assertEquals(4096, ready.info.nCtx)
        assertEquals(8, ready.info.nExpertUsed)
        assertEquals(f.name, ready.info.name)
    }

    @Test
    fun generateWithoutModelFailsCleanly() = runTest {
        val c = EngineController({ FakeEngineBackend() }, UnconfinedTestDispatcher(testScheduler))
        val events = c.generate(request("hi", "k")).toList()
        val fin = events.single() as EngineEvent.Finished
        assertFalse(fin.ok)
        assertTrue(fin.error.contains("No model"))
    }

    @Test
    fun firstTurnResetsThenContinuesWhenStateIsCommitted() = runTest {
        val fake = FakeEngineBackend()
        val c = EngineController({ fake }, UnconfinedTestDispatcher(testScheduler))
        c.loadModel(modelFile().path, cfg)

        fake.script.add(ScriptedTurn(tokens = listOf("Hel", "lo")))
        val first = c.generate(request("hi", "k0")).toList()
        assertEquals(listOf("Hel", "lo"), first.filterIsInstance<EngineEvent.Answer>().map { it.delta })
        assertTrue((first.last() as EngineEvent.Finished).ok)
        assertTrue("engine state unknown before the caller commits", fake.calls[0].clearKv)

        c.commitState("k1")
        fake.script.add(ScriptedTurn(tokens = listOf("ok")))
        c.generate(request("next", "k1", history = listOf(ChatTurn("user", "hi"), ChatTurn("assistant", "Hello")))).toList()
        assertFalse("committed state matches -> continue the engine's KV", fake.calls[1].clearKv)
        assertTrue("no replay payload when continuing", fake.calls[1].history.isEmpty())
        assertEquals("next", fake.calls[1].prompt)
    }

    @Test
    fun differentConversationResetsAndReplaysHistory() = runTest {
        val fake = FakeEngineBackend()
        val c = EngineController({ fake }, UnconfinedTestDispatcher(testScheduler))
        c.loadModel(modelFile().path, cfg)
        c.commitState("chatA")

        fake.script.add(ScriptedTurn(tokens = listOf("x")))
        val hist = listOf(ChatTurn("user", "old q"), ChatTurn("assistant", "old a"))
        c.generate(request("new q", "chatB", history = hist)).toList()
        val call = fake.calls.single()
        assertTrue(call.clearKv)
        assertEquals(hist, call.history)
        assertEquals("sys", call.system)
        assertEquals("new q", call.prompt)
    }

    @Test
    fun cancelledContinuedTurnKeepsTheEngineStateKey() = runTest {
        val fake = FakeEngineBackend()
        val c = EngineController({ fake }, UnconfinedTestDispatcher(testScheduler))
        c.loadModel(modelFile().path, cfg)
        c.commitState("k1")
        fake.script.add(ScriptedTurn(tokens = listOf("par"), cancelled = true))
        val fin = c.generate(request("q", "k1")).toList().last() as EngineEvent.Finished
        assertTrue(fin.cancelled)
        // the engine rolled the turn back: asking again with the same expected key must still continue
        fake.script.add(ScriptedTurn(tokens = listOf("ok")))
        c.generate(request("q again", "k1")).toList()
        assertFalse(fake.calls[1].clearKv)
    }

    @Test
    fun contextOverflowRetriesWithLessHistory() = runTest {
        val fake = FakeEngineBackend()
        val c = EngineController({ fake }, UnconfinedTestDispatcher(testScheduler))
        c.loadModel(modelFile().path, cfg)
        fake.script.add(ScriptedTurn(ok = false, error = "prompt + n_predict exceeds the session n_ctx (4096); open the session with a larger n_ctx"))
        fake.script.add(ScriptedTurn(tokens = listOf("fine")))
        val hist = (1..40).flatMap { listOf(ChatTurn("user", "question $it ".repeat(60)), ChatTurn("assistant", "answer $it ".repeat(60))) }
        val events = c.generate(request("now", "kX", history = hist)).toList()
        assertEquals(2, fake.calls.size)
        assertTrue(fake.calls[1].history.size < fake.calls[0].history.size)
        assertTrue((events.last() as EngineEvent.Finished).ok)
        assertEquals("only one Finished is ever emitted", 1, events.count { it is EngineEvent.Finished })
    }

    @Test
    fun nonOverflowErrorIsNotRetried() = runTest {
        val fake = FakeEngineBackend()
        val c = EngineController({ fake }, UnconfinedTestDispatcher(testScheduler))
        c.loadModel(modelFile().path, cfg)
        fake.script.add(ScriptedTurn(ok = false, error = "decode failed"))
        val fin = c.generate(request("q", "k")).toList().last() as EngineEvent.Finished
        assertFalse(fin.ok)
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun unicodeTokensSurviveTheByteBoundary() = runTest {
        val fake = FakeEngineBackend()
        val c = EngineController({ fake }, UnconfinedTestDispatcher(testScheduler))
        c.loadModel(modelFile().path, cfg)
        fake.script.add(ScriptedTurn(tokens = listOf("مرحبا", " 😀", " €")))
        val text = c.generate(request("q", "k")).toList().filterIsInstance<EngineEvent.Answer>().joinToString("") { it.delta }
        assertEquals("مرحبا 😀 €", text)
    }

    @Test
    fun maxTokensIsClampedAndUnlimitedIsZero() = runTest {
        val fake = FakeEngineBackend()
        val c = EngineController({ fake }, UnconfinedTestDispatcher(testScheduler))
        c.loadModel(modelFile().path, cfg)
        fake.script.add(ScriptedTurn(tokens = listOf("a")))
        c.generate(request("q", "k", maxTokens = -1)).toList()
        fake.script.add(ScriptedTurn(tokens = listOf("a")))
        c.generate(request("q", "k", maxTokens = 1_000_000)).toList()
        assertEquals(0, fake.calls[0].maxTokens)
        assertEquals(2048, fake.calls[1].maxTokens)
    }

    /** Real threads: stopping a blocked native generation must end the flow with a cancelled result. */
    @Test
    fun stopGenerationEndsABlockedGeneration() = runBlocking {
        val fake = FakeEngineBackend()
        val c = EngineController({ fake }, Dispatchers.IO)
        c.loadModel(modelFile().path, cfg)
        fake.script.add(ScriptedTurn(tokens = listOf("partial"), blockUntilStopped = true))
        val events = ArrayList<EngineEvent>()
        val job = launch(Dispatchers.Default) { c.generate(request("q", "k")).toList(events) }
        withTimeout(5_000) { while (fake.started.count > 0) kotlinx.coroutines.delay(10) }
        c.stopGeneration()
        withTimeout(5_000) { job.join() }
        val fin = events.last() as EngineEvent.Finished
        assertTrue(fin.ok && fin.cancelled)
        assertEquals("partial", (events.first() as EngineEvent.Answer).delta)
    }

    @Test
    fun unloadReleasesTheBackend() = runTest {
        val fake = FakeEngineBackend()
        val c = EngineController({ fake }, UnconfinedTestDispatcher(testScheduler))
        c.loadModel(modelFile().path, cfg)
        c.unload()
        assertTrue(fake.freed)
        assertTrue(c.state.value is EngineState.Unloaded)
    }
}
