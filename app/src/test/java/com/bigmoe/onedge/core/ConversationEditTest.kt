package com.bigmoe.onedge.core

import com.bigmoe.onedge.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEditTest {
    private fun m(id: String, role: MessageRole, text: String = id) = HistoryMessage(id, role, text)

    private val chat = listOf(
        m("u1", MessageRole.USER, "first"),
        m("a1", MessageRole.ASSISTANT),
        m("u2", MessageRole.USER, "second"),
        m("c2", MessageRole.CONTEXT),
        m("t2", MessageRole.TOOL),
        m("a2", MessageRole.ASSISTANT),
        m("u3", MessageRole.USER, "third"),
        m("a3", MessageRole.ASSISTANT)
    )

    @Test
    fun editingAMiddleMessageKeepsEarlierTurnsAndDropsEverythingAfterIt() {
        val plan = ConversationEdit.plan(chat, "u2")!!
        assertEquals(listOf("u1", "a1"), plan.history.map { it.id })
        assertEquals(listOf("c2", "t2", "a2", "u3", "a3"), plan.removeIds)
        assertEquals("second", plan.previousText)
        assertFalse(plan.isFirstUserMessage)
    }

    @Test
    fun editingTheFirstMessageStartsFromAnEmptyHistory() {
        val plan = ConversationEdit.plan(chat, "u1")!!
        assertTrue(plan.history.isEmpty())
        assertEquals(chat.drop(1).map { it.id }, plan.removeIds)
        assertTrue(plan.isFirstUserMessage)
    }

    @Test
    fun editingTheLastMessageRemovesOnlyItsAnswer() {
        val plan = ConversationEdit.plan(chat, "u3")!!
        assertEquals(listOf("a3"), plan.removeIds)
        assertEquals(6, plan.history.size)
    }

    @Test
    fun onlyUserMessagesAreEditableAndUnknownIdsAreRefused() {
        assertNull(ConversationEdit.plan(chat, "a1"))
        assertNull(ConversationEdit.plan(chat, "t2"))
        assertNull(ConversationEdit.plan(chat, "missing"))
        assertNull(ConversationEdit.plan(emptyList(), "u1"))
        assertNotNull(ConversationEdit.plan(chat, "u1"))
    }
}
