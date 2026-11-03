package com.bigmoe.onedge.core

import org.junit.Assert.*
import org.junit.Test

class InferenceTelemetryTest {
    private fun sample(phase: Long = 2, generated: Long = 10, us: Long = 1_000_000, id: Long = 1) =
        InferenceTelemetry.fromWire(longArrayOf(phase, 100, 500_000, generated, us, 180, 1000, 20, 15, id))!!
    @Test fun promptUsesEvaluationNotResponseTime() {
        assertEquals(200.0, sample().promptTokensPerSecond!!, 0.0001)
        assertEquals(10.0, sample().averageTokensPerSecond!!, 0.0001)
    }
    @Test fun actualContextIncludesPromptAndHistory() {
        assertEquals(820L, sample().remainingContext)
        assertEquals(18.0, sample().contextPercent!!, 0.0001)
    }
    @Test fun unavailableAndShortSamplesAreNotZeroMetrics() {
        assertNull(InferenceTelemetry.fromWire(longArrayOf()))
        assertNull(sample(us = 10).averageTokensPerSecond)
        val missing = InferenceTelemetry.fromWire(longArrayOf(0,0,0,0,0,-1,100,-1,-1,0))!!
        assertNull(missing.contextUsed)
        assertNull(missing.promptTokensPerSecond)
        assertNull(missing.draftAcceptancePercent)
    }
    @Test fun recentRateAndNewRequests() {
        val rates = RecentTokenRate()
        assertNull(rates.sample(sample()))
        assertEquals(20.0, rates.sample(sample(generated = 20, us = 1_500_000))!!, 0.0001)
        assertNull(rates.sample(sample(generated = 1, us = 500_000, id = 2)))
        rates.reset()
        assertNull(rates.sample(sample()))
    }
    @Test fun cancellationAndInvalidCounters() {
        assertEquals(InferencePhase.STOPPED, sample(phase = 4).phase)
        assertNull(InferenceTelemetry.fromWire(longArrayOf(2,-1,0,0,0,0,100,-1,-1,1)))
        assertEquals(75.0, sample().draftAcceptancePercent!!, 0.0001)
    }
}
