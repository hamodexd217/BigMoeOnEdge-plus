package com.bigmoe.onedge.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bigmoe.onedge.ui.chat.ChatViewModel
import com.bigmoe.onedge.ui.files.FileViewModel
import com.bigmoe.onedge.ui.history.HistoryViewModel
import com.bigmoe.onedge.ui.models.ModelViewModel
import com.bigmoe.onedge.ui.settings.SettingsViewModel

class AppViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(
                repository = container.chatRepository,
                settingsDataStore = container.settingsDataStore,
                agentController = container.agentController,
                engineController = container.engineController,
                toolManager = container.toolManager,
                artifactRepository = container.artifactRepository,
                attachmentManager = container.attachmentManager,
                workspace = container.workspace,
                composerStore = container.chatComposerDataStore
            ) as T
            modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
                HistoryViewModel(repository = container.chatRepository, composerStore = container.chatComposerDataStore) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(
                dataStore = container.settingsDataStore,
                engineController = container.engineController
            ) as T
            modelClass.isAssignableFrom(ModelViewModel::class.java) -> ModelViewModel(
                modelManager = container.modelManager,
                engineController = container.engineController,
                settingsDataStore = container.settingsDataStore,
                totalRamBytes = { container.totalRamBytes() }
            ) as T
            modelClass.isAssignableFrom(FileViewModel::class.java) -> FileViewModel(
                workspace = container.workspace,
                artifactRepository = container.artifactRepository
            ) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
