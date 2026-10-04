package com.bigmoe.onedge.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolManagerTest {

    private class EchoTool : Tool {
        override val name = "echo"
        override val description = "echoes the text argument"
        override val parametersSchema = JSONObject("""{"type":"object","properties":{"text":{"type":"string"}}}""")
        override suspend fun execute(arguments: JSONObject) = "echo:" + arguments.optString("text")
    }

    private class SlowTool : Tool {
        override val name = "slow"
        override val description = "never finishes in time"
        override val parametersSchema = JSONObject()
        override suspend fun execute(arguments: JSONObject): String { delay(10_000); return "late" }
    }

    private class BrokenTool : Tool {
        override val name = "broken"
        override val description = "throws"
        override val parametersSchema = JSONObject()
        override suspend fun execute(arguments: JSONObject): String = throw IllegalStateException("kaboom")
    }

    private class HugeTool : Tool {
        override val name = "huge"
        override val description = "returns a lot"
        override val parametersSchema = JSONObject()
        override suspend fun execute(arguments: JSONObject) = "x".repeat(100_000)
    }

    @Test
    fun executesRegisteredTool() = runBlocking {
        val m = ToolManager().apply { registerTool(EchoTool()) }
        assertEquals("echo:hi", m.executeToolCall("echo", """{"text":"hi"}"""))
        assertEquals("echo:", m.executeToolCall("echo", ""))
    }

    @Test
    fun unknownToolListsAvailableOnes() = runBlocking {
        val m = ToolManager().apply { registerTool(EchoTool()) }
        val r = m.executeToolCall("nope", "{}")
        assertTrue(r.startsWith("Error"))
        assertTrue(r.contains("echo"))
    }

    @Test
    fun invalidArgumentsJsonBecomesErrorText() = runBlocking {
        val m = ToolManager().apply { registerTool(EchoTool()) }
        assertTrue(m.executeToolCall("echo", "{not json").startsWith("Error executing tool"))
    }

    @Test
    fun timeoutBecomesErrorText() = runBlocking {
        val m = ToolManager(toolTimeoutMs = 50).apply { registerTool(SlowTool()) }
        assertTrue(m.executeToolCall("slow", "{}").contains("timed out"))
    }

    @Test
    fun toolExceptionBecomesErrorText() = runBlocking {
        val m = ToolManager().apply { registerTool(BrokenTool()) }
        val r = m.executeToolCall("broken", "{}")
        assertTrue(r.contains("kaboom"))
    }

    @Test
    fun oversizedResultsAreTruncated() = runBlocking {
        val m = ToolManager(maxResultChars = 1000).apply { registerTool(HugeTool()) }
        val r = m.executeToolCall("huge", "{}")
        assertTrue(r.length < 1100)
        assertTrue(r.endsWith("[truncated]"))
    }

    private class ProtectedTool : Tool {
        override val name = "protected"
        override val description = "needs approval"
        override val parametersSchema = JSONObject()
        override fun confirmation(arguments: JSONObject) = ConfirmationRequest("Approve?", "do it", destructive = true)
        override suspend fun execute(arguments: JSONObject) = "changed"
    }

    @Test
    fun approvalTrueRunsAndFalseDoesNotRun() = runBlocking {
        val m = ToolManager().apply { registerTool(ProtectedTool()) }
        var seen = false
        m.confirmationHandler = ConfirmationHandler { seen = true; true }
        assertEquals("changed", m.executeToolCall("protected", "{}"))
        assertTrue(seen)
        m.confirmationHandler = ConfirmationHandler { false }
        assertTrue(m.executeToolCall("protected", "{}").contains("declined"))
    }

    @Test
    fun protectedToolWithoutApprovalSurfaceNeverRuns() = runBlocking {
        val m = ToolManager().apply { registerTool(ProtectedTool()) }
        assertTrue(m.executeToolCall("protected", "{}").contains("no approval screen"))
    }

    @Test
    fun promptDescribesToolsAndCallFormat() {
        val m = ToolManager().apply { registerTool(EchoTool()) }
        val p = m.getToolsDescriptionPrompt()
        assertTrue(p.contains("echo"))
        assertTrue(p.contains("<tool_call>"))
        assertEquals("", ToolManager().getToolsDescriptionPrompt())
    }
}
