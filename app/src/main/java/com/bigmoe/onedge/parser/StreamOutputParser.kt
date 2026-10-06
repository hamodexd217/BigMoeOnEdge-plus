package com.bigmoe.onedge.parser

sealed interface ParsedChunk {
    /** Text that is safe to show. Tool-call blocks are never part of it. */
    data class Text(val content: String) : ParsedChunk
    data class ToolInvocation(val toolName: String, val rawArguments: String) : ParsedChunk
    /** A tool_call block whose JSON could not be repaired. */
    data class MalformedToolCall(val raw: String, val reason: String) : ParsedChunk
    data class ArtifactDetected(val artifact: ArtifactModel) : ParsedChunk
    /** The reasoning span, already separated by the engine; produced by AgentController, never by this parser. */
    data class Reasoning(val content: String) : ParsedChunk
}

enum class ArtifactType { HTML, SVG, MERMAID, CODE }

data class ArtifactModel(
    val id: String,
    val title: String,
    val type: ArtifactType,
    val content: String,
    /** Code-fence language; populated for CODE artifacts so saving keeps the right extension. */
    val language: String = "text",
    /** Optional filename supplied in the fence info string (for example: ```python foo.py). */
    val fileName: String? = null
)

/**
 * Incremental parser for the ANSWER channel (reasoning never goes through it).
 *
 *  - Text is emitted as soon as it cannot be the beginning of a "<tool_call>" tag, so a tag split across
 *    tokens ("<tool", "_call>") is never shown.
 *  - Tool-call blocks are swallowed and reported as [ParsedChunk.ToolInvocation] (or
 *    [ParsedChunk.MalformedToolCall]) once closed; an unterminated block is resolved in [flush].
 *  - Fenced code blocks using a supported artifact language are reported as [ParsedChunk.ArtifactDetected]
 *    once their closing fence has been seen; the code itself stays part of the text.
 *  - An unterminated fence at [flush] produces no artifact (the code just stays as text).
 */
class StreamOutputParser(private val idProvider: () -> String = { java.util.UUID.randomUUID().toString() }) {

    private val pending = StringBuilder()
    private val toolBuf = StringBuilder()
    private var inTool = false

    /** Everything emitted as Text so far; used to find complete fenced blocks. */
    private val visible = StringBuilder()

    /** Offset in [visible] before which everything is final: reported blocks and fence-free complete lines. */
    private var scannedUpTo = 0

    fun processToken(token: String): List<ParsedChunk> {
        val out = ArrayList<ParsedChunk>()
        if (token.isEmpty()) return out
        if (inTool) toolBuf.append(token) else pending.append(token)
        drain(out)
        return out
    }

    private fun drain(out: MutableList<ParsedChunk>) {
        while (true) {
            if (inTool) {
                val s = toolBuf.toString()
                val e = s.indexOf(ToolProtocol.CALL_CLOSE)
                if (e < 0) return
                emitToolBlock(s.substring(0, e), out)
                val rest = s.substring(e + ToolProtocol.CALL_CLOSE.length)
                toolBuf.setLength(0)
                inTool = false
                pending.append(rest)
                continue
            }
            val s = pending.toString()
            val open = s.indexOf(ToolProtocol.CALL_OPEN)
            if (open >= 0) {
                emitText(s.substring(0, open), out)
                toolBuf.setLength(0)
                toolBuf.append(s.substring(open + ToolProtocol.CALL_OPEN.length))
                pending.setLength(0)
                inTool = true
                continue
            }
            val hold = partialTagSuffix(s)
            emitText(s.substring(0, s.length - hold), out)
            pending.setLength(0)
            pending.append(s, s.length - hold, s.length)
            return
        }
    }

    /** Length of the longest suffix of [s] that is a proper prefix of the opening tag. */
    private fun partialTagSuffix(s: String): Int {
        val tag = ToolProtocol.CALL_OPEN
        val max = minOf(s.length, tag.length - 1)
        for (len in max downTo 1) {
            if (s.regionMatches(s.length - len, tag, 0, len)) return len
        }
        return 0
    }

    private fun emitToolBlock(payload: String, out: MutableList<ParsedChunk>) {
        when (val r = ToolProtocol.parseCall(payload)) {
            is ToolCallParseResult.Ok -> out.add(ParsedChunk.ToolInvocation(r.call.name, r.call.arguments))
            is ToolCallParseResult.Malformed -> out.add(ParsedChunk.MalformedToolCall(payload.trim(), r.reason))
        }
    }

    private fun emitText(text: String, out: MutableList<ParsedChunk>) {
        if (text.isEmpty()) return
        out.add(ParsedChunk.Text(text))
        visible.append(text)
        scanArtifacts(out, final = false)
    }

    /**
     * Reports every fenced block of a supported language whose closing fence has been seen. The scan uses the same
     * line based [CodeFences] as the chat renderer and only looks at the not-yet-final tail of [visible], so the
     * work per token stays small even for long code. While streaming, a last line without a line break is not
     * judged yet (it may still become a longer fence); [flush] makes the final decision.
     */
    private fun scanArtifacts(out: MutableList<ParsedChunk>, final: Boolean) {
        if (scannedUpTo >= visible.length) return
        val tail = visible.substring(scannedUpTo)
        if (!tail.contains("```") && !tail.contains("~~~")) {
            // No fence anywhere in the tail: all complete lines are final; keep only the unfinished last line.
            scannedUpTo += tail.lastIndexOf('\n') + 1
            return
        }
        var lastEnd = -1
        var unclosedOpen = false
        for (block in CodeFences.scan(tail, final)) {
            if (!block.closed) { unclosedOpen = true; continue }
            lastEnd = block.endOffset
            toArtifact(block)?.let { out.add(ParsedChunk.ArtifactDetected(it)) }
        }
        when {
            lastEnd >= 0 -> scannedUpTo += lastEnd
            // Backticks that never opened a fence (inline code): nothing to wait for, keep the tail short.
            !unclosedOpen -> scannedUpTo += tail.lastIndexOf('\n') + 1
        }
    }

    private fun toArtifact(block: FencedBlock): ArtifactModel? {
        // Priority: fence language tag, then the file name's extension (ArtifactLanguages.resolve).
        val language = ArtifactLanguages.resolve(block.tag, block.fileName) ?: return null
        if (block.code.isBlank()) return null
        return ArtifactModel(
            id = idProvider(),
            title = ArtifactLanguages.titleFor(language, block.fileName),
            type = ArtifactLanguages.typeFor(language),
            content = block.code,
            language = language,
            fileName = block.fileName
        )
    }

    /** End of stream: resolves a half-received tool call and releases held-back text. */
    fun flush(): List<ParsedChunk> {
        val out = ArrayList<ParsedChunk>()
        if (inTool) {
            val payload = toolBuf.toString()
            if (payload.isNotBlank()) emitToolBlock(payload, out)
            toolBuf.setLength(0)
            inTool = false
        }
        if (pending.isNotEmpty()) {
            emitText(pending.toString(), out)
            pending.setLength(0)
        }
        scanArtifacts(out, final = true)
        return out
    }

    fun reset() {
        pending.setLength(0)
        toolBuf.setLength(0)
        visible.setLength(0)
        scannedUpTo = 0
        inTool = false
    }
}
