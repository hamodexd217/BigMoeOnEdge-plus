package com.bigmoe.onedge.ui.chat

import com.bigmoe.onedge.parser.CodeFences
import com.bigmoe.onedge.parser.FenceOpen

sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock

    /** [code] is the raw content only (no fence lines, no language label); that is exactly what Copy puts on the clipboard. */
    data class Code(val language: String, val code: String, val closed: Boolean, val fileName: String? = null) : MarkdownBlock

    /** Display math: `$$ ... $$` or `\[ ... \]`, alone on its line(s). [tex] is the LaTeX between the delimiters. */
    data class Math(val tex: String) : MarkdownBlock

    /** A generated prompt / template / instruction block; [text] is copied whole. */
    data class Prompt(val text: String, val closed: Boolean = true, val label: String = "Prompt") : MarkdownBlock
}

/** Small, dependency-free markdown parser designed for streaming chat text. */
object MarkdownLite {

    /**
     * Fence tags that mark a block as a prompt/template/instruction instead of code. They get the prompt card
     * (label + Copy of the complete content) rather than syntax highlighting.
     */
    private val PROMPT_TAGS: Map<String, String> = mapOf(
        "prompt" to "Prompt",
        "system-prompt" to "Prompt",
        "systemprompt" to "Prompt",
        "system" to "Prompt",
        "template" to "Template",
        "instruction" to "Instructions",
        "instructions" to "Instructions"
    )

    fun parse(text: String): List<MarkdownBlock> {
        if (text.isEmpty()) return emptyList()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<MarkdownBlock>()
        val prose = StringBuilder()
        var code: StringBuilder? = null
        var fence: FenceOpen? = null
        var prompt: StringBuilder? = null

        fun flushProse() {
            val value = prose.toString().trimEnd()
            if (value.isNotBlank()) blocks.add(MarkdownBlock.Paragraph(value))
            prose.setLength(0)
        }

        fun flushPrompt() {
            val value = prompt?.toString()?.trim() ?: return
            if (value.isNotBlank()) blocks.add(MarkdownBlock.Prompt(value))
            prompt = null
        }

        fun emitFence(open: FenceOpen, content: String, closed: Boolean) {
            val label = PROMPT_TAGS[open.tag.lowercase()]
            if (label != null) {
                if (content.isNotBlank()) blocks.add(MarkdownBlock.Prompt(content, closed, label))
            } else {
                blocks.add(MarkdownBlock.Code(open.tag, content, closed, open.fileName))
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            val open = fence
            if (open != null) {
                if (CodeFences.isClose(line, open)) {
                    emitFence(open, code.toString().trimEnd('\n'), true)
                    code = null
                    fence = null
                } else {
                    code!!.append(CodeFences.stripIndent(line, open.indent)).append('\n')
                }
                i++
                continue
            }

            if (prompt != null) {
                if (PROMPT_DELIMITER.matches(line.trim())) {
                    flushPrompt()
                } else {
                    prompt!!.append(line).append('\n')
                }
                i++
                continue
            }

            val opening = CodeFences.parseOpen(line)
            if (opening != null) {
                flushProse()
                fence = opening
                code = StringBuilder()
                i++
                continue
            }

            if (PROMPT_DELIMITER.matches(line.trim())) {
                // Treat --- as a prompt delimiter only when a matching closing delimiter exists.
                val close = (i + 1 until lines.size).firstOrNull { PROMPT_DELIMITER.matches(lines[it].trim()) }
                if (close != null) {
                    flushProse()
                    prompt = StringBuilder()
                    i++
                    continue
                }
            }

            // Display math on its own line(s). An unclosed one (still streaming in) stays ordinary text until it closes.
            val math = displayMath(lines, i)
            if (math != null) {
                flushProse()
                blocks.add(MarkdownBlock.Math(math.tex))
                if (math.after.isNotBlank()) prose.append(math.after.trim()).append('\n')
                i = math.next
                continue
            }

            prose.append(line).append('\n')
            i++
        }

        val openFence = fence
        if (openFence != null) {
            // While streaming, an open fence is still a code block; it remains visible but has no destructive/open action.
            emitFence(openFence, code.toString().trimEnd('\n'), false)
        } else if (prompt != null) {
            // Malformed/incomplete prompt block: preserve it as ordinary text.
            prose.append("---\n").append(prompt.toString())
        }
        flushProse()
        return blocks
    }

    private val PROMPT_DELIMITER = Regex("^\\s*---\\s*$")

    private class DisplayMath(val tex: String, val after: String, val next: Int)

    /** `$$ x $$` on one line, or `$$` ... `$$` over several lines; the same with `\[` and `\]`. */
    private fun displayMath(lines: List<String>, start: Int): DisplayMath? {
        val first = lines[start].trim()
        val (open, close) = when {
            first.startsWith("$$") -> "$$" to "$$"
            first.startsWith("\\[") -> "\\[" to "\\]"
            else -> return null
        }
        val rest = first.substring(open.length)
        val sameLine = rest.indexOf(close)
        if (sameLine >= 0) {
            val tex = rest.substring(0, sameLine).trim()
            return if (tex.isEmpty()) null else DisplayMath(tex, rest.substring(sameLine + close.length), start + 1)
        }
        for (k in start + 1 until lines.size) {
            val idx = lines[k].indexOf(close)
            if (idx < 0) continue
            val sb = StringBuilder(rest)
            for (m in start + 1 until k) sb.append('\n').append(lines[m])
            sb.append('\n').append(lines[k].substring(0, idx))
            val tex = sb.toString().trim()
            return if (tex.isEmpty()) null else DisplayMath(tex, lines[k].substring(idx + close.length), k + 1)
        }
        return null
    }
}
