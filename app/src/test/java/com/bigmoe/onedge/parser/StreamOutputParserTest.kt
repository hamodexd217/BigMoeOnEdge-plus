package com.bigmoe.onedge.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamOutputParserTest {

    private fun feed(parser: StreamOutputParser, tokens: List<String>): List<ParsedChunk> {
        val all = ArrayList<ParsedChunk>()
        tokens.forEach { all += parser.processToken(it) }
        all += parser.flush()
        return all
    }

    private fun text(chunks: List<ParsedChunk>) =
        chunks.filterIsInstance<ParsedChunk.Text>().joinToString("") { it.content }

    @Test
    fun plainTextPassesThroughUnchanged() {
        assertEquals("Hello world", text(feed(StreamOutputParser(), listOf("Hel", "lo ", "wor", "ld"))))
    }

    @Test
    fun anglebracketsThatAreNotTagsAreNotLost() {
        val chunks = feed(StreamOutputParser(), listOf("a < b and c <", "d> ", "<tool", "box> x"))
        assertEquals("a < b and c <d> <toolbox> x", text(chunks))
    }

    @Test
    fun toolCallIsHiddenAndReported() {
        val chunks = feed(
            StreamOutputParser(),
            listOf("Let me look. ", "<tool_call>", "{\"name\":\"web_search\",", "\"arguments\":{\"query\":\"x\"}}", "</tool_call>")
        )
        assertEquals("Let me look. ", text(chunks))
        val inv = chunks.filterIsInstance<ParsedChunk.ToolInvocation>()
        assertEquals(1, inv.size)
        assertEquals("web_search", inv[0].toolName)
    }

    @Test
    fun tagSplitAtEveryPossiblePoint() {
        val full = "hi <tool_call>{\"name\":\"t\",\"arguments\":{}}</tool_call> bye"
        for (n in 1..12) {
            val chunks = feed(StreamOutputParser(), full.chunked(n))
            assertEquals("chunk size $n", "hi  bye", text(chunks))
            assertEquals("chunk size $n", 1, chunks.filterIsInstance<ParsedChunk.ToolInvocation>().size)
        }
    }

    @Test
    fun oneCharTokenStreamNeverLeaksTagFragments() {
        val full = "x<tool_call>{\"name\":\"t\"}</tool_call>"
        val p = StreamOutputParser()
        val shown = StringBuilder()
        full.forEach { ch -> p.processToken(ch.toString()).filterIsInstance<ParsedChunk.Text>().forEach { shown.append(it.content) } }
        p.flush().filterIsInstance<ParsedChunk.Text>().forEach { shown.append(it.content) }
        assertEquals("x", shown.toString())
    }

    @Test
    fun unterminatedToolCallWithValidJsonIsRecoveredOnFlush() {
        val chunks = feed(StreamOutputParser(), listOf("<tool_call>{\"name\":\"t\",\"arguments\":{\"q\":\"v\"}}"))
        assertEquals(1, chunks.filterIsInstance<ParsedChunk.ToolInvocation>().size)
        assertEquals("", text(chunks))
    }

    @Test
    fun unterminatedToolCallWithBrokenJsonIsReportedMalformed() {
        val chunks = feed(StreamOutputParser(), listOf("<tool_call>this is not json"))
        assertEquals(1, chunks.filterIsInstance<ParsedChunk.MalformedToolCall>().size)
    }

    @Test
    fun malformedClosedBlockIsReportedNotShown() {
        val chunks = feed(StreamOutputParser(), listOf("a<tool_call>{oops}</tool_call>b"))
        assertEquals("ab", text(chunks))
        assertEquals(1, chunks.filterIsInstance<ParsedChunk.MalformedToolCall>().size)
    }

    @Test
    fun twoCallsInOneMessage() {
        val chunks = feed(
            StreamOutputParser(),
            listOf("<tool_call>{\"name\":\"a\"}</tool_call><tool_call>{\"name\":\"b\"}</tool_call>")
        )
        assertEquals(listOf("a", "b"), chunks.filterIsInstance<ParsedChunk.ToolInvocation>().map { it.toolName })
    }

    @Test
    fun trailingPartialTagIsReleasedByFlush() {
        assertEquals("value <tool_c", text(feed(StreamOutputParser(), listOf("value <tool_c"))))
    }

    @Test
    fun htmlArtifactDetectedOnlyWhenClosed() {
        val p = StreamOutputParser { "id1" }
        val a = p.processToken("Here:\n```ht") + p.processToken("ml\n<h1>Hi</h1>\n") + p.processToken("``")
        assertTrue(a.none { it is ParsedChunk.ArtifactDetected })
        val art = (p.processToken("`") + p.flush()).filterIsInstance<ParsedChunk.ArtifactDetected>()
        assertEquals(1, art.size)
        assertEquals(ArtifactType.HTML, art[0].artifact.type)
        assertEquals("<h1>Hi</h1>", art[0].artifact.content)
    }

    @Test
    fun artifactTextStaysInTheMessage() {
        val src = "Code:\n```svg\n<svg/>\n```\nDone"
        val chunks = feed(StreamOutputParser(), src.chunked(3))
        assertEquals(src, text(chunks))
        assertEquals(1, chunks.filterIsInstance<ParsedChunk.ArtifactDetected>().size)
    }

    @Test
    fun unterminatedFenceProducesNoArtifact() {
        val chunks = feed(StreamOutputParser(), listOf("```html\n<p>never closed"))
        assertTrue(chunks.none { it is ParsedChunk.ArtifactDetected })
        assertEquals("```html\n<p>never closed", text(chunks))
    }

    @Test
    fun ordinaryCodeFencesAreNotArtifacts() {
        val chunks = feed(StreamOutputParser(), listOf("```python\nprint(1)\n```"))
        assertTrue(chunks.none { it is ParsedChunk.ArtifactDetected })
    }

    @Test
    fun mermaidAndJsxKinds() {
        val chunks = feed(StreamOutputParser(), listOf("```mermaid\ngraph TD; A-->B\n```\n```jsx\n<A/>\n```"))
        val types = chunks.filterIsInstance<ParsedChunk.ArtifactDetected>().map { it.artifact.type }
        assertEquals(listOf(ArtifactType.MERMAID, ArtifactType.CODE), types)
    }

    @Test
    fun resetClearsAllState() {
        val p = StreamOutputParser()
        p.processToken("<tool_call>{\"name\"")
        p.reset()
        assertEquals("fresh", text(p.processToken("fresh") + p.flush()))
    }

    @Test
    fun messageContentArtifactsHaveStableIds() {
        val raw = "```html\n<b>x</b>\n```"
        val a1 = MessageContent.artifacts("m1", raw)
        val a2 = MessageContent.artifacts("m1", raw)
        assertEquals(listOf("m1#0"), a1.map { it.id })
        assertEquals(a1.map { it.id }, a2.map { it.id })
    }
}
