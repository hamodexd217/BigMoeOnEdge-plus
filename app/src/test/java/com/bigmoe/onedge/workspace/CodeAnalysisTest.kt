package com.bigmoe.onedge.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeAnalysisTest {
    @Test
    fun findsDefinitionsAndTodos() {
        val text = "class Demo\n\n// TODO: fix\nfun hello() = 1\n"
        val out = CodeAnalysis.analyze("Demo.kt", text)
        assertTrue(out.contains("Classes (1): Demo@1"))
        assertTrue(out.contains("Functions (1): hello@4"))
        assertTrue(out.contains("TODO/FIXME (1)"))
    }

    @Test
    fun braceBalanceFindsMismatches() {
        assertEquals(null, CodeAnalysis.braceBalance("fun x() { return mapOf(\"a\" to 1) }"))
        assertTrue(CodeAnalysis.braceBalance("fun x() {\n").orEmpty().contains("unclosed"))
        assertTrue(CodeAnalysis.braceBalance("fun x() ]").orEmpty().contains("unmatched"))
    }
}
