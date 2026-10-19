package com.bigmoe.onedge.ui

import com.bigmoe.onedge.ui.chat.ChatDraftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDraftStateTest {
    @Test
    fun `switching chats keeps each draft and sending clears only that chat`() {
        val state = ChatDraftState()
        state.setDraft("A", "draft A")
        state.setDraft("B", "draft B")

        assertEquals("draft B", state.switch("B"))
        assertEquals("draft A", state.draftFor("A"))
        assertEquals("draft B", state.draftFor("B"))

        state.clearDraft("A")

        assertEquals("", state.draftFor("A"))
        assertEquals("draft B", state.draftFor("B"))
        assertTrue(state.draftFor(ChatDraftState.NEW_CHAT_KEY).isEmpty())
    }
}
