package com.bigmoe.onedge.ui.models

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigmoe.onedge.core.EngineController
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.LoadAdvice
import com.bigmoe.onedge.core.ModelFile
import com.bigmoe.onedge.core.ModelManager
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.EngineSettingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ImportProgress(val name: String, val copiedBytes: Long, val totalBytes: Long)

/** A load the person still has to confirm (shows [advice] first). */
data class PendingLoad(
    val path: String,
    val advice: LoadAdvice,
    val projectors: List<ModelFile> = emptyList(),
    val selectedProjector: String? = null
)

data class ModelsUiState(
    val models: List<ModelFile> = emptyList(),
    val importProgress: ImportProgress? = null,
    val pendingLoad: PendingLoad? = null,
    val message: String? = null,
    val modelsDir: String = ""
)

class ModelViewModel(
    private val modelManager: ModelManager,
    private val engineController: EngineController,
    private val settingsDataStore: EngineSettingsDataStore,
    private val totalRamBytes: () -> Long
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelsUiState(modelsDir = modelManager.directoryPath()))
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    val engineState: StateFlow<EngineState> = engineController.state

    val settings: StateFlow<EngineSettings> = settingsDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EngineSettings())

    private var importJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val list = modelManager.listModels()
            _uiState.update { it.copy(models = list) }
        }
    }

    fun importModel(resolver: ContentResolver, uri: Uri) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _uiState.update { it.copy(message = null, importProgress = ImportProgress("…", 0, 0)) }
            try {
                val imported = modelManager.importModel(resolver, uri) { copied, total ->
                    _uiState.update { s -> s.copy(importProgress = ImportProgress(s.importProgress?.name ?: "…", copied, total)) }
                }
                _uiState.update { it.copy(message = "Imported ${imported.name}") }
                refresh()
            } catch (e: CancellationException) {
                _uiState.update { it.copy(message = "Import cancelled") }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Import failed") }
            } finally {
                _uiState.update { it.copy(importProgress = null) }
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    /** Step 1: run the pre-flight checks and ask for confirmation. */
    fun requestLoad(path: String) {
        if (ModelManager.isProjectorName(path)) {
            _uiState.update { it.copy(message = "A vision projector is loaded together with its text model; choose the text model to load it.") }
            return
        }
        viewModelScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            val advice = ModelManager.adviseLoad(File(path), totalRamBytes(), s.toLoadConfig())
            val projectors = _uiState.value.models.filter { ModelManager.isProjectorName(it.name) }
            // By name, or the projector this very model was loaded with last time. Never a projector that was
            // used with a different model: a mismatched mmproj makes the whole load fail.
            val selected = ModelManager.suggestProjector(ModelFile(path, File(path).name, File(path).length()), projectors)?.path
                ?: s.lastMmprojPath.takeIf { candidate -> s.lastModelPath == path && projectors.any { it.path == candidate } }
            _uiState.update { it.copy(pendingLoad = PendingLoad(path, advice, projectors, selected), message = null) }
        }
    }

    fun dismissPendingLoad() {
        _uiState.update { it.copy(pendingLoad = null) }
    }

    fun selectProjector(path: String?) {
        _uiState.update { state -> state.copy(pendingLoad = state.pendingLoad?.copy(selectedProjector = path)) }
    }

    /** Step 2: load. The engine state (Loading / Ready / Failed) is observed through [engineState]. */
    fun confirmLoad() {
        val pending = _uiState.value.pendingLoad ?: return
        if (!pending.advice.canLoad) {
            dismissPendingLoad()
            return
        }
        _uiState.update { it.copy(pendingLoad = null) }
        loadNow(pending.path, pending.selectedProjector)
    }

    /** Reload the current model with the current settings (used after load-time settings changed). */
    fun reloadCurrent() {
        val ready = engineState.value as? EngineState.Ready ?: return
        loadNow(ready.info.path, ready.info.mmprojPath)
    }

    private fun loadNow(path: String, mmprojPath: String?) {
        viewModelScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            val error = engineController.loadModel(path, s.toLoadConfig(), mmprojPath)
            if (error == null) {
                settingsDataStore.updateLastModelPath(path)
                settingsDataStore.updateLastMmprojPath(mmprojPath.orEmpty())
            } else if (mmprojPath != null) {
                // The projector may be the problem (wrong model, damaged file): try again as a text-only model,
                // which is what the plain engine does, and say so.
                val textOnly = engineController.loadModel(path, s.toLoadConfig(), null)
                if (textOnly == null) {
                    settingsDataStore.updateLastModelPath(path)
                    settingsDataStore.updateLastMmprojPath("")
                    _uiState.update { it.copy(message = "Loaded WITHOUT vision: the projector ${File(mmprojPath).name} failed ($error).") }
                } else {
                    _uiState.update { it.copy(message = "$textOnly (with the projector: $error)") }
                }
            } else {
                _uiState.update { it.copy(message = error) }
            }
        }
    }

    fun unload() {
        viewModelScope.launch { engineController.unload() }
    }

    fun delete(path: String) {
        viewModelScope.launch {
            val loaded = (engineController.state.value as? EngineState.Ready)?.info?.path
            if (loaded == path) engineController.unload()
            val ok = modelManager.delete(path)
            _uiState.update { it.copy(message = if (ok) "Deleted" else "Could not delete the file") }
            refresh()
        }
    }

    fun setAutoLoad(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.updateAutoLoadLastModel(enabled) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
