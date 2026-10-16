package com.bigmoe.onedge.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentLimitsTest {
    private val mb = 1024L * 1024

    @Test
    fun theLimitIs256MbAndTheWarningStartsAbove8Mb() {
        assertEquals(256 * mb, AttachmentLimits.MAX_FILE_BYTES)
        assertNull(AttachmentLimits.warningFor("a.txt", 8 * mb, false))
        assertNull(AttachmentLimits.warningFor("a.txt", 1000, false))
        assertNotNull(AttachmentLimits.warningFor("a.txt", 8 * mb + 1, false))
    }

    @Test
    fun warningNamesTheFileItsSizeAndTheSlowdown() {
        val w = AttachmentLimits.warningFor("big.log", 100 * mb, false)!!
        assertTrue(w.contains("big.log"))
        assertTrue(w.contains("100 MB"))
        assertTrue(w.contains("slower"))
        assertTrue(w.contains("first 8 MB"))
    }

    @Test
    fun archivesAreNotToldAboutPartialTextReading() {
        val w = AttachmentLimits.warningFor("data.zip", 50 * mb, true)!!
        assertTrue(w.contains("data.zip"))
        assertTrue(!w.contains("first 8 MB"))
    }

    @Test
    fun partlyReadTextEndsOnALineBreak() {
        val bytes = "line one\nline two\nline thr".toByteArray()
        assertEquals("line one\nline two\n", String(AttachmentLimits.cutAtLineEnd(bytes, bytes.size)))
        // a single huge line without any break is kept as it is
        val oneLine = "abcdef".toByteArray()
        assertEquals("abcdef", String(AttachmentLimits.cutAtLineEnd(oneLine, oneLine.size)))
        assertEquals("ab", String(AttachmentLimits.cutAtLineEnd(oneLine, 2)))
    }
}
