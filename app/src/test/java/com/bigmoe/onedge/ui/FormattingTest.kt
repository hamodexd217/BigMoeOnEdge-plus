package com.bigmoe.onedge.ui

import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.loadConfigDiffers
import com.bigmoe.onedge.ui.chat.ChatViewModel
import com.bigmoe.onedge.ui.chat.formatStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattingTest {

    @Test
    fun statsUseRealNumbersAndSkipMissingOnes() {
        assertEquals("12.3 t/s • prefill 850 ms • 40 tok • cache 74%", formatStats(12.34, 850, 40, 74.2))
        assertEquals("5.0 t/s", formatStats(5.0, null, null, null))
        assertNull(formatStats(null, null, null, null))
        assertNull(formatStats(0.0, 0, 0, -1.0))
    }

    @Test
    fun titleFromPrompt() {
        assertEquals("Hello", ChatViewModel.titleFrom("  Hello\nsecond line"))
        assertEquals("New Chat", ChatViewModel.titleFrom("   "))
        val long = "a".repeat(100)
        assertEquals(41, ChatViewModel.titleFrom(long).length)
        assertTrue(ChatViewModel.titleFrom(long).endsWith("…"))
    }

    @Test
    fun reloadNeededOnlyForLoadTimeSettings() {
        val s = EngineSettings()
        val loaded = s.toLoadConfig()
        assertFalse(loadConfigDiffers(loaded, s))
        assertFalse("per-request settings never need a reload", loadConfigDiffers(loaded, s.copy(maxTokens = 99, systemPrompt = "x", thinkingEnabled = false)))
        assertTrue(loadConfigDiffers(loaded, s.copy(contextLength = 8192)))
        assertTrue(loadConfigDiffers(loaded, s.copy(dropColdPct = 0)))
        assertTrue(loadConfigDiffers(loaded, s.copy(overlap = false)))
        assertTrue(loadConfigDiffers(loaded, s.copy(temperature = 0.1f)))
        assertTrue(loadConfigDiffers(loaded, s.copy(cacheMb = 0)))
    }
}
