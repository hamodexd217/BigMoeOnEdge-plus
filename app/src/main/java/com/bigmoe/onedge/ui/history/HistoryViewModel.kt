package com.bigmoe.onedge.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigmoe.onedge.data.repository.ChatRepository
import com.bigmoe.onedge.data.repository.ChatSessionDomainModel
import com.bigmoe.onedge.data.local.datastore.ChatComposerDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val repository: ChatRepository,
    private val composerStore: ChatComposerDataStore
) : ViewModel() {

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

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllSessions()
            composerStore.clearAll()
        }
    }
}
