package com.bigmoe.onedge.ui.chat

import com.bigmoe.onedge.data.repository.ChatSessionDomainModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiStateTest {
    private fun session(id: String) = ChatSessionDomainModel(id = id, title = "t", createdAt = 0L, updatedAt = 0L)

    @Test
    fun anotherChatStreamingDoesNotShowALiveBubbleHere() {
        val state = ChatUiState(currentSession = session("B"), isGenerating = true, generatingSessionId = "A")
        assertTrue(state.isGenerating)          // the composer stays locked: one shared generation slot
        assertFalse(state.isGeneratingHere)     // but chat B has nothing to stream
    }

    @Test
    fun theOpenChatStreamingShowsTheLiveBubble() {
        val state = ChatUiState(currentSession = session("A"), isGenerating = true, generatingSessionId = "A")
        assertTrue(state.isGeneratingHere)
    }

    @Test
    fun aTurnBeingPreparedBelongsToTheChatItWasSentFrom() {
        val preparing = ChatUiState(currentSession = session("A"), isGenerating = true, isPreparingMessage = true, preparingSessionId = "A")
        assertTrue(preparing.isGeneratingHere)
        assertFalse(preparing.copy(currentSession = session("B")).isGeneratingHere)
        // a new chat has no id yet
        assertTrue(ChatUiState(currentSession = null, isGenerating = true, isPreparingMessage = true).isGeneratingHere)
    }

    @Test
    fun aNewChatsFirstTurnStaysVisibleUntilItsRowIsObserved() {
        val state = ChatUiState(currentSession = null, isGenerating = true, isPreparingMessage = true, generatingSessionId = "NEW")
        assertTrue(state.isGeneratingHere)
    }

    @Test
    fun idleShowsNothing() {
        assertFalse(ChatUiState(currentSession = session("A")).isGeneratingHere)
    }
}
