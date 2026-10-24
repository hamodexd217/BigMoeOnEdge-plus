package com.bigmoe.onedge.data.local

import com.bigmoe.onedge.core.AutoSave
import com.bigmoe.onedge.core.AutoSaveCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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

    private fun fileFor(id: String) = File(dir, "$id.json")
    private val activeFile get() = File(dir, "active.txt")
    private val markerFile get() = File(dir, "measuring.txt")

    private fun safeId(id: String) = id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' }

    suspend fun load() = withContext(io) {
        lock.withLock {
            dir.mkdirs()
            val list = (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
                .mapNotNull { f -> runCatching { AutoSaveCodec.parse(f.readText()) }.getOrNull() }
                .filter { safeId(it.id) }
                .sortedBy { it.createdAt }
            _saves.value = list
            val active = runCatching { activeFile.readText().trim() }.getOrNull()
            _activeId.value = active?.takeIf { id -> list.any { it.id == id } }
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
}
