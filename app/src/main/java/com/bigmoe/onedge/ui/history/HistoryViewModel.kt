package com.bigmoe.onedge.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigmoe.onedge.data.repository.ChatRepository
import com.bigmoe.onedge.data.repository.ChatSessionDomainModel
import com.bigmoe.onedge.data.repository.ConversationExportDocument
import com.bigmoe.onedge.data.repository.ConversationExportFormat
import com.bigmoe.onedge.data.repository.ConversationExportFormatter
import com.bigmoe.onedge.data.local.datastore.ChatComposerDataStore
import com.bigmoe.onedge.core.GenerationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(
    private val repository: ChatRepository,
    private val composerStore: ChatComposerDataStore,
    generationManager: GenerationManager
) : ViewModel() {

    val generationState = generationManager.state

    val sessionsState: StateFlow<List<ChatSessionDomainModel>> = repository.observeAllSessions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            composerStore.clearSession(sessionId)
        }
    }

    fun renameSession(sessionId: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { repository.updateSessionTitle(sessionId, clean.take(80)) }
    }

    /** Builds an export from the saved conversation snapshot; no database entities or attachment paths are serialized. */
    suspend fun buildExport(
        sessionId: String,
        format: ConversationExportFormat,
        includeThinking: Boolean
    ): ConversationExportDocument? {
        val snapshot = withContext(Dispatchers.IO) {
            val session = repository.getSession(sessionId) ?: return@withContext null
            val messages = repository.getMessages(sessionId)
            val attachments = repository.observeAttachments(sessionId).first()
            Triple(session, messages, attachments)
        } ?: return null
        return withContext(Dispatchers.Default) {
            ConversationExportFormatter.create(snapshot.first, snapshot.second, snapshot.third, format, includeThinking)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllSessions()
            composerStore.clearAll()
        }
    }
}
