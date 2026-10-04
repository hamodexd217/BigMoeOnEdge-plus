package com.bigmoe.onedge.ui

import com.bigmoe.onedge.ui.chat.MarkdownBlock
import com.bigmoe.onedge.ui.chat.MarkdownLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {
    @Test
    fun parsesProseAndClosedCode() {
        val blocks = MarkdownLite.parse("Hello\n\n```kotlin\nfun x() = 1\n```")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        val code = blocks[1] as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("fun x() = 1", code.code)
        assertTrue(code.closed)
    }

    @Test
    fun streamingOpenFenceStaysVisible() {
        val code = MarkdownLite.parse("before\n```python\nprint(1)").last() as MarkdownBlock.Code
        assertEquals("python", code.language)
        assertFalse(code.closed)
    }
}
