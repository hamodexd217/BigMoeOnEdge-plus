package com.bigmoe.onedge.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextSearchTest {
    @Test
    fun searchesTextAndReturnsContext() {
        val root = File.createTempFile("bmoe-search", "").apply { delete(); mkdirs(); deleteOnExit() }
        try {
            val w = Workspace(root)
            w.writeText("a.kt", "one\nneedle here\nthree\n", overwrite = true)
            val r = TextSearch.search(w, "needle", contextLines = 1)
            assertEquals(1, r.matches.size)
            assertEquals(2, r.matches.single().line)
            assertEquals(listOf("one"), r.matches.single().before)
            assertEquals(listOf("three"), r.matches.single().after)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun invalidRegexIsReported() {
        val root = File.createTempFile("bmoe-search", "").apply { delete(); mkdirs(); deleteOnExit() }
        try {
            val w = Workspace(root)
            try { TextSearch.search(w, "[", regex = true); throw AssertionError("bad regex accepted") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("invalid regular expression")) }
        } finally { root.deleteRecursively() }
    }
}
