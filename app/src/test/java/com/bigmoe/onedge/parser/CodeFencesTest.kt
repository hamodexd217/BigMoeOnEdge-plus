package com.bigmoe.onedge.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeFencesTest {
    @Test
    fun parsesOpeningLines() {
        val o = CodeFences.parseOpen("```javascript filename=a.js")!!
        assertEquals("javascript", o.tag)
        assertEquals("a.js", o.fileName)
        assertEquals("c#", CodeFences.parseOpen("```c#")!!.tag)
        assertEquals("", CodeFences.parseOpen("```")!!.tag)
        assertEquals(4, CodeFences.parseOpen("````md")!!.length)
        assertNull(CodeFences.parseOpen("not a fence"))
        assertNull(CodeFences.parseOpen("```js inline```"))
    }

    @Test
    fun closingFenceNeedsAtLeastTheOpeningLength() {
        val open = CodeFences.parseOpen("````")!!
        assertFalse(CodeFences.isClose("```", open))
        assertTrue(CodeFences.isClose("````", open))
        assertTrue(CodeFences.isClose("  `````  ", open))
        assertFalse(CodeFences.isClose("```js", CodeFences.parseOpen("```")!!))
    }

    @Test
    fun scanReturnsRawCodeOnly() {
        val blocks = CodeFences.scan("x\n```javascript\nconsole.log(\"hello\");\n```\ny")
        assertEquals(1, blocks.size)
        assertEquals("console.log(\"hello\");", blocks[0].code)
        assertTrue(blocks[0].closed)
    }

    @Test
    fun unfinishedLastLineIsNotJudgedWhileStreaming() {
        assertTrue(CodeFences.scan("```js\nx\n``", final = false).none { it.closed })
        assertTrue(CodeFences.scan("```js\nx\n```", final = true).single().closed)
    }

    @Test
    fun emptyAndWeirdInput() {
        assertTrue(CodeFences.scan("").isEmpty())
        assertTrue(CodeFences.scan("plain text only").isEmpty())
        assertFalse(CodeFences.scan("```").single().closed)
    }
}
