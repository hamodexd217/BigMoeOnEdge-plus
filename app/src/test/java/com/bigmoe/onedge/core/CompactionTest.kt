package com.bigmoe.onedge.core

import com.bigmoe.onedge.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionTest {

    private fun m(id: String, role: MessageRole, content: String = id) = HistoryMessage(id, role, content)

    @Test
    fun summaryRoundTripsThroughItsStoredForm() {
        val stored = Compaction.encode("abc-1", "  hello\nworld ")
        assertEquals(Compaction.Stored("abc-1", "hello\nworld"), Compaction.decode(stored))
        assertNull(Compaction.decode("an ordinary message"))
        assertNull(Compaction.decode("bmoe-summary upto=\ntext"))
    }

    @Test
    fun resolveSendsTheSummaryAndOnlyTheMessagesAfterWhatItCovers() {
        val stored = listOf(
            m("u1", MessageRole.USER), m("a1", MessageRole.ASSISTANT),
            m("u2", MessageRole.USER), m("a2", MessageRole.ASSISTANT),
            m("s1", MessageRole.SUMMARY, Compaction.encode("a1", "S1 text")),
            m("u3", MessageRole.USER)
        )
        val r = Compaction.resolve(stored)
        assertEquals("S1 text", r.summary)
        assertEquals(listOf("u2", "a2", "u3"), r.history.map { it.id })
    }

    @Test
    fun withoutASummaryEverythingIsSent() {
        val r = Compaction.resolve(listOf(m("u1", MessageRole.USER), m("a1", MessageRole.ASSISTANT)))
        assertEquals("", r.summary)
        assertEquals(2, r.history.size)
    }

    @Test
    fun aSummaryWhoseCoveredMessageIsGoneFallsBackToTheOlderOne() {
        val stored = listOf(
            m("u1", MessageRole.USER), m("a1", MessageRole.ASSISTANT),
            m("s1", MessageRole.SUMMARY, Compaction.encode("u1", "older")),
            m("u2", MessageRole.USER),
            m("s2", MessageRole.SUMMARY, Compaction.encode("gone", "broken")),
            m("a2", MessageRole.ASSISTANT)
        )
        val r = Compaction.resolve(stored)
        assertEquals("older", r.summary)
        assertEquals(listOf("a1", "u2", "a2"), r.history.map { it.id })
    }

    @Test
    fun theNewestSummaryWinsAndOlderSummariesAreNeverSent() {
        val stored = listOf(
            m("u1", MessageRole.USER), m("a1", MessageRole.ASSISTANT),
            m("s1", MessageRole.SUMMARY, Compaction.encode("a1", "one")),
            m("u2", MessageRole.USER), m("a2", MessageRole.ASSISTANT),
            m("s2", MessageRole.SUMMARY, Compaction.encode("a2", "two")),
            m("u3", MessageRole.USER)
        )
        val r = Compaction.resolve(stored)
        assertEquals("two", r.summary)
        assertEquals(listOf("u3"), r.history.map { it.id })
    }

    @Test
    fun aSummaryNamingAMessageAfterItselfIsIgnored() {
        val stored = listOf(m("s1", MessageRole.SUMMARY, Compaction.encode("u1", "x")), m("u1", MessageRole.USER))
        assertEquals("", Compaction.resolve(stored).summary)
    }

    @Test
    fun compactionStartsOnlyAbovePercentOfContextAndOnlyInABigEnoughContext() {
        assertTrue(Compaction.needed(2500, 4096, 60))
        assertFalse(Compaction.needed(2400, 4096, 60))
        assertFalse(Compaction.needed(1000, 1024, 40))
        // the share is clamped to 40..70
        assertEquals(3000 > 4096 * 70 / 100, Compaction.needed(3000, 4096, 99))
    }

    private fun turn(role: String, chars: Int) = ChatTurn(role, "x".repeat(chars))

    @Test
    fun theCutLandsOnAUserMessageAndKeepsTheNewestTurnsThatFit() {
        val turns = (0 until 8).map { turn(if (it % 2 == 0) ChatTurn.USER else ChatTurn.ASSISTANT, 3000) }
        val roles = (0 until 8).map { if (it % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT }
        val cut = Compaction.splitIndex(turns, roles, 8192)
        assertEquals(6, cut)
        assertEquals(MessageRole.USER, roles[cut])
        assertEquals(0, Compaction.splitIndex(turns, emptyList(), 8192))
    }

    @Test
    fun aCutNeverSeparatesAUserMessageFromItsContextOrToolResult() {
        val roles = listOf(
            MessageRole.USER, MessageRole.CONTEXT, MessageRole.ASSISTANT, MessageRole.USER,
            MessageRole.TOOL, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT
        )
        val turns = roles.map { turn(if (it == MessageRole.ASSISTANT) ChatTurn.ASSISTANT else ChatTurn.USER, 3000) }
        val cut = Compaction.splitIndex(turns, roles, 8192)
        assertTrue(cut == roles.size || roles[cut] == MessageRole.USER)
    }

    @Test
    fun whenTheRunningTurnAloneIsTooBigAllStoredHistoryIsFolded() {
        val roles = (0 until 8).map { if (it % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT }
        val turns = roles.map { turn(if (it == MessageRole.USER) ChatTurn.USER else ChatTurn.ASSISTANT, 3000) } +
            listOf(turn(ChatTurn.USER, 30000), turn(ChatTurn.ASSISTANT, 30000))
        assertEquals(8, Compaction.splitIndex(turns, roles, 8192))
    }

    @Test
    fun tinyStretchesAreNotWorthASummary() {
        val turns = listOf(ChatTurn(ChatTurn.USER, "hi"), ChatTurn(ChatTurn.ASSISTANT, "hello"))
        assertFalse(Compaction.worthFolding(turns, 2))
        assertFalse(Compaction.worthFolding(turns, 0))
    }

    @Test
    fun cleanSummaryDropsReasoningAndRejectsUnusableAnswers() {
        assertEquals("The user wants a login page in Kotlin.", Compaction.cleanSummary("<think>plan</think>The user wants a login page in Kotlin.", 4096))
        assertNull(Compaction.cleanSummary("<think>still thinking about it and more of it", 4096))
        assertNull(Compaction.cleanSummary("ok", 4096))
        assertEquals("The user asked about gradle builds and CI.", Compaction.cleanSummary("Summary: The user asked about gradle builds and CI.", 4096))
        assertEquals(Compaction.summaryTokens(4096) * 5, Compaction.cleanSummary("a".repeat(100_000), 4096)!!.length)
    }

    @Test
    fun theSummarizerRequestMentionsAnEarlierSummaryOnlyWhenThereIsOne() {
        assertTrue(Compaction.summaryPrompt("PREV", 100).contains("<earlier_summary>\nPREV"))
        assertFalse(Compaction.summaryPrompt("", 100).contains("earlier_summary"))
    }
}
