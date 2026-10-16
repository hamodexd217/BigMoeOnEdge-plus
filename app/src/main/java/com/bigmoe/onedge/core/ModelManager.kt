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

data class ModelFile(val path: String, val name: String, val sizeBytes: Long, val inAppStorage: Boolean = true)

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
        val internal = modelsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && !isLaterShard(f.name) && !isDraftName(f.name)
        }.orEmpty().map { ModelFile(it.canonicalPath, it.name, it.length(), true) }
        val external = registeredPaths().mapNotNull { path ->
            val f = File(path)
            if (f.isFile && f.name.endsWith(".gguf", true) && !isLaterShard(f.name) && !isDraftName(f.name))
                ModelFile(runCatching { f.canonicalPath }.getOrDefault(path), f.name, f.length(), false) else null
        }
        (internal + external).distinctBy { it.path }.sortedBy { it.name.lowercase() }
    }

    suspend fun listDrafts(): List<ModelFile> = withContext(io) {
        val internal = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) && isDraftName(f.name) }
            .orEmpty().map { ModelFile(it.canonicalPath, it.name, it.length(), true) }
        val external = registeredPaths().mapNotNull { path ->
            val f = File(path)
            if (f.isFile && f.name.endsWith(".gguf", true) && isDraftName(f.name))
                ModelFile(runCatching { f.canonicalPath }.getOrDefault(path), f.name, f.length(), false) else null
        }
        (internal + external).distinctBy { it.path }.sortedBy { it.name.lowercase() }
    }

    /** Register an existing real filesystem path without copying a potentially enormous model. */
    suspend fun registerExistingPath(path: String): ModelFile = withContext(io) {
        val typedPath = path.trim()
        if (typedPath.isEmpty() || !File(typedPath).isAbsolute) {
            throw IOException("Enter a full absolute filesystem path, for example /storage/emulated/0/Download/model.gguf.")
        }
        val f = try {
            File(typedPath).canonicalFile
        } catch (e: SecurityException) {
            throw IOException("Android denied access while resolving this path. For shared storage, enable Grant path access and try again.", e)
        }
        if (!f.exists()) throw IOException("File not found at path: $typedPath. Check the folder and filename, including spaces and the .gguf extension.")
        if (!f.isFile) throw IOException("The path points to a folder, not a GGUF file: $typedPath")
        if (!f.canRead()) {
            throw IOException(
                "Android cannot read this file path: $typedPath. For a file under /storage/emulated/0, use Grant path access (Android 11+) and retry. If access is already enabled, verify the exact path and that the file is available locally."
            )
        }
        if (!f.name.endsWith(".gguf", true)) throw IOException("Only .gguf files can be registered.")
        if (!hasGgufMagic(f)) throw IOException("The selected file does not have a valid GGUF magic header.")
        if (isLaterShard(f.name)) throw IOException("Choose the first split shard (-00001-of-000NN.gguf), not ${f.name}.")
        splitSetIssue(f)?.let { throw IOException(it) }
        if (!isInsideModelsDir(f)) {
            val paths = registeredPaths().toMutableSet()
            paths.add(f.path)
            saveRegisteredPaths(paths)
        }
        ModelFile(f.path, f.name, f.length(), isInsideModelsDir(f))
    }

    /** Draft models are explicit sidecars, keyed to the exact target path; null detaches a sidecar. */
    suspend fun attachDraft(modelPath: String, draftPath: String?) = withContext(io) {
        val target = canonicalPath(modelPath)
        val props = draftLinks()
        val key = encodePath(target)
        if (draftPath.isNullOrBlank()) {
            props.remove(key)
        } else {
            val draft = File(draftPath).canonicalFile
            if (!draft.isFile || !draft.canRead() || !draft.name.endsWith(".gguf", true))
                throw IOException("Draft path is not a readable GGUF file: $draftPath")
            if (!hasGgufMagic(draft)) throw IOException("The selected draft does not have a GGUF header.")
            if (isLaterShard(draft.name)) throw IOException("Select a complete draft GGUF, not a later split shard.")
            if (!isDraftName(draft.name)) throw IOException("Name the sidecar with an MTP marker (for example mtp-Qwen3.8-Flash-Next-shared-Q8_0.gguf) so it is not mistaken for a target model.")
            splitSetIssue(draft)?.let { throw IOException(it) }
            props.setProperty(key, encodePath(draft.path))
        }
        saveDraftLinks(props)
    }

    suspend fun draftForModel(modelPath: String): String? = withContext(io) {
        draftLinks().getProperty(encodePath(canonicalPath(modelPath)))?.let(::decodePath)?.takeIf { File(it).isFile }
    }

    /** Delete a model as a group, but never delete a user-owned registered file from external storage. */
    suspend fun delete(path: String): Boolean = withContext(io) {
        val f = File(path)
        if (!f.isFile) return@withContext false
        val canonical = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        val isRegistered = registeredPaths().contains(canonical) && !isInsideModelsDir(f)
        val targets = if (!isRegistered) deleteTargets(f) else emptyList()
        val ok = if (isRegistered) {
            val paths = registeredPaths().toMutableSet(); paths.remove(canonical); saveRegisteredPaths(paths); true
        } else {
            if (!isInsideModelsDir(f) || targets.isEmpty() || targets.any { !isInsideModelsDir(it) }) false
            else targets.all { !it.exists() || it.delete() }
        }
        if (ok) {
            val props = draftLinks()
            val keysToRemove = props.stringPropertyNames().filter { k ->
                decodePath(k) == canonical || runCatching { decodePath(props.getProperty(k)) == canonical }.getOrDefault(false)
            }
            keysToRemove.forEach(props::remove)
            saveDraftLinks(props)
        }
        ok
    }

    /** Rename ordinary models or an entire stored shard set as one transaction. Registered external paths stay owned by their folder. */
    suspend fun rename(path: String, requestedName: String): ModelFile = withContext(io) {
        val src = File(path).canonicalFile
        if (!src.isFile) throw IOException("Model file does not exist: $path")
        if (!isInsideModelsDir(src)) throw IOException("Registered external files must be renamed in their original folder; the app will keep the registration until you remove it.")
        if (isLaterShard(src.name)) throw IOException("Rename the model from its first shard, not a later shard.")
        val raw = sanitizeFileName(requestedName)
        val oldMatch = SHARD.find(src.name)
        val name = if (oldMatch != null) raw.removeSuffixIgnoreCase(".gguf") else if (raw.endsWith(".gguf", true)) raw else "$raw.gguf"
        if (oldMatch != null) splitSetIssue(src)?.let { throw IOException("The split set is invalid; refusing to rename it: $it") }
        val sources = splitTargets(src)
        if (sources.isEmpty()) throw IOException("The split set is incomplete; refusing to rename it.")
        val newFiles = if (oldMatch != null) {
            val prefix = name.removeSuffixIgnoreCase(".gguf")
            sources.map { old -> File(modelsDir, "$prefix-${SHARD.find(old.name)!!.groupValues[1]}-of-${oldMatch.groupValues[2]}.gguf") }
        } else listOf(File(modelsDir, name))
        if (newFiles.any { it.exists() && it.canonicalPath !in sources.map(File::getCanonicalPath) }) throw IOException("A file with the requested name already exists.")
        val staged = sources.mapIndexed { i, old ->
            val tmp = File(modelsDir, ".rename-${System.nanoTime()}-$i.tmp")
            if (!old.renameTo(tmp)) throw IOException("Could not stage ${old.name}; rename was cancelled.")
            old to tmp
        }
        try {
            staged.forEachIndexed { i, (_, tmp) -> if (!tmp.renameTo(newFiles[i])) throw IOException("Could not finish rename to ${newFiles[i].name}.") }
        } catch (t: Throwable) {
            // Roll back whichever entries made it to their new names, then restore the original names.
            newFiles.forEachIndexed { i, nf -> if (i < staged.size && nf.exists()) nf.renameTo(staged[i].second) }
            staged.forEach { (old, tmp) -> if (tmp.exists()) tmp.renameTo(old) }
            throw t
        }
        val props = draftLinks()
        val oldPath = src.canonicalPath
        val newPath = newFiles.first().canonicalPath
        val existing = props.getProperty(encodePath(oldPath))
        props.remove(encodePath(oldPath))
        if (existing != null) props.setProperty(encodePath(newPath), existing)
        saveDraftLinks(props)
        ModelFile(newPath, newFiles.first().name, newFiles.first().length(), true)
    }

    /** Human-readable shard validation. Checks the complete numbered set and metadata on the first shard. */
    fun splitSetIssue(first: File): String? {
        val m = SHARD.find(first.name) ?: return null
        val index = m.groupValues[1].toIntOrNull() ?: return "Invalid shard index in ${first.name}."
        val count = m.groupValues[2].toIntOrNull() ?: return "Invalid shard count in ${first.name}."
        if (index != 1) return "Select the first shard (-00001-of-$count), not ${first.name}."
        if (count < 2 || index > count) return "Invalid split GGUF numbering in ${first.name}."
        val prefix = first.name.substringBeforeLast(m.value)
        val missing = (1..count).map { n ->
            File(first.parentFile, "$prefix-${n.toString().padStart(5, '0')}-of-${count.toString().padStart(5, '0')}.gguf")
        }.filterNot { it.isFile && it.length() > 0 }
        if (missing.isNotEmpty()) return "Split GGUF is incomplete. Missing/empty shards:\n" + missing.joinToString("\n") { it.name }
        val firstSummary = GgufInspector.inspect(first)
        if (firstSummary.kind == ModelKind.UNKNOWN || firstSummary.architecture.isBlank()) return "The first shard's GGUF metadata could not be read: ${first.name}."
        if (firstSummary.splitCount != null && firstSummary.splitCount != count.toLong())
            return "Split metadata says ${firstSummary.splitCount} shards, but the filename says $count."
        var metadataBase = firstSummary.splitNo?.let { if (it == 0L) 0L else if (it == 1L) 1L else return "The selected first shard has unexpected split.no=$it." }
        for (n in 1..count) {
            val f = File(first.parentFile, "$prefix-${n.toString().padStart(5, '0')}-of-${count.toString().padStart(5, '0')}.gguf")
            if (!hasGgufMagic(f)) return "Shard ${f.name} has an invalid GGUF magic header."
            val summary = GgufInspector.inspect(f)
            if (summary.kind == ModelKind.UNKNOWN) return "Shard ${f.name} has unreadable GGUF metadata."
            if (summary.architecture.isNotBlank() && summary.architecture != firstSummary.architecture)
                return "Shard ${f.name} uses architecture ${summary.architecture}, not ${firstSummary.architecture}."
            if (summary.splitCount != null && summary.splitCount != count.toLong())
                return "Shard ${f.name} reports split.count=${summary.splitCount}, expected $count."
            if (summary.splitNo != null) {
                val candidateBase = summary.splitNo - (n - 1L)
                if (candidateBase != 0L && candidateBase != 1L)
                    return "Shard ${f.name} reports split.no=${summary.splitNo}, inconsistent with shard index $n."
                if (metadataBase == null) metadataBase = candidateBase
                else if (metadataBase != candidateBase) return "Shard ${f.name} uses a different split.no numbering base."
            }
        }
        return null
    }

    private fun isInsideModelsDir(file: File): Boolean = runCatching {
        file.canonicalPath.startsWith(modelsDir.canonicalPath + File.separator)
    }.getOrDefault(false)

    private fun canonicalPath(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(File(path).absolutePath)

    private fun registeredPaths(): Set<String> = try {
        registeredFile.readLines().filter { it.isNotBlank() }.map { decodePath(it) }.toSet()
    } catch (_: Exception) { emptySet() }

    private fun saveRegisteredPaths(paths: Set<String>) {
        val tmp = File(modelsDir, ".registered-paths.part")
        tmp.writeText(paths.sorted().joinToString("\n") { encodePath(it) })
        if (!tmp.renameTo(registeredFile)) {
            registeredFile.writeText(paths.sorted().joinToString("\n") { encodePath(it) }); tmp.delete()
        }
    }

    private val registeredFile: File get() = File(modelsDir, ".registered-paths")
    private val draftLinksFile: File get() = File(modelsDir, ".draft-links.properties")
    private fun draftLinks(): java.util.Properties = java.util.Properties().also { props ->
        try { draftLinksFile.inputStream().use(props::load) } catch (_: Exception) { }
    }
    private fun saveDraftLinks(props: java.util.Properties) {
        draftLinksFile.outputStream().use { props.store(it, "BigMoeOnEdge MTP sidecar links") }
    }
    private fun encodePath(path: String): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))
    private fun decodePath(path: String): String = runCatching { String(java.util.Base64.getUrlDecoder().decode(path), Charsets.UTF_8) }.getOrDefault("")

    private fun splitTargets(first: File): List<File> {
        val targets = deleteTargets(first)
        val m = SHARD.find(first.name) ?: return targets
        val count = m.groupValues[2].toIntOrNull() ?: return emptyList()
        return targets.takeIf { it.size == count && it.all { f -> f.isFile && f.length() > 0 } }.orEmpty()
    }

    private fun deleteTargets(first: File): List<File> {
        val m = SHARD.find(first.name)
        if (m == null) return listOf(first)
        if (m.groupValues[1].toIntOrNull() != 1) return emptyList()
        val count = m.groupValues[2].toIntOrNull() ?: return emptyList()
        val prefix = first.name.substringBeforeLast(m.value)
        return (1..count).map { i -> File(first.parentFile, "$prefix-${i.toString().padStart(5, '0')}-of-${count.toString().padStart(5, '0')}.gguf") }
            .filter { it.exists() }
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String = if (endsWith(suffix, true)) dropLast(suffix.length) else this

    /**
     * Copies a picked document into [modelsDir]. Streams in 8 MiB chunks, reports progress, deletes the
     * partial file on failure or cancellation. Throws [IOException] with a readable message.
     */
    suspend fun importModel(
        resolver: ContentResolver,
        uri: Uri,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit
    ): ModelFile = importModels(resolver, listOf(uri), onProgress).single()

    /**
     * Import one or more GGUF files selected through SAF. Multiple selection can carry every shard of
     * a split GGUF; all selected files are staged first and only renamed into place after every copy
     * succeeds, so a cancelled/failed import does not leave a half-registered model.
     */
    suspend fun importModels(
        resolver: ContentResolver,
        uris: List<Uri>,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit
    ): List<ModelFile> = withContext(io) {
        if (uris.isEmpty()) throw IOException("No files were selected.")
        val selected = uris.map { uri ->
            val (displayName, size) = queryNameAndSize(resolver, uri)
            val name = sanitizeFileName(displayName ?: "model.gguf")
            if (!name.endsWith(".gguf", ignoreCase = true)) throw IOException("Only .gguf files are supported (picked: $name).")
            if (isLaterShard(name) && uris.size == 1) throw IOException("Do not select a later shard without the first shard (-00001-of-000NN.gguf); select every shard together.")
            Triple(uri, name, size)
        }
        if (selected.map { it.second.lowercase() }.distinct().size != selected.size) {
            throw IOException("The selection contains duplicate file names. Rename them before importing.")
        }
        val split = selected.filter { SHARD.containsMatchIn(it.second) }
        if (split.isNotEmpty()) {
            if (split.size != selected.size) throw IOException("Do not mix a split model with unrelated GGUFs in one import.")
            val firstMatch = SHARD.find(split.first().second)!!
            val prefix = split.first().second.substringBeforeLast(firstMatch.value)
            val count = firstMatch.groupValues[2].toIntOrNull() ?: 0
            if (count < 2 || split.any { row ->
                    val m = SHARD.find(row.second) ?: return@any true
                    row.second.substringBeforeLast(m.value) != prefix || m.groupValues[2].toIntOrNull() != count
                }) {
                throw IOException("Selected shards do not belong to one split GGUF set.")
            }
            val indexes = split.map { SHARD.find(it.second)!!.groupValues[1].toInt() }
            if (indexes.size != count || indexes.toSet() != (1..count).toSet()) {
                val missing = (1..count).filterNot { it in indexes }.joinToString { it.toString().padStart(5, '0') }
                throw IOException("Select all $count shards together. Missing shard indexes: ${missing.ifEmpty { "duplicate/extra indexes" }}.")
            }
        }
        val sorted = if (split.isEmpty()) selected else selected.sortedBy { SHARD.find(it.second)!!.groupValues[1].toInt() }
        val targets = sorted.map { File(modelsDir, it.second) }
        if (targets.map { it.canonicalPath }.distinct().size != targets.size) throw IOException("Selected GGUF paths collide after normalizing file names.")
        targets.firstOrNull { it.exists() }?.let { throw IOException("A file named ${it.name} already exists; rename or remove it before importing.") }

        val totalKnown = sorted.filter { it.third > 0 }.sumOf { it.third }
        if (totalKnown > 0 && modelsDir.usableSpace < totalKnown + SPACE_MARGIN) {
            throw IOException("Not enough free storage: need at least ${formatBytes(totalKnown)}, only ${formatBytes(modelsDir.usableSpace)} free.")
        }
        val importNonce = System.nanoTime()
        val staged = sorted.mapIndexed { i, _ -> File(modelsDir, ".import-$importNonce-$i.part") }
        var copiedTotal = 0L
        try {
            sorted.forEachIndexed { index, row ->
                resolver.openInputStream(row.first).use { input ->
                    if (input == null) throw IOException("Could not open ${row.second}.")
                    staged[index].outputStream().use { output ->
                        val buf = ByteArray(8 * 1024 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (modelsDir.usableSpace < n + SPACE_MARGIN) {
                                throw IOException("Storage is nearly full while copying ${row.second}; the partial import was removed.")
                            }
                            output.write(buf, 0, n)
                            copiedTotal += n
                            onProgress(copiedTotal, totalKnown)
                        }
                        output.fd.sync()
                    }
                }
                if (!hasGgufMagic(staged[index])) throw IOException("${row.second} does not start with a GGUF header.")
            }
            val finalized = ArrayList<File>()
            try {
                staged.forEachIndexed { index, tmp ->
                    if (!tmp.renameTo(targets[index])) throw IOException("Could not finalize import of ${targets[index].name}.")
                    finalized += targets[index]
                }
            } catch (t: Throwable) {
                finalized.forEach { it.delete() }
                throw t
            }
            if (split.isNotEmpty()) splitSetIssue(targets.first())?.let { issue ->
                targets.forEach { it.delete() }
                throw IOException("Imported split GGUF failed validation: $issue")
            }
            targets.map { ModelFile(it.canonicalPath, it.name, it.length(), true) }
        } catch (t: Throwable) {
            staged.forEach { it.delete() }
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

        fun isDraftName(fileName: String): Boolean {
            val n = fileName.lowercase()
            return n.startsWith("mtp-") || n.startsWith("mtp_") || n.contains("-mtp-") || n.contains("_mtp_") || n.contains("-shared-mtp")
        }

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
            if (file.isFile) ModelManager(file.parentFile ?: File(".")).splitSetIssue(file)?.let(errors::add)
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
