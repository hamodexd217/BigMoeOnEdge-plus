package com.bigmoe.onedge.attachments

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaProcessorTest {
    @Test
    fun fitSizePreservesAspectRatioAndNeverUpscales() {
        assertEquals(800 to 400, MediaProcessor.fitSize(800, 400, 1024))
        assertEquals(512 to 256, MediaProcessor.fitSize(1024, 512, 512))
    }

    @Test
    fun rgbConversionDropsAlphaAndFrameTimesStayInsideClip() {
        assertEquals(listOf(255.toByte(), 0, 0, 0, 255.toByte(), 0), MediaProcessor.rgbFromArgb(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt())).toList())
        val times = MediaProcessor.frameTimesUs(1000, 4)
        assertEquals(4, times.size)
        assertEquals(true, times.zipWithNext().all { it.first < it.second })
        assertEquals(125_000L, times.first())
        assertEquals(875_000L, times.last())
    }
}
