package com.bigmoe.onedge.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileContextTest {
    @Test
    fun smallFileRendersWholeFileWithName() {
        val r = FileContext.render("notes.md", "hello\nworld", "hello", 1000)
        assertTrue(r.text.contains("name=\"notes.md\""))
        assertTrue(r.text.contains("hello\nworld"))
        assertEquals(false, r.truncated)
        assertEquals(2, r.shownLines)
    }

    @Test
    fun largeFileRanksRelevantChunksAndKeepsFirstChunk() {
        val content = (List(30) { i -> "line $i unrelated words" } +
            List(4) { "THE_NEEDLE alpha beta" } +
            List(30) { i -> "tail $i" }).joinToString("\n")
        val r = FileContext.render("big.txt", content, "THE_NEEDLE", 350)
        assertTrue(r.truncated)
        assertTrue(r.text.contains("THE_NEEDLE"))
        assertTrue(r.text.contains("lines=\"64\""))
        assertTrue(r.text.contains("lines 1-"))
        assertTrue(r.shownLines <= r.totalLines)
    }

    @Test
    fun tinyBudgetNeverLetsOneChunkConsumeAnUnboundedAmount() {
        val c = FileContext.chunk("x".repeat(5000), maxChars = 40)
        assertTrue(c.isNotEmpty())
        assertTrue(c.first().text.length <= 43)
    }
}
