package com.bigmoe.onedge.parser

import com.bigmoe.onedge.workspace.FileKinds

/** The opening line of a fenced block: ```` ```javascript title="app.js" ````. */
data class FenceOpen(
    /** ` or ~ */
    val marker: Char,
    /** Number of marker characters; the closing fence needs at least as many. */
    val length: Int,
    /** Leading whitespace of the fence line; removed from the code lines (fences inside list items). */
    val indent: Int,
    /** Language tag exactly as written ("JavaScript", "c#", "c++"); empty for a bare fence. */
    val tag: String,
    /** Everything after the fence marker, trimmed. */
    val info: String,
    /** A file name found in the info string whose extension is a known text/code kind, else null. */
    val fileName: String?
)

/** One fenced block. [code] is the raw content only: no fence lines, no language label, `\n` line breaks. */
data class FencedBlock(
    val tag: String,
    val info: String,
    val fileName: String?,
    val code: String,
    val closed: Boolean,
    /** Offset in the scanned text just after the closing fence line (the text length for an unclosed block). */
    val endOffset: Int
)

/**
 * Line based fenced-code scanner (CommonMark-like): ``` and ~~~ fences, any indentation, a closing fence at least
 * as long as the opening one (so a ````markdown block may contain ``` fences). Both the chat renderer
 * ([com.bigmoe.onedge.ui.chat.MarkdownLite]) and the artifact detection ([StreamOutputParser]) use it, so what is
 * drawn as a code block and what becomes an artifact can never disagree. Never throws.
 */
object CodeFences {
    private val OPEN = Regex("^([ \\t]*)(`{3,}|~{3,})(.*)$")
    private val TAG = Regex("^[{.\\s]*(?:language-)?([A-Za-z0-9_+#-]+)")
    private val FILE = Regex("[\\w@\\-./\\\\]+\\.[A-Za-z0-9]{1,8}\\b")

    fun parseOpen(line: String): FenceOpen? {
        val m = OPEN.matchEntire(line.trimEnd('\r')) ?: return null
        val marker = m.groupValues[2][0]
        val rest = m.groupValues[3]
        // ```js code``` on one line is inline code, not a fence (CommonMark: no backticks in a backtick info string).
        if (marker == '`' && rest.contains('`')) return null
        val info = rest.trim()
        val tag = TAG.find(info)?.groupValues?.get(1).orEmpty()
        val fileName = FILE.findAll(info).map { it.value }.firstOrNull { FileKinds.isKnownTextName(it) }
        return FenceOpen(marker, m.groupValues[2].length, m.groupValues[1].length, tag, info, fileName)
    }

    fun isClose(line: String, open: FenceOpen): Boolean {
        val t = line.trim()
        return t.length >= open.length && t.all { it == open.marker }
    }

    /** Removes up to [indent] leading blanks from a code line. */
    fun stripIndent(line: String, indent: Int): String {
        var i = 0
        while (i < indent && i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return line.substring(i)
    }

    /**
     * All fenced blocks of [text]. With [final] false the last line is ignored while it has no line break yet
     * (it may still grow while streaming); an unclosed block is returned with `closed = false`.
     */
    fun scan(text: String, final: Boolean = true): List<FencedBlock> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<FencedBlock>()
        val code = StringBuilder()
        var open: FenceOpen? = null
        var pos = 0
        val n = text.length
        while (pos < n) {
            val nl = text.indexOf('\n', pos)
            val complete = nl >= 0
            if (!complete && !final) break
            val lineEnd = if (complete) nl else n
            val next = if (complete) nl + 1 else n
            val line = text.substring(pos, lineEnd).trimEnd('\r')
            val o = open
            if (o == null) {
                open = parseOpen(line)
                code.setLength(0)
            } else if (isClose(line, o)) {
                out.add(FencedBlock(o.tag, o.info, o.fileName, code.toString().trimEnd('\n'), true, next))
                open = null
            } else {
                code.append(stripIndent(line, o.indent)).append('\n')
            }
            pos = next
        }
        open?.let { out.add(FencedBlock(it.tag, it.info, it.fileName, code.toString().trimEnd('\n'), false, n)) }
        return out
    }
}
