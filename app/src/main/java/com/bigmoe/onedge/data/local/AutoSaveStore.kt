package com.bigmoe.onedge.data.local

import com.bigmoe.onedge.core.AutoSave
import com.bigmoe.onedge.core.AutoSaveCodec
import com.bigmoe.onedge.core.AutoTuner
import com.bigmoe.onedge.core.ModelProfileIdentity
import com.bigmoe.onedge.core.applyTo
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject

/** Auto-settings saves: one small JSON file per save under [dir], plus a tiny file naming the active one. */
class AutoSaveStore(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val lock = Mutex()
    private val _saves = MutableStateFlow<List<AutoSave>>(emptyList())
    val saves: StateFlow<List<AutoSave>> = _saves.asStateFlow()
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()
    private val _modelProfiles = MutableStateFlow<Map<String, String>>(emptyMap())
    val modelProfiles: StateFlow<Map<String, String>> = _modelProfiles.asStateFlow()
    private val _autoApplyModelProfilesEnabled = MutableStateFlow(true)
    val autoApplyModelProfilesEnabled: StateFlow<Boolean> = _autoApplyModelProfilesEnabled.asStateFlow()

    private fun fileFor(id: String) = File(dir, "$id.json")
    private val activeFile get() = File(dir, "active.txt")
    private val markerFile get() = File(dir, "measuring.txt")
    private val profilesFile get() = File(dir, "model-profiles.json")
    private val profileEnabledFile get() = File(dir, "model-profiles-enabled.txt")

    private fun safeId(id: String) = id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' }

    suspend fun load() = withContext(io) {
        lock.withLock {
            dir.mkdirs()
            val list = (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != profilesFile.name } ?: emptyArray())
                .mapNotNull { f -> runCatching { AutoSaveCodec.parse(f.readText()) }.getOrNull() }
                .filter { safeId(it.id) }
                .sortedBy { it.createdAt }
            _saves.value = list
            val active = runCatching { activeFile.readText().trim() }.getOrNull()
            _activeId.value = active?.takeIf { id -> list.any { it.id == id } }
            val validSaveIds = list.mapTo(hashSetOf()) { it.id }
            _modelProfiles.value = readModelProfilesLocked().filterValues { it in validSaveIds }
            _autoApplyModelProfilesEnabled.value = when (runCatching { profileEnabledFile.readText().trim() }.getOrNull()) {
                "false" -> false
                else -> true
            }
        }
    }

    suspend fun upsert(save: AutoSave): Boolean = withContext(io) {
        lock.withLock {
            if (!safeId(save.id)) return@withLock false
            dir.mkdirs()
            val tmp = File(dir, "${save.id}.json.tmp")
            val ok = runCatching {
                tmp.writeText(AutoSaveCodec.toJson(save))
                tmp.renameTo(fileFor(save.id)) || fileFor(save.id).let { target -> target.delete(); tmp.renameTo(target) }
            }.getOrDefault(false)
            if (ok) _saves.value = (_saves.value.filterNot { it.id == save.id } + save).sortedBy { it.createdAt }
            else tmp.delete()
            ok
        }
    }

    suspend fun rename(id: String, rawName: String): Boolean {
        val name = AutoSaveCodec.cleanName(rawName)
        if (name.isEmpty()) return false
        val current = _saves.value.firstOrNull { it.id == id } ?: return false
        return upsert(current.copy(name = name))
    }

    suspend fun delete(id: String) = withContext(io) {
        lock.withLock {
            if (!safeId(id)) return@withLock
            fileFor(id).delete()
            _saves.value = _saves.value.filterNot { it.id == id }
            val remainingProfiles = _modelProfiles.value.filterValues { it != id }
            if (remainingProfiles != _modelProfiles.value && writeModelProfilesLocked(remainingProfiles)) {
                _modelProfiles.value = remainingProfiles
            }
            if (_activeId.value == id) {
                _activeId.value = null
                activeFile.delete()
            }
        }
    }

    suspend fun setActive(id: String?) = withContext(io) {
        lock.withLock {
            dir.mkdirs()
            if (id == null) activeFile.delete() else runCatching { activeFile.writeText(id) }
            _activeId.value = id
        }
    }

    /**
     * Crash marker. Written right before a measurement loads the model and removed right after it ends. If it
     * is still there at the next start, the app was killed (out of memory or a native crash) mid-measurement.
     */
    suspend fun beginMeasuring(id: String) = withContext(io) {
        lock.withLock { dir.mkdirs(); runCatching { markerFile.writeText(id) } }
        Unit
    }

    suspend fun endMeasuring() = withContext(io) {
        lock.withLock { runCatching { markerFile.delete() } }
        Unit
    }

    /** Returns the id of a save whose measurement never finished, and clears the marker. */
    suspend fun takeInterruptedMeasurement(): String? = withContext(io) {
        lock.withLock {
            val id = runCatching { markerFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            if (id != null) runCatching { markerFile.delete() }
            id
        }
    }

    fun profileFor(modelIdentity: String): AutoSave? {
        val saveId = _modelProfiles.value[modelIdentity] ?: return null
        return _saves.value.firstOrNull { it.id == saveId }
    }

    /** Assign a saved Auto Settings profile to one model identity, or remove its override when saveId is null. */
    suspend fun setModelProfile(modelIdentity: String, saveId: String?): Boolean = withContext(io) {
        lock.withLock {
            if (modelIdentity.isBlank() || modelIdentity.length > 256) return@withLock false
            if (saveId != null && _saves.value.none { it.id == saveId }) return@withLock false
            val next = _modelProfiles.value.toMutableMap()
            if (saveId == null) next.remove(modelIdentity) else next[modelIdentity] = saveId
            val ok = writeModelProfilesLocked(next)
            if (ok) _modelProfiles.value = next.toMap()
            ok
        }
    }

    suspend fun setAutoApplyModelProfilesEnabled(enabled: Boolean): Boolean = withContext(io) {
        lock.withLock {
            dir.mkdirs()
            val tmp = File(dir, "model-profiles-enabled.tmp")
            val ok = runCatching {
                tmp.writeText(enabled.toString())
                tmp.renameTo(profileEnabledFile) || profileEnabledFile.let { target -> target.delete(); tmp.renameTo(target) }
            }.getOrDefault(false)
            if (ok) _autoApplyModelProfilesEnabled.value = enabled else tmp.delete()
            ok
        }
    }

    /** Resolves only the saved load-time tuning overlay; global defaults remain untouched. */
    suspend fun settingsForModelPath(path: String, base: EngineSettings): EngineSettings {
        runCatching { load() }.getOrElse { return base }
        if (!_autoApplyModelProfilesEnabled.value) return base
        val identity = ModelProfileIdentity.fromPath(path) ?: return base
        val save = profileFor(identity) ?: return base
        return runCatching { AutoTuner.sanitize(save.values).applyTo(base) }.getOrDefault(base)
    }

    private fun readModelProfilesLocked(): Map<String, String> = runCatching {
        val json = JSONObject(profilesFile.readText())
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optString(key).takeIf { safeId(it) }
            if (!key.isBlank() && value != null) result[key] = value
        }
        result
    }.getOrDefault(emptyMap())

    private fun writeModelProfilesLocked(values: Map<String, String>): Boolean {
        dir.mkdirs()
        val json = JSONObject()
        values.forEach { (key, value) ->
            if (key.isNotBlank() && key.length <= 256 && safeId(value)) json.put(key, value)
        }
        val tmp = File(dir, "model-profiles.json.tmp")
        val ok = runCatching {
            tmp.writeText(json.toString())
            tmp.renameTo(profilesFile) || profilesFile.let { target -> target.delete(); tmp.renameTo(target) }
        }.getOrDefault(false)
        if (!ok) tmp.delete()
        return ok
    }
}
