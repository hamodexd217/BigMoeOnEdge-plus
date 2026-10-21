package com.bigmoe.onedge.ui

import com.bigmoe.onedge.ui.chat.InlineMarkdown
import com.bigmoe.onedge.ui.chat.MarkdownTable
import com.bigmoe.onedge.ui.chat.MathText
import com.bigmoe.onedge.ui.chat.MdLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSupportTest {
    @Test
    fun classifiesHeadingsListsQuotesAndRules() {
        assertEquals(MdLine.Heading(2, "Heading"), MdLine.classify("## Heading"))
        assertEquals(MdLine.Bullet(1, "-", "nested"), MdLine.classify("  - nested"))
        assertEquals(MdLine.Quote(2, "quoted"), MdLine.classify(">> quoted"))
        assertEquals(MdLine.Rule, MdLine.classify("---"))
    }

    @Test
    fun collectsPipeTableAndReturnsFirstLineAfterIt() {
        val actual = MarkdownTable.collect(
            listOf("| Name | Value |", "| --- | :---: |", "| RAM | 12 GB |", "next paragraph"), 0
        )
        assertEquals(listOf(listOf("Name", "Value"), listOf("RAM", "12 GB")), actual?.first)
        assertEquals(3, actual?.second)
    }

    @Test
    fun parsesInlineFormattingWithoutLeakingDelimiters() {
        val spans = InlineMarkdown.parse("**bold** `code` ~~gone~~ [link](https://example.org)")
        assertTrue(spans.any { it.bold && it.text == "bold" })
        assertTrue(spans.any { it.code && it.text == "code" })
        assertTrue(spans.any { it.strike && it.text == "gone" })
        assertTrue(spans.any { it.link && it.text == "link" })
    }

    @Test
    fun markdownLinksPreserveHttpDestinationForTapHandling() {
        val link = InlineMarkdown.parse("Open [the docs](https://example.org/docs?q=gguf) now")
            .first { it.link }
        assertEquals("the docs", link.text)
        assertEquals("https://example.org/docs?q=gguf", link.linkTarget)
    }

    @Test
    fun displaysCommonMathAsReadableUnicode() {
        assertEquals("(a)/(b)", MathText.toUnicode("\\frac{a}{b}"))
        assertTrue(MathText.toUnicode("\\alpha + \\beta").contains("α"))
        assertTrue(MathText.toUnicode("x^2").codePoints().anyMatch { it == 0x00B2 })
    }
}
