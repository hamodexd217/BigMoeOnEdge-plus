package com.bigmoe.onedge.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentContextTest {
    @Test
    fun budgetHasReasonableBounds() {
        assertTrue(AttachmentContext.budgetTokens(1024) >= 350)
        assertTrue(AttachmentContext.budgetTokens(1_000_000) <= 7000)
    }

    @Test
    fun includesNamesAndMediaMarkers() {
        val out = AttachmentContext.build(
            listOf(AttachmentContext.TextFile("a.md", "hello")),
            listOf(AttachmentContext.Media("x.png", "image", "mime=\"image/png\"")),
            "hello", 4096
        )
        assertTrue(out.contains("<attached_file name=\"a.md\""))
        assertTrue(out.contains("<attached_image name=\"x.png\""))
    }
}
