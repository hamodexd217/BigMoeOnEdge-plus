package com.bigmoe.onedge.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigmoe.onedge.core.AutoSave
import com.bigmoe.onedge.core.AutoSaveCodec
import com.bigmoe.onedge.core.ModelProfileIdentity
import com.bigmoe.onedge.core.AutoTier
import com.bigmoe.onedge.core.AutoTuner
import com.bigmoe.onedge.core.DeviceProfile
import com.bigmoe.onedge.core.DeviceScanner
import com.bigmoe.onedge.core.EngineController
import com.bigmoe.onedge.core.EngineEvent
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.GenerationRequest
import com.bigmoe.onedge.core.GgufInspector
import com.bigmoe.onedge.core.ModelFacts
import com.bigmoe.onedge.core.ModelFile
import com.bigmoe.onedge.core.ModelManager
import com.bigmoe.onedge.core.LoadAdvice
import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.core.applyTo
import com.bigmoe.onedge.data.local.AutoSaveStore
import com.bigmoe.onedge.data.local.datastore.EngineSettingsDataStore
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.loadConfigDiffers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class AutoUiState(
    val device: DeviceProfile? = null,
    val scanning: Boolean = false,
    val models: List<ModelFile> = emptyList(),
    val selectedPath: String? = null,
    val facts: ModelFacts? = null,
    /** Non-null while a measurement runs: what it is doing right now. */
    val busy: String? = null,
    val message: String? = null,
    /** True after a save was switched on while a model is loaded: it needs a reload to take effect. */
    val reloadNeeded: Boolean = false,
    /** Opt-in: allow NPU prefill in newly created saves (off by default: least proven path). */
    val useNpu: Boolean = false,
    /** Live persisted settings shown by the Custom Settings editor. */
    val engineSettings: EngineSettings = EngineSettings()
)

