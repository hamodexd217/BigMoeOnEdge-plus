package com.bigmoe.onedge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigmoe.onedge.core.DenseWeights
import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.core.EngineController
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.EngineSettingsDataStore
import com.bigmoe.onedge.data.local.datastore.WebSearchMode
import com.bigmoe.onedge.ui.theme.PalettePresets
import com.bigmoe.onedge.ui.theme.PaletteSpec
import com.bigmoe.onedge.ui.theme.PaletteStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val dataStore: EngineSettingsDataStore,
    private val engineController: EngineController
) : ViewModel() {

    /** null until the first DataStore read finished, so the screen never seeds text fields with defaults. */
    val settingsState: StateFlow<EngineSettings?> = dataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val engineState: StateFlow<EngineState> = engineController.state
    val npuPrefillAvailable: Boolean get() = engineController.capabilities.npuPrefill

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    // ---- per-request (apply to the next message) ----
    fun updateSystemPrompt(v: String) = launch { dataStore.updateSystemPrompt(v) }
    fun updateMaxTokens(v: Int) = launch { dataStore.updateMaxTokens(v) }
    fun updateThinkingEnabled(v: Boolean) = launch { dataStore.updateThinkingEnabled(v) }
    fun updateWebSearchEnabled(v: Boolean) = launch { dataStore.updateWebSearchEnabled(v) }
    fun updateWebSearchMode(v: WebSearchMode) = launch { dataStore.updateWebSearchMode(v) }
    fun updateBraveApiKey(v: String) = launch { dataStore.updateBraveApiKey(v.trim()) }
    fun updateSearxngUrl(v: String) = launch { dataStore.updateSearxngUrl(v.trim()) }
    fun updateToolsFilesEnabled(v: Boolean) = launch { dataStore.updateToolsFilesEnabled(v) }
    fun updateToolsCodeEnabled(v: Boolean) = launch { dataStore.updateToolsCodeEnabled(v) }
    fun updateToolsUtilitiesEnabled(v: Boolean) = launch { dataStore.updateToolsUtilitiesEnabled(v) }
    fun updateImageMaxDim(v: Int) = launch { dataStore.updateImageMaxDim(v) }
    fun updateVideoFrames(v: Int) = launch { dataStore.updateVideoFrames(v) }

    // ---- engine (apply after the model is reloaded) ----
    fun updateContextLength(v: Int) = launch { dataStore.updateContextLength(v) }
    fun updateThreadCount(v: Int) = launch { dataStore.updateThreadCount(v) }
    fun updateTemperature(v: Float) = launch { dataStore.updateTemperature(v) }
    fun updateTopP(v: Float) = launch { dataStore.updateTopP(v) }
    fun updateTopK(v: Int) = launch { dataStore.updateTopK(v) }
    fun updateCacheMb(v: Int) = launch { dataStore.updateCacheMb(v) }
    fun updateCacheCeilMb(v: Int) = launch { dataStore.updateCacheCeilMb(v) }
    fun updateCacheFloorMb(v: Int) = launch { dataStore.updateCacheFloorMb(v) }
    fun updateIoThreads(v: Int) = launch { dataStore.updateIoThreads(v) }
    fun updateODirect(v: Boolean) = launch { dataStore.updateODirect(v) }
    fun updateOverlap(v: Boolean) = launch { dataStore.updateOverlap(v) }
    fun updateDenseWeights(v: DenseWeights) = launch { dataStore.updateDenseWeights(v) }
    fun updateNExpertUsed(v: Int) = launch { dataStore.updateNExpertUsed(v) }
    fun updateDropColdPct(v: Int) = launch { dataStore.updateDropColdPct(v) }
    fun updateSubstitutePct(v: Int) = launch { dataStore.updateSubstitutePct(v) }
    fun updatePrefetchLayers(v: Int) = launch { dataStore.updatePrefetchLayers(v) }
    fun updatePredictPrefetch(v: Boolean) = launch { dataStore.updatePredictPrefetch(v) }
    fun updatePredictSpecMax(v: Int) = launch { dataStore.updatePredictSpecMax(v) }
    fun updateRouteAhead(v: Int) = launch { dataStore.updateRouteAhead(v) }
    fun updateRowStream(v: Boolean) = launch { dataStore.updateRowStream(v) }
    fun updateReleaseMmap(v: Boolean) = launch { dataStore.updateReleaseMmap(v) }
    fun updateSpec(v: SpecSource) = launch { dataStore.updateSpec(v) }
    fun updateDraftMax(v: Int) = launch { dataStore.updateDraftMax(v) }
    fun updateMtpPMinPct(v: Int) = launch { dataStore.updateMtpPMinPct(v) }
    fun updateMetricsCsv(v: Boolean) = launch { dataStore.updateMetricsCsv(v) }
    fun updateNpuPrefill(v: Boolean) = launch { dataStore.updateNpuPrefill(v) }
    fun updateNpuLoaders(v: Int) = launch { dataStore.updateNpuLoaders(v) }

    // ---- appearance ----
    fun selectTheme(id: String) = launch { dataStore.updateThemeId(id) }

    /** Saves (insert or replace) a custom palette and selects it. */
    fun saveCustomPalette(palette: PaletteSpec) = launch {
        val current = PaletteStore.parse(dataStore.settingsFlow.first().customPalettesJson)
        dataStore.updateCustomPalettesJson(PaletteStore.serialize(PaletteStore.upsert(current, palette)))
        dataStore.updateThemeId(palette.id)
    }

    fun deleteCustomPalette(id: String) = launch {
        val s = dataStore.settingsFlow.first()
        val remaining = PaletteStore.parse(s.customPalettesJson).filter { it.id != id }
        dataStore.updateCustomPalettesJson(PaletteStore.serialize(remaining))
        if (s.themeId == id) dataStore.updateThemeId(PalettePresets.DEFAULT_LIGHT_ID)
    }

    /** Reloads the current model so the engine settings above take effect. */
    fun reloadModel() = launch {
        val ready = engineController.state.value as? EngineState.Ready ?: return@launch
        val s = dataStore.settingsFlow.first()
        engineController.loadModel(ready.info.path, s.toLoadConfig(), ready.info.mmprojPath)
    }
}
