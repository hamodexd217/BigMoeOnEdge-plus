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
    val language: String = "text"
)

/**
 * Incremental parser for the ANSWER channel (reasoning never goes through it).
 *
 *  - Text is emitted as soon as it cannot be the beginning of a "<tool_call>" tag, so a tag split across
 *    tokens ("<tool", "_call>") is never shown.
 *  - Tool-call blocks are swallowed and reported as [ParsedChunk.ToolInvocation] (or
 *    [ParsedChunk.MalformedToolCall]) once closed; an unterminated block is resolved in [flush].
 *  - Fenced code blocks (```html / svg / mermaid / jsx / tsx) are reported as [ParsedChunk.ArtifactDetected]
 *    once their closing fence has been seen; the code itself stays part of the text.
 *  - An unterminated fence at [flush] produces no artifact (the code just stays as text).
 */
class StreamOutputParser(private val idProvider: () -> String = { java.util.UUID.randomUUID().toString() }) {

    private val pending = StringBuilder()
    private val toolBuf = StringBuilder()
    private var inTool = false

    /** Everything emitted as Text so far; used to find complete fenced blocks. */
    private val visible = StringBuilder()
    private var artifactScanFrom = 0

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
        scanArtifacts(out)
    }

    private fun scanArtifacts(out: MutableList<ParsedChunk>) {
        while (true) {
            val m = FENCE.find(visible, artifactScanFrom) ?: return
            artifactScanFrom = m.range.last + 1
            val lang = m.groupValues[1].lowercase()
            val type = when (lang) {
                "html", "htm", "xhtml" -> ArtifactType.HTML
                "svg" -> ArtifactType.SVG
                "mermaid", "mmd" -> ArtifactType.MERMAID
                else -> if (isCodeLanguage(lang)) ArtifactType.CODE else null
            } ?: continue
            val code = m.groupValues[2].trimEnd()
            if (code.isBlank()) continue
            out.add(
                ParsedChunk.ArtifactDetected(
                    ArtifactModel(id = idProvider(), title = "${lang.uppercase()} artifact", type = type, content = code, language = lang)
                )
            )
        }
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
        return out
    }

    fun reset() {
        pending.setLength(0)
        toolBuf.setLength(0)
        visible.setLength(0)
        artifactScanFrom = 0
        inTool = false
    }

    private fun isCodeLanguage(language: String): Boolean {
        val l = language.lowercase()
        if (l in setOf("dockerfile", "makefile", "cmake", "shell", "bash", "zsh")) return true
        return com.bigmoe.onedge.workspace.FileKinds.languageFor("x.$l") != "text"
    }

    private companion object {
        // ```lang [attrs]\n code ```   (the closing fence may or may not start a line)
        val FENCE = Regex("```([A-Za-z0-9_+-]+)[^\\n]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
    }
}