class AutoTuneViewModel(
    private val dataStore: EngineSettingsDataStore,
    private val engineController: EngineController,
    private val modelManager: ModelManager,
    private val store: AutoSaveStore,
    private val scanner: DeviceScanner,
    private val contentResolver: ContentResolver
) : ViewModel() {

    private val _ui = MutableStateFlow(AutoUiState())
    val ui: StateFlow<AutoUiState> = _ui.asStateFlow()
    val saves: StateFlow<List<AutoSave>> = store.saves
    val activeId: StateFlow<String?> = store.activeId
    val modelProfiles: StateFlow<Map<String, String>> = store.modelProfiles
    val autoApplyModelProfilesEnabled: StateFlow<Boolean> = store.autoApplyModelProfilesEnabled
    private val _currentModelPath = MutableStateFlow((engineController.state.value as? EngineState.Ready)?.info?.path)
    val currentModelPath: StateFlow<String?> = _currentModelPath.asStateFlow()

    private var measureJob: Job? = null

    init {
        viewModelScope.launch {
            dataStore.settingsFlow.collect { settings ->
                _ui.update { it.copy(engineSettings = settings) }
            }
        }
        viewModelScope.launch {
            store.load()
            migrateOldSaves()
            healInterruptedMeasurement()
            refreshModels()
            scan()
        }
        // The VM is created when the app starts, long before a model is added or loaded: follow the engine.
        viewModelScope.launch {
            engineController.state.collect { st ->
                _currentModelPath.value = (st as? EngineState.Ready)?.info?.path
                if (st is EngineState.Ready && _ui.value.busy == null) refreshModels()
            }
        }
    }

    /** Saves made by an older version may hold settings that crashed a device; strip them quietly. */
    private suspend fun migrateOldSaves() {
        store.saves.value.forEach { save ->
            val clean = AutoTuner.sanitize(save.values)
            if (clean != save.values) store.upsert(save.copy(values = clean, measuredTps = null, measuredAt = null))
        }
    }

    fun setUseNpu(on: Boolean) = _ui.update { it.copy(useNpu = on) }

    /** Manual controls used by the Custom Settings mode; persistence and reload-diff logic are shared. */
    private fun updateCustomSetting(change: suspend () -> Unit) {
        viewModelScope.launch {
            change()
            val saved = dataStore.settingsFlow.first()
            val loaded = (engineController.state.value as? EngineState.Ready)?.info
            _ui.update {
                it.copy(
                    engineSettings = saved,
                    reloadNeeded = loaded != null && loadConfigDiffers(loaded.config, saved),
                    message = null
                )
            }
        }
    }
    fun updateCustomContext(v: Int) = updateCustomSetting { dataStore.updateContextLength(v) }
    fun updateCustomThreads(v: Int) = updateCustomSetting { dataStore.updateThreadCount(v) }
    fun updateCustomBatchThreads(v: Int) = updateCustomSetting { dataStore.updateBatchThreadCount(v) }
    fun updateCustomBatchWidth(v: Int) = updateCustomSetting { dataStore.updateBatchWidth(v) }
    fun updateCustomCache(v: Int) = updateCustomSetting { dataStore.updateCacheMb(v) }
    fun updateCustomIoThreads(v: Int) = updateCustomSetting { dataStore.updateIoThreads(v) }
    fun updateCustomTemperature(v: Float) = updateCustomSetting { dataStore.updateTemperature(v) }
    fun updateCustomTopP(v: Float) = updateCustomSetting { dataStore.updateTopP(v) }
    fun updateCustomTopK(v: Int) = updateCustomSetting { dataStore.updateTopK(v) }
    fun updateCustomSeed(v: Int) = updateCustomSetting { dataStore.updateSeed(v) }

    /**
     * If the previous run died in the middle of a measurement (Android killed the app for memory, or a native
     * crash), lower that save's memory use so the same save can't crash the app again.
     */
    private suspend fun healInterruptedMeasurement() {
        val id = store.takeInterruptedMeasurement() ?: return
        val save = store.saves.value.firstOrNull { it.id == id } ?: return
        val lower = AutoTuner.safer(save.values)
        if (lower != null) {
            store.upsert(
                save.copy(
                    values = lower, measuredTps = null, measuredAt = null,
                    lastError = "The app was closed while measuring this save (almost certainly out of memory). " +
                        "Its memory use was lowered automatically; measure it again."
                )
            )
            say("“${save.name}” closed the app last time, so its memory use was lowered.")
        } else {
            store.upsert(
                save.copy(
                    measuredTps = null, measuredAt = null,
                    lastError = "The app was closed while measuring, even at the lowest settings. This model is too big for this phone to run safely."
                )
            )
            say("“${save.name}” is too big for this phone to run safely.")
        }
    }

    /** Manually lowers a save's memory use (smaller cache and context, no extras). */
    fun makeSafer(id: String) {
        val save = store.saves.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val lower = AutoTuner.safer(save.values)
            if (lower == null) {
                say("“${save.name}” is already at the lowest memory use.")
            } else {
                store.upsert(save.copy(values = lower, measuredTps = null, measuredAt = null, lastError = null))
                say("Lowered memory use of “${save.name}”. If it is in use, press Use again to apply it.")
            }
        }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }
    private fun say(text: String) = _ui.update { it.copy(message = text) }

    // ---------------------------------------------------------------- device + model

    fun scan() {
        if (_ui.value.scanning) return
        viewModelScope.launch {
            _ui.update { it.copy(scanning = true) }
            val npu = runCatching { engineController.capabilities.npuPrefill }.getOrDefault(false)
            val profile = scanner.scan(npuRuntime = npu)
            _ui.update { it.copy(device = profile, scanning = false) }
        }
    }

    private fun samePath(a: String, b: String): Boolean =
        runCatching { File(a).canonicalPath == File(b).canonicalPath }.getOrDefault(a == b)

    /** Re-reads the model list. Called when the screen opens and whenever a model finishes loading. */
    fun refreshModels() {
        viewModelScope.launch {
            val listed = modelManager.listModels()
            val loadedPath = engineController.loadedModel()?.path
            // A model that is loaded right now must always be selectable, even if it isn't in the list.
            val models = if (loadedPath != null && listed.none { samePath(it.path, loadedPath) }) {
                val f = File(loadedPath)
                listed + ModelFile(path = loadedPath, name = f.name, sizeBytes = f.length(), inAppStorage = false)
            } else listed
            val last = dataStore.settingsFlow.first().lastModelPath
            val current = _ui.value.selectedPath
            val pick = listOfNotNull(loadedPath, current, last)
                .firstNotNullOfOrNull { p -> models.firstOrNull { samePath(it.path, p) }?.path }
                ?: models.firstOrNull()?.path
            _ui.update { it.copy(models = models) }
            if (pick != null) selectModel(pick) else _ui.update { it.copy(selectedPath = null, facts = null) }
        }
    }

    fun selectModel(path: String) {
        viewModelScope.launch {
            val file = _ui.value.models.firstOrNull { it.path == path }
            val facts = withContext(Dispatchers.IO) {
                val summary = GgufInspector.inspect(File(path))
                val used = summary.identityMetadata.entries
                    .firstOrNull { it.key.endsWith(".expert_used_count") }?.value?.toIntOrNull() ?: 0
                ModelFacts(
                    path = path,
                    name = file?.displayName ?: File(path).name,
                    sizeBytes = file?.sizeBytes ?: File(path).length(),
                    kind = summary.kind,
                    expertCount = summary.expertCount,
                    expertUsed = used
                )
            }
            _ui.update { it.copy(selectedPath = path, facts = facts) }
        }
    }

    // ---------------------------------------------------------------- saves

    /** Creates exactly one save for the goal selected by the user. */
    fun createSaves(tier: AutoTier) {
        val state = _ui.value
        val device = state.device ?: return say("Scan the device first.")
        val facts = state.facts ?: return say("Pick a model first.")
        viewModelScope.launch {
            val values = AutoTuner.plan(device, facts, tier, allowNpu = state.useNpu)
            val fits = AutoTuner.fitsInRam(device, facts, values)
            val save = AutoSave(
                id = UUID.randomUUID().toString(),
                name = AutoSaveCodec.cleanName("${facts.name} · ${tier.title.substringBefore(" (")}"),
                tier = tier,
                kind = facts.kind.name,
                modelName = facts.name,
                createdAt = System.currentTimeMillis(),
                values = values,
                estTps = AutoTuner.estimateTps(device, facts, tier, values),
                lastError = if (fits) null else "The initial plan may exceed the conservative RAM estimate; measure only after reviewing the settings."
            )
            store.upsert(save)
            say("Created ${tier.title} settings for ${facts.name}. The speed is an estimate; measure it to verify real performance.")
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { if (!store.rename(id, name)) say("That name isn't valid.") }
    }

    fun delete(id: String) {
        viewModelScope.launch { store.delete(id) }
    }

    /** Applies a save as this model's override when possible; otherwise retains legacy global-save behavior. */
    fun use(id: String) {
        val save = store.saves.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val ready = engineController.state.value as? EngineState.Ready
            val identity = ready?.info?.path?.let { ModelProfileIdentity.fromPath(it) }
            if (identity != null && store.autoApplyModelProfilesEnabled.value) {
                if (!store.setModelProfile(identity, id)) {
                    say("Couldn't save this model profile. The current settings were left unchanged.")
                    return@launch
                }
                store.setActive(id)
                _ui.update { it.copy(reloadNeeded = true) }
                say("“${save.name}” is now this model's profile. Reload the model to apply it; other models keep their own settings.")
            } else {
                dataStore.applyTunedValues(save.values)
                store.setActive(id)
                val loaded = ready != null
                _ui.update { it.copy(reloadNeeded = loaded) }
                say(if (loaded) "“${save.name}” is on. Reload the model to use it."
                    else "“${save.name}” is on as the global default. It applies the next time a model loads.")
            }
        }
    }

    fun setAutoApplyModelProfilesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!store.setAutoApplyModelProfilesEnabled(enabled)) say("Couldn't save the profile preference.")
        }
    }

    fun resetCurrentModelProfile() {
        viewModelScope.launch {
            val path = (engineController.state.value as? EngineState.Ready)?.info?.path
            val identity = path?.let { ModelProfileIdentity.fromPath(it) }
            if (identity == null) {
                say("Load a model to reset its profile.")
            } else if (store.setModelProfile(identity, null)) {
                say("Model profile cleared. The next load uses global defaults.")
            } else {
                say("Couldn't clear this model profile.")
            }
        }
    }

    fun reloadModel() {
        viewModelScope.launch {
            val ready = engineController.state.value as? EngineState.Ready ?: return@launch
            val base = dataStore.settingsFlow.first()
            val s = store.settingsForModelPath(ready.info.path, base)
            val draft = if (s.spec == SpecSource.MTP && s.separateMtpDraftEnabled)
                modelManager.draftForModel(ready.info.path) else null
            _ui.update { it.copy(reloadNeeded = false, busy = "Reloading the model…") }
            val err = engineController.loadModel(ready.info.path, s.toLoadConfig(), ready.info.mmprojPath, draft)
            _ui.update { it.copy(busy = null) }
            if (err != null) say("Reload failed: ${err.take(200)}")
        }
    }

    // ---------------------------------------------------------------- export / import

    fun export(id: String, uri: Uri) {
        val save = store.saves.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(AutoSaveCodec.toJson(save).toByteArray()) } != null
                }.getOrDefault(false)
            }
            say(if (ok) "Saved “${save.name}” to your chosen file." else "Couldn't write the file.")
        }
    }

    fun importSave(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0
                        var tooBig = false
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_IMPORT_BYTES) { tooBig = true; break }
                            out.write(buf, 0, n)
                        }
                        if (tooBig) null else String(out.toByteArray(), Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            val parsed = text?.let { AutoSaveCodec.parse(it) }
            if (parsed == null) {
                say("That file isn't a BigMoeOnEdge+ auto save.")
                return@launch
            }
            // Always a new save: never overwrite one the user already has.
            store.upsert(parsed.copy(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis(), values = AutoTuner.sanitize(parsed.values)))
            say("Imported “${parsed.name}”.")
        }
    }

    // ---------------------------------------------------------------- measuring

    /** Rough minutes the measurement of [count] saves will take, for the "this may take a while" prompt. */
    fun measureSecondsFor(count: Int): Int {
        val facts = _ui.value.facts ?: return 0
        val tps = store.saves.value.map { it.estTps }.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 3.0
        return AutoTuner.estimateMeasureSeconds(facts, count, tps)
    }

    /**
     * Loads the selected model once per save, generates 64 tokens, records the real tokens/second, then
     * restores whatever was loaded before. Can be cancelled.
     */
    fun measure(ids: List<String>) {
        if (measureJob?.isActive == true) return
        val facts = _ui.value.facts ?: return say("Pick a model first.")
        val targets = store.saves.value.filter { it.id in ids }
        if (targets.isEmpty()) return
        measureJob = viewModelScope.launch {
            val before = dataStore.settingsFlow.first()
            val previous = engineController.loadedModel()
            var done = 0
            try {
                for ((i, save) in targets.withIndex()) {
                    val label = "${i + 1}/${targets.size}"
                    _ui.update { it.copy(busy = "Measuring $label: loading the model with “${save.name}”. This can take a while.") }
                    val config = save.values.applyTo(before).toLoadConfig()
                    val device = _ui.value.device

                    // Preflight 1: the file and the settings must be loadable at all.
                    val advice: LoadAdvice = withContext(Dispatchers.IO) {
                        ModelManager.adviseLoad(File(facts.path), (device?.totalRamMb ?: 0L) * 1024L * 1024L, config)
                    }
                    if (!advice.canLoad) {
                        store.upsert(save.copy(measuredTps = null, measuredAt = null, lastError = advice.errors.joinToString(" ").take(300)))
                        continue
                    }
                    // Preflight 2: refuse a load that would not fit in RAM. Android would kill the whole app.
                    if (device != null && !AutoTuner.fitsInRam(device, facts, save.values)) {
                        val need = AutoTuner.footprintMb(facts, save.values)
                        val can = AutoTuner.usableMb(device)
                        store.upsert(
                            save.copy(
                                measuredTps = null, measuredAt = null,
                                lastError = "Needs about ${gbText(need)} but this phone can safely give about ${gbText(can)}. " +
                                    "Not measured, to keep the app from being killed. create a new settings profile with a smaller context/cache, or use a smaller model."
                            )
                        )
                        continue
                    }

                    store.beginMeasuring(save.id)
                    try {
                        // Free the previous load completely before the next one: two big loads must never overlap.
                        engineController.unload()
                        delay(1500)
                        val loadError = engineController.loadModel(facts.path, config, null, null)
                        if (loadError != null) {
                            store.upsert(save.copy(measuredTps = null, measuredAt = null, lastError = loadError.take(300)))
                            continue
                        }
                        _ui.update { it.copy(busy = "Measuring $label: generating test text with “${save.name}”.") }
                        val result = runBenchmark()
                        store.upsert(
                            save.copy(
                                measuredTps = result.first,
                                measuredAt = if (result.first != null) System.currentTimeMillis() else null,
                                lastError = result.second
                            )
                        )
                        if (result.first != null) done++
                    } finally {
                        withContext(NonCancellable) { store.endMeasuring() }
                    }
                }
                say(if (done == targets.size) "Measured $done save(s)." else "Measured $done of ${targets.size}. Failed saves show the reason.")
            } catch (e: CancellationException) {
                say("Measuring cancelled.")
                throw e
            } catch (e: Throwable) {
                say("Measuring stopped: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                withContext(NonCancellable) {
                    _ui.update { it.copy(busy = "Restoring your previous model…") }
                    if (previous != null) {
                        val draft = if (before.spec == SpecSource.MTP && before.separateMtpDraftEnabled)
                            modelManager.draftForModel(previous.path) else null
                        engineController.loadModel(previous.path, before.toLoadConfig(), previous.mmprojPath, draft)
                    } else {
                        engineController.unload()
                    }
                    _ui.update { it.copy(busy = null) }
                }
            }
        }
    }

    fun cancelMeasure() {
        engineController.stopGeneration()
        measureJob?.cancel()
    }

    /** Returns (tokens/second or null, error text or null). */
    private suspend fun runBenchmark(): Pair<Double?, String?> {
        var tps = 0.0
        var error: String? = null
        val request = GenerationRequest(
            system = "",
            history = emptyList(),
            prompt = BENCH_PROMPT,
            expectedKey = "autotune-${System.nanoTime()}",
            maxTokens = BENCH_TOKENS,
            think = false
        )
        engineController.generate(request).collect { event ->
            if (event is EngineEvent.Finished) {
                if (event.ok) tps = event.stats.tokensPerSecond else error = event.error
            }
        }
        engineController.invalidateState()
        val failure = error
        return when {
            failure != null -> null to failure.take(300)
            tps <= 0.0 -> null to "The model produced no tokens during the test."
            else -> (Math.round(tps * 10) / 10.0) to null
        }
    }

    private fun gbText(mb: Long) = String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)

    private companion object {
        const val BENCH_TOKENS = 64
        const val BENCH_PROMPT = "Explain in a few sentences how a mechanical clock keeps time."
        const val MAX_IMPORT_BYTES = 256 * 1024
    }
}
