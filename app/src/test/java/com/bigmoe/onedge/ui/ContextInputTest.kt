package com.bigmoe.onedge.ui

import com.bigmoe.onedge.ui.settings.ContextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextInputTest {
    @Test fun acceptsAnyPositiveNumber() {
        assertEquals(4096, ContextInput.parse("4096"))
        assertEquals(6000, ContextInput.parse("6000"))
        assertEquals(1, ContextInput.parse("1"))
        assertEquals(262_144, ContextInput.parse("262144"))
        assertEquals(999_999_999, ContextInput.parse("999999999"))
    }

    @Test fun rejectsEmptyZeroAndText() {
        assertNull(ContextInput.parse(""))
        assertNull(ContextInput.parse("0"))
        assertNull(ContextInput.parse("12a"))
    }

    @Test fun sanitizeKeepsDigitsOnlyAndFitsAnInt() {
        assertEquals("4096", ContextInput.sanitize("4 096"))
        assertEquals("123456789", ContextInput.sanitize("1234567890123"))
        assertEquals("", ContextInput.sanitize("abc"))
    }
}
