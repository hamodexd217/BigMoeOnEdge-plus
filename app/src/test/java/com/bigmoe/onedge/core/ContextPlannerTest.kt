package com.bigmoe.onedge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPlannerTest {

    private fun u(t: String) = ChatTurn(ChatTurn.USER, t)
    private fun a(t: String) = ChatTurn(ChatTurn.ASSISTANT, t)

    @Test
    fun stateKeyChangesWithChatLastMessageAndSystemPrompt() {
        val k = ContextPlanner.stateKey("s1", "m1", "sys")
        assertEquals(k, ContextPlanner.stateKey("s1", "m1", "sys"))
        assertNotEquals(k, ContextPlanner.stateKey("s2", "m1", "sys"))
        assertNotEquals(k, ContextPlanner.stateKey("s1", "m2", "sys"))
        assertNotEquals(k, ContextPlanner.stateKey("s1", "m1", "sys2"))
        assertNotEquals(k, ContextPlanner.stateKey("s1", null, "sys"))
    }

    @Test
    fun normalizeDropsEmptyMergesSameRoleAndStartsWithUser() {
        val out = ContextPlanner.normalize(listOf(a("stray"), u("q1"), u("q1b"), a(" "), a("ans"), a("more")))
        assertEquals(listOf(u("q1\n\nq1b"), a("ans\n\nmore")), out)
    }

    @Test
    fun prepareFoldsTrailingUnansweredUserTurnIntoPrompt() {
        val p = ContextPlanner.prepare(listOf(u("q1"), a("a1"), u("q2 (failed)")), "q3", 10_000)
        assertEquals(listOf(u("q1"), a("a1")), p.history)
        assertEquals("q2 (failed)\n\nq3", p.prompt)
        assertFalse(p.trimmed)
    }

    @Test
    fun prepareTrimsOldestPairsAndKeepsAlternation() {
        val big = "x".repeat(3000)
        val hist = listOf(u(big), a(big), u("recent q"), a("recent a"))
        val p = ContextPlanner.prepare(hist, "next", 200)
        assertTrue(p.trimmed)
        assertEquals(listOf(u("recent q"), a("recent a")), p.history)
    }

    @Test
    fun prepareWithZeroBudgetDropsAllHistory() {
        val p = ContextPlanner.prepare(listOf(u("q"), a("a")), "next", 0)
        assertTrue(p.history.isEmpty())
        assertTrue(p.trimmed)
    }

    @Test
    fun historyBudgetNeverNegativeAndReservesAnswerSpace() {
        assertEquals(0, ContextPlanner.historyBudget(4096, 5000, 10, 100))
        val b = ContextPlanner.historyBudget(4096, 100, 50, 512)
        assertTrue(b in 1..(4096 * 7 / 10))
        // unlimited answer reserves a quarter of the context
        val unlimited = ContextPlanner.historyBudget(4096, 100, 50, -1)
        assertTrue(unlimited <= 4096 - 4096 / 4 - 100 - 50)
    }

    @Test
    fun effectiveMaxTokens() {
        assertEquals(0, ContextPlanner.effectiveMaxTokens(-1, 4096))
        assertEquals(0, ContextPlanner.effectiveMaxTokens(0, 4096))
        assertEquals(512, ContextPlanner.effectiveMaxTokens(512, 4096))
        assertEquals(2048, ContextPlanner.effectiveMaxTokens(100_000, 4096))
    }

    @Test
    fun estimateTokensIsMonotonicAndHandlesArabicAndEmoji() {
        assertEquals(0, ContextPlanner.estimateTokens(""))
        assertTrue(ContextPlanner.estimateTokens("hello world") < ContextPlanner.estimateTokens("hello world hello world"))
        assertTrue(ContextPlanner.estimateTokens("مرحبا بالعالم") > 4)
        assertTrue(ContextPlanner.estimateTokens("😀😀😀") > 4)
    }

    @Test
    fun overflowDetection() {
        assertTrue(ContextPlanner.isContextOverflow("prompt + n_predict exceeds the session n_ctx (4096); open the session with a larger n_ctx"))
        assertFalse(ContextPlanner.isContextOverflow("No model is loaded"))
    }
}
