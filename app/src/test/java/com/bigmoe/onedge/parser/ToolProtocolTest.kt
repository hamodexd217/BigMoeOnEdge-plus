package com.bigmoe.onedge.parser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProtocolTest {

    private fun ok(payload: String): ToolCall {
        val r = ToolProtocol.parseCall(payload)
        assertTrue("expected Ok for: $payload but was $r", r is ToolCallParseResult.Ok)
        return (r as ToolCallParseResult.Ok).call
    }

    private fun malformed(payload: String) {
        val r = ToolProtocol.parseCall(payload)
        assertTrue("expected Malformed for: $payload but was $r", r is ToolCallParseResult.Malformed)
    }

    @Test
    fun parsesPlainCall() {
        val c = ok("""{"name": "web_search", "arguments": {"query": "kotlin"}}""")
        assertEquals("web_search", c.name)
        assertEquals("kotlin", JSONObject(c.arguments).getString("query"))
    }

    @Test
    fun missingArgumentsBecomesEmptyObject() {
        assertEquals("{}", ok("""{"name":"ping"}""").arguments)
    }

    @Test
    fun toleratesCodeFenceAndSurroundingText() {
        val c = ok("Sure!\n```json\n{\"name\":\"web_search\",\"arguments\":{\"query\":\"a\"}}\n```\n")
        assertEquals("web_search", c.name)
    }

    @Test
    fun toleratesTrailingCommaAndSmartQuotes() {
        val c = ok("{\u201Cname\u201D: \u201Cweb_search\u201D, \u201Carguments\u201D: {\u201Cquery\u201D: \u201Cx\u201D,},}")
        assertEquals("x", JSONObject(c.arguments).getString("query"))
    }

    @Test
    fun toleratesSingleQuotes() {
        val c = ok("{'name': 'web_search', 'arguments': {'query': 'x'}}")
        assertEquals("web_search", c.name)
    }

    @Test
    fun acceptsParametersAndArgsAliases() {
        assertEquals("q", JSONObject(ok("""{"name":"t","parameters":{"query":"q"}}""").arguments).getString("query"))
        assertEquals("q", JSONObject(ok("""{"name":"t","args":{"query":"q"}}""").arguments).getString("query"))
    }

    @Test
    fun acceptsArgumentsEncodedAsJsonString() {
        val c = ok("""{"name":"t","arguments":"{\"query\":\"q\"}"}""")
        assertEquals("q", JSONObject(c.arguments).getString("query"))
    }

    @Test
    fun closesTruncatedObject() {
        val c = ok("""{"name": "web_search", "arguments": {"query": "cut off"""")
        assertEquals("web_search", c.name)
    }

    @Test
    fun bracesInsideStringsDoNotConfuseExtraction() {
        val c = ok("""{"name":"t","arguments":{"query":"a } b { c"}}""")
        assertEquals("a } b { c", JSONObject(c.arguments).getString("query"))
    }

    @Test
    fun rejectsGarbage() {
        malformed("no json here")
        malformed("{ this is not json }")
        malformed("""{"arguments":{"query":"x"}}""")
        malformed("""{"name":"t","arguments":[1,2]}""")
    }

    @Test
    fun stripsCompleteAndUnterminatedBlocks() {
        assertEquals("before  after", ToolProtocol.stripToolCalls("before <tool_call>{}</tool_call> after"))
        assertEquals("hello", ToolProtocol.stripToolCalls("hello <tool_call>{\"name\":\"x\""))
        assertEquals("plain", ToolProtocol.stripToolCalls("plain"))
    }

    @Test
    fun extractsAllPayloadsWithTerminationFlag() {
        val text = "<tool_call>{\"a\":1}</tool_call> mid <tool_call>{\"b\":2}</tool_call><tool_call>{\"c\":"
        val p = ToolProtocol.extractPayloads(text)
        assertEquals(3, p.size)
        assertTrue(p[0].second && p[1].second)
        assertTrue(!p[2].second)
        assertEquals("{\"c\":", p[2].first)
    }

    @Test
    fun wrapsResultsForTheModel() {
        val w = ToolProtocol.wrapResult("web_search", " result \n")
        assertTrue(w.startsWith("<tool_response>"))
        assertTrue(w.contains("[web_search]"))
        assertTrue(w.endsWith("</tool_response>"))
    }

    @Test
    fun messageContentLabelsAndDisplay() {
        val raw = "Let me check. <tool_call>{\"name\":\"web_search\",\"arguments\":{\"query\":\"weather\"}}</tool_call>"
        assertEquals("Let me check.", MessageContent.displayText(raw))
        assertEquals(listOf("web_search: weather"), MessageContent.toolCallLabels(raw))
        assertEquals(listOf("invalid tool call"), MessageContent.toolCallLabels("<tool_call>nope</tool_call>"))
    }
}
