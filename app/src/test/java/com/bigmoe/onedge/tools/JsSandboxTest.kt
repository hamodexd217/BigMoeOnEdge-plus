package com.bigmoe.onedge.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSandboxTest {
    @Test
    fun formatsLogsAndValues() {
        assertEquals("hello\n=> 42", JsSandbox.format("{\"logs\":[\"hello\"],\"value\":\"42\",\"error\":null}", false))
        assertTrue(JsSandbox.format(null, true).contains("did not finish"))
    }

    @Test
    fun wrapperQuotesArbitraryCode() {
        val wrapped = JsSandbox.wrap("console.log(\"a\\\"b\")")
        assertTrue(wrapped.contains("eval("))
        assertTrue(wrapped.contains("console"))
    }
}
