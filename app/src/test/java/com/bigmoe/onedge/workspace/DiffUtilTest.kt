package com.bigmoe.onedge.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffUtilTest {
    @Test
    fun lcsKeepsSharedLinesAndReportsAddsDeletes() {
        val d = DiffUtil.diffLines(listOf("a", "b", "c"), listOf("a", "x", "c", "d"))
        assertEquals(DiffUtil.Op.KEEP, d[0].op)
        assertEquals(DiffUtil.Op.DEL, d[1].op)
        assertEquals(DiffUtil.Op.ADD, d[2].op)
        assertEquals(DiffUtil.Stats(2, 1), DiffUtil.stats(d))
    }

    @Test
    fun unifiedDiffIsEmptyForEqualText() {
        assertEquals("", DiffUtil.unified("a\nb", "a\nb"))
    }

    @Test
    fun oversizedMatrixFallsBackToReplacement() {
        val old = List(2200) { "old$it" }
        val new = List(2200) { "new$it" }
        val d = DiffUtil.diffLines(old, new)
        assertTrue(d.all { it.op == DiffUtil.Op.DEL || it.op == DiffUtil.Op.ADD })
    }
}
