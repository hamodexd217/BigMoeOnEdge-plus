package com.bigmoe.onedge.ui.chat

sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Code(val language: String, val code: String, val closed: Boolean) : MarkdownBlock
}

/** Small, dependency-free markdown parser designed for streaming chat text. */
object MarkdownLite {
    fun parse(text: String): List<MarkdownBlock> {
        if (text.isEmpty()) return emptyList()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<MarkdownBlock>()
        val prose = StringBuilder()
        var code: StringBuilder? = null
        var codeLanguage = ""

        fun flushProse() {
            val value = prose.toString().trimEnd()
            if (value.isNotBlank()) blocks.add(MarkdownBlock.Paragraph(value))
            prose.setLength(0)
        }

        for (line in lines) {
            if (code == null) {
                val fence = FENCE_OPEN.matchEntire(line)
                if (fence != null) {
                    flushProse()
                    code = StringBuilder()
                    codeLanguage = fence.groupValues[1].trim()
                } else {
                    prose.append(line).append('\n')
                }
            } else if (FENCE_CLOSE.matches(line.trim())) {
                blocks.add(MarkdownBlock.Code(codeLanguage, code.toString().trimEnd('\n'), true))
                code = null
                codeLanguage = ""
            } else {
                code.append(line).append('\n')
            }
        }

        if (code != null) {
            // While streaming, an open fence is still a code block; when the final answer ends without a
            // closing fence we keep the code visible but deliberately do not offer destructive/open actions.
            blocks.add(MarkdownBlock.Code(codeLanguage, code.toString().trimEnd('\n'), false))
        }
        flushProse()
        return blocks
    }

    private val FENCE_OPEN = Regex("^\\s*```([A-Za-z0-9_+.-]*)\\s*$")
    private val FENCE_CLOSE = Regex("^```\\s*$")
}
