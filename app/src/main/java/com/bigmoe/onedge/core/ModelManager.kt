package com.bigmoe.onedge.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

data class ModelFile(val path: String, val name: String, val sizeBytes: Long)

/** Result of the checks done before loading a model. Warnings never block loading; errors do. */
data class LoadAdvice(val errors: List<String>, val warnings: List<String>) {
    val canLoad: Boolean get() = errors.isEmpty()
}

/**
 * Model files live in the app-private internal directory `filesDir/models`. Rationale
 * (docs/model-loading.md): the engine streams experts with O_DIRECT reads and needs a REAL path on a
 * normal filesystem. content:// URIs have no path, and shared/external storage goes through the FUSE
 * layer, where O_DIRECT is unreliable and slow. Internal storage is ext4/f2fs.
 * Advanced users can also `adb push` a .gguf into that directory or type an absolute path.
 */
class ModelManager(
    private val modelsDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    init {
        modelsDir.mkdirs()
    }

    fun directoryPath(): String = modelsDir.absolutePath

    suspend fun listModels(): List<ModelFile> = withContext(io) {
        modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && !isLaterShard(f.name) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { ModelFile(it.absolutePath, it.name, it.length()) }
            ?: emptyList()
    }

    suspend fun delete(path: String): Boolean = withContext(io) {
        val f = File(path)
        // Only ever delete inside our own models directory.
        f.isFile && f.canonicalPath.startsWith(modelsDir.canonicalPath + File.separator) && f.delete()
    }

    /**
     * Copies a picked document into [modelsDir]. Streams in 8 MiB chunks, reports progress, deletes the
     * partial file on failure or cancellation. Throws [IOException] with a readable message.
     */
    suspend fun importModel(
        resolver: ContentResolver,
        uri: Uri,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit
    ): ModelFile = withContext(io) {
        val (displayName, size) = queryNameAndSize(resolver, uri)
        val name = sanitizeFileName(displayName ?: "model.gguf")
        if (!name.endsWith(".gguf", ignoreCase = true)) {
            throw IOException("Only .gguf model files are supported (picked: $name).")
        }
        if (size > 0 && modelsDir.usableSpace < size + SPACE_MARGIN) {
            throw IOException(
                "Not enough free storage: need ${formatBytes(size)}, only ${formatBytes(modelsDir.usableSpace)} free."
            )
        }
        val target = File(modelsDir, name)
        val tmp = File(modelsDir, "$name.part")
        try {
            resolver.openInputStream(uri).use { input ->
                if (input == null) throw IOException("Could not open the selected file.")
                tmp.outputStream().use { output ->
                    val buf = ByteArray(8 * 1024 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        onProgress(copied, size)
                    }
                    output.fd.sync()
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not finalize the imported file.")
            ModelFile(target.absolutePath, target.name, target.length())
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return name to size
    }

    companion object {
        private const val SPACE_MARGIN = 256L * 1024 * 1024
        private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

        private val SHARD = Regex("-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)

        fun isProjectorName(fileName: String): Boolean = fileName.contains("mmproj", ignoreCase = true) && fileName.endsWith(".gguf", ignoreCase = true)

        /** Picks a likely projector by normalized-name prefix, without treating quantisation suffixes as identity. */
        fun suggestProjector(model: ModelFile, projectors: List<ModelFile>): ModelFile? {
            if (projectors.isEmpty()) return null
            val modelKey = normalizeProjectorKey(model.name)
            if (modelKey.isBlank()) return null
            return projectors
                .map { it to normalizeProjectorKey(it.name).removePrefix("mmproj ").trim() }
                // A projector with no name left ("mmproj-F16.gguf") says nothing about which model it belongs to:
                // it must never be picked by name, or it would be attached to every other model and break its load.
                .filter { (_, key) -> key.isNotBlank() }
                .mapNotNull { (file, key) ->
                    val match = key.startsWith(modelKey) || modelKey.startsWith(key)
                    if (!match) null else file to if (key == modelKey) 2 else 1
                }
                .sortedWith(compareByDescending<Pair<ModelFile, Int>> { it.second }.thenBy { it.first.name.lowercase() })
                .firstOrNull()?.first
        }

        private fun normalizeProjectorKey(name: String): String {
            return name.substringBeforeLast('.', name)
                .lowercase()
                .replace(Regex("\\bmmproj\\b"), " ")
                .replace(Regex("\\b(?:q\\d(?:_\\w+)?|iq\\w+|f16|f32|bf16|qnt)\\b"), " ")
                .replace(Regex("[_./-]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        /** True for shard 2..N of a split model; the engine is opened on shard 1 and finds its siblings. */
        fun isLaterShard(fileName: String): Boolean {
            val m = SHARD.find(fileName) ?: return false
            return m.groupValues[1].toIntOrNull()?.let { it > 1 } ?: false
        }

        fun sanitizeFileName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base.replace(Regex("[^A-Za-z0-9._\\- ]"), "_").trim().trimStart('.')
            return cleaned.ifEmpty { "model.gguf" }
        }

        fun formatBytes(bytes: Long): String {
            val gib = bytes / (1024.0 * 1024.0 * 1024.0)
            return if (gib >= 1.0) String.format(java.util.Locale.US, "%.1f GB", gib)
            else String.format(java.util.Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
        }

        /**
         * Pure checks (unit-tested). [totalRamBytes] from ActivityManager.MemoryInfo.totalMem,
         * [freeStorageBytes] unused for loading (kept for import). See docs/model-loading.md for the sources
         * of the thresholds; they are heuristics, not upstream requirements.
         */
        fun adviseLoad(file: File, totalRamBytes: Long, config: LoadConfig): LoadAdvice {
            val errors = ArrayList<String>()
            val warnings = ArrayList<String>()
            if (!file.isFile) errors.add("The model file does not exist: ${file.path}")
            else if (!file.canRead()) errors.add("The model file is not readable: ${file.path}")
            else if (!hasGgufMagic(file)) errors.add("This does not look like a GGUF file (bad header).")
            val size = if (file.isFile) file.length() else 0L
            val gib = 1024.0 * 1024.0 * 1024.0
            if (totalRamBytes > 0) {
                val ramGib = totalRamBytes / gib
                if (ramGib < 6.0) {
                    warnings.add(
                        "This device has only %.1f GB of RAM. Even with expert streaming, big MoE models are likely to be killed by Android or run very slowly.".format(java.util.Locale.US, ramGib)
                    )
                }
                if (config.cacheMode == CacheMode.FIXED && config.cacheMb * 1024L * 1024L > totalRamBytes * 6 / 10) {
                    warnings.add("The fixed expert cache (${config.cacheMb} MB) is more than 60% of the device RAM; Android may kill the app.")
                }
                if (config.denseWeights == DenseWeights.PINNED) {
                    warnings.add("'Pinned' dense weights lock memory; use it only if the device has plenty of free RAM.")
                }
            }
            if (size > 0 && size < 200L * 1024 * 1024) {
                warnings.add("The file is very small (${formatBytes(size)}). Is it a complete download?")
            }
            if (config.spec != SpecSource.OFF) {
                warnings.add("Guess ahead decodes greedily: the temperature setting is ignored (treated as 0) while it is on.")
                warnings.add("Guess ahead is disabled for image turns when a vision projector is loaded; image prompts and speculation are not combined.")
            }
            if (file.isFile && GgufInspector.inspect(file).kind == ModelKind.DENSE) {
                warnings.add("This is a regular (non-MoE) model. It is loaded the ordinary way, without expert streaming, so it should fit in RAM.")
            }
            return LoadAdvice(errors, warnings)
        }

        private fun hasGgufMagic(file: File): Boolean = try {
            FileInputStream(file).use { s ->
                val head = ByteArray(4)
                s.read(head) == 4 && head.contentEquals(GGUF_MAGIC)
            }
        } catch (_: IOException) {
            false
        }
    }
}
