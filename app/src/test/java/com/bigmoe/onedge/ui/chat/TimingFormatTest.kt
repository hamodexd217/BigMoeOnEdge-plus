package com.bigmoe.onedge.ui.chat

import com.bigmoe.onedge.core.GenerationPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingFormatTest {
    @Test
    fun durationsReadNaturally() {
        assertEquals("850 ms", formatDuration(850))
        assertEquals("3.2 s", formatDuration(3200))
        assertEquals("1m 05s", formatDuration(65_000))
        assertEquals("1h 02m 03s", formatDuration(3_723_000))
    }

    @Test
    fun prefillSpeedNeedsBothNumbers() {
        assertEquals(42.0, prefillSpeed(420, 10_000)!!, 0.001)
        assertNull(prefillSpeed(null, 1000))
        assertNull(prefillSpeed(100, 0))
        assertNull(prefillSpeed(0, 1000))
    }

    @Test
    fun savedStatsShowPrefillSpeedAndThinkingTimeWhenKnown() {
        assertEquals(
            "6.4 t/s • prefill 42 tok/s (420 tok, 10.0 s) • thought 18.0 s • 124 tok",
            formatStats(6.4, 10_000, 124, null, prefillTokens = 420, thinkingMs = 18_000)
        )
        // older messages have no prompt size: unchanged wording
        assertEquals("6.4 t/s • prefill 850 ms", formatStats(6.4, 850, null, null))
    }

    @Test
    fun liveStatusFollowsThePhase() {
        val base = LiveTiming(phaseStartedAt = 1_000L)
        assertEquals("Reading prompt 2.0 s", formatLiveStatus(base, 3_000L))
        assertEquals("Thinking 8.0 s", formatLiveStatus(base.copy(phase = GenerationPhase.THINKING), 9_000L))
        assertEquals(
            "Using web_search, read_file 1.5 s",
            formatLiveStatus(base.copy(phase = GenerationPhase.TOOL, toolNames = listOf("web_search", "read_file")), 2_500L)
        )
        assertEquals(
            "Answering • Thought for 18.0 s • Tools took 3.2 s • Prefill 42 tok/s (420 tok, 10.0 s) • Generation 6.7 tok/s",
            formatLiveStatus(
                base.copy(
                    phase = GenerationPhase.ANSWERING, thinkingMs = 18_000, toolMs = 3_200,
                    prefillTokens = 420, prefillMs = 10_000, decodeTokensPerSecond = 6.7
                ),
                5_000L
            )
        )
    }

    @Test
    fun liveStatusNeverShowsANegativeClock() {
        assertTrue(formatLiveStatus(LiveTiming(phaseStartedAt = 9_000L), 1_000L).startsWith("Reading prompt 0 ms"))
        assertTrue(formatLiveStatus(LiveTiming(), 5_000L).startsWith("Reading prompt 0 ms")) // no start time yet
    }
}
