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
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

data class ModelFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val inAppStorage: Boolean = true,
    val displayNameOverride: String? = null
) {
    /** Split models are shown by their logical base/custom name, while [name] remains the real first-shard filename. */
    val displayName: String
        get() = displayNameOverride?.takeIf { it.isNotBlank() }
            ?: SHARD_NAME_PATTERN.find(name)?.let { name.substringBeforeLast(it.value) }
            ?: name

    private companion object {
        val SHARD_NAME_PATTERN = Regex("-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)
    }
}

/** Persistent summary of an import that still needs one or more numbered shards. */
data class IncompleteModelImport(
    val id: String,
    val displayName: String,
    val expectedShardCount: Int,
    val presentShardIndices: List<Int>,
    val totalSizeBytes: Long,
    val status: String,
    val validationMessage: String? = null
) {
    val missingShardIndices: List<Int>
        get() = (1..expectedShardCount).filterNot { it in presentShardIndices }
}

data class ModelImportResult(
    val importedModels: List<ModelFile>,
    val incompleteImport: IncompleteModelImport? = null,
    val message: String
)

/** Stream source abstraction so the same incremental validator can be tested without Android SAF. */
data class SplitShardSource(val name: String, val sizeBytes: Long, val openStream: () -> InputStream)

private data class ParsedShardName(val prefix: String, val index: Int, val count: Int)

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

    /** Lists persisted partial imports. Partial shards live outside the model root and cannot be loaded by the engine. */
    suspend fun listIncompleteImports(): List<IncompleteModelImport> = withContext(io) {
        listIncompleteImportsInternal().sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    /** Removes only app-owned partial copies; originals selected through SAF are never deleted. */
    suspend fun removeIncompleteImport(id: String): Boolean = withContext(io) {
        if (!id.matches(Regex("split-[a-f0-9]{24}"))) return@withContext false
        val root = incompleteRoot().canonicalFile
        val target = File(root, id).canonicalFile
        if (!target.path.startsWith(root.path + File.separator) || !target.isDirectory) return@withContext false
        if (ACTIVE_IMPORT_PATHS.any { it.startsWith(target.path + File.separator) }) return@withContext false
        target.deleteRecursively()
    }

    suspend fun listModels(): List<ModelFile> = withContext(io) {
        cleanupStaleSingleFileImportTemps()
        val displayNames = completedDisplayNames()
        val internal = modelsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && !isLaterShard(f.name) && !isDraftName(f.name) && hasCompleteShardFiles(f)
        }.orEmpty().map { f -> ModelFile(f.canonicalPath, f.name, logicalModelSize(f), true, displayNames[f.canonicalPath]) }
        val external = registeredPaths().mapNotNull { path ->
            val f = File(path)
            if (f.isFile && f.name.endsWith(".gguf", true) && !isLaterShard(f.name) && !isDraftName(f.name) && hasCompleteShardFiles(f)) {
                val canonical = runCatching { f.canonicalPath }.getOrDefault(path)
                ModelFile(canonical, f.name, logicalModelSize(f), false, displayNames[canonical])
            } else null
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
            removeCompletedManifest(canonical)
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
        if (oldMatch != null) {
            splitSetIssue(src)?.let { throw IOException("The split set is invalid; refusing to rename it: $it") }
            if (name.isBlank()) throw IOException("Enter a non-empty display name.")
            val props = loadOrCreateReadyManifest(src, oldMatch.groupValues[2].toInt())
            props.setProperty("displayName", name)
            persistManifest(completeManifestFile(src), props)
            return@withContext ModelFile(src.canonicalPath, src.name, logicalModelSize(src), true, name)
        }
        val sources = splitTargets(src)
        if (sources.isEmpty()) throw IOException("The split set is incomplete; refusing to rename it.")
        val newFiles = listOf(File(modelsDir, name))
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
        if (firstSummary.tensorCount <= 0L) return "The first shard has no tensor descriptors in its GGUF header."
        if (firstSummary.splitCount != count.toLong())
            return "The first shard's split.count is ${firstSummary.splitCount ?: "missing"}, but the filename says $count."
        if (count > 65535) return "This llama.cpp build uses a 16-bit shard count and cannot load $count shards."
        val firstSplitNo = firstSummary.splitNo ?: return "The first shard is missing required GGUF metadata split.no."
        if (firstSplitNo != 0L) return "The first shard reports split.no=$firstSplitNo; this llama.cpp build requires zero-based numbering (split.no=0 for -00001)."
        for (n in 1..count) {
            val f = File(first.parentFile, "$prefix-${n.toString().padStart(5, '0')}-of-${count.toString().padStart(5, '0')}.gguf")
            if (!hasGgufMagic(f)) return "Shard ${f.name} has an invalid GGUF magic header."
            val summary = GgufInspector.inspect(f)
            if (summary.kind == ModelKind.UNKNOWN) return "Shard ${f.name} has unreadable GGUF metadata or tensor descriptors."
            if (summary.tensorCount <= 0L) return "Shard ${f.name} has no tensor descriptors in its GGUF header."
            if (summary.splitTensorCount != null && summary.splitTensorCount < summary.tensorCount)
                return "Shard ${f.name} reports split.tensors.count=${summary.splitTensorCount}, below its own tensor count ${summary.tensorCount}."
            // Standard llama.cpp shards after index 1 may contain only split metadata and tensor headers.
            if (summary.architecture.isNotBlank() && summary.architecture != firstSummary.architecture)
                return "Shard ${f.name} uses architecture ${summary.architecture}, not ${firstSummary.architecture}."
            if (summary.splitCount != null && summary.splitCount != count.toLong())
                return "Shard ${f.name} reports split.count=${summary.splitCount}, expected $count."
            val splitNo = summary.splitNo ?: return "Shard ${f.name} is missing required GGUF metadata split.no."
            val expectedSplitNo = n - 1L
            if (splitNo != expectedSplitNo)
                return "Shard ${f.name} reports split.no=$splitNo, expected $expectedSplitNo for filename index $n."
            identityMismatch(firstSummary, summary)?.let { mismatch ->
                return "Shard ${f.name} has incompatible model metadata: $mismatch"
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
        val activePaths = staged.map { it.canonicalPath }
        activePaths.forEach(ACTIVE_IMPORT_PATHS::add)
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
        } finally {
            activePaths.forEach(ACTIVE_IMPORT_PATHS::remove)
        }
    }

    /**
     * Imports a separate MTP draft from SAF into app-private storage and attaches it to [modelPath].
     * The source document is never modified. The app-owned name is normalized with an `mtp-` prefix
     * so it cannot accidentally appear as a target model in the model picker.
     * This path intentionally accepts a single, non-sharded GGUF sidecar; split MTP drafts are rejected
     * with a clear message until the native sidecar loader supports a verified split-set contract.
     */
    suspend fun importAndAttachDraft(
        resolver: ContentResolver,
        uri: Uri,
        modelPath: String,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): ModelFile = withContext(io) {
        val target = File(canonicalPath(modelPath))
        if (!target.isFile || !target.canRead() || isDraftName(target.name) || isProjectorName(target.name)) {
            throw IOException("Choose a readable target text model before attaching an MTP draft.")
        }
        val (displayName, reportedSize) = queryNameAndSize(resolver, uri)
        val originalName = sanitizeFileName(displayName ?: "draft.gguf")
        if (!originalName.endsWith(".gguf", ignoreCase = true)) {
            throw IOException("Choose an MTP draft in .gguf format.")
        }
        if (isProjectorName(originalName)) throw IOException("This file looks like an mmproj vision projector, not an MTP draft.")
        if (isLaterShard(originalName) || SHARD.containsMatchIn(originalName)) {
            throw IOException("Separate MTP sidecars must currently be a single GGUF file, not a split shard set.")
        }
        val draftName = if (isDraftName(originalName)) originalName else "mtp-$originalName"
        val extensionless = draftName.removeSuffixIgnoreCase(".gguf")
        var destination = File(modelsDir, draftName)
        var suffix = 2
        while (destination.exists()) {
            destination = File(modelsDir, "$extensionless-$suffix.gguf")
            suffix++
        }
        val temp = File(modelsDir, ".import-mtp-${System.nanoTime()}.part")
        val active = temp.canonicalPath
        ACTIVE_IMPORT_PATHS.add(active)
        try {
            if (reportedSize > 0 && modelsDir.usableSpace < reportedSize + SPACE_MARGIN) {
                throw IOException("Not enough free app storage to copy this MTP sidecar. Need about ${formatBytes(reportedSize)} plus working space.")
            }
            var copied = 0L
            resolver.openInputStream(uri).use { input ->
                if (input == null) throw IOException("Android could not open the selected MTP file. It may be offline or access may have expired.")
                temp.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (modelsDir.usableSpace < read + SPACE_MARGIN) {
                            throw IOException("Storage became insufficient while copying the MTP draft; the partial copy was removed.")
                        }
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, reportedSize)
                    }
                    output.fd.sync()
                }
            }
            if (!hasGgufMagic(temp)) throw IOException("The selected file does not have a valid GGUF header.")
            val summary = runCatching { GgufInspector.inspect(temp) }.getOrElse {
                throw IOException("Could not read the selected MTP GGUF metadata: ${it.message ?: "invalid header"}", it)
            }
            if (summary.kind == ModelKind.UNKNOWN || summary.architecture.isBlank() || summary.tensorCount <= 0L) {
                throw IOException("The selected file has a GGUF magic header but its model metadata/tensor directory is invalid or incomplete.")
            }
            if (summary.splitCount != null && summary.splitCount != 1L) {
                if (summary.splitCount > 1L) {
                    throw IOException("This MTP file declares split.count=${summary.splitCount}; split MTP sidecars are not supported yet.")
                }
                throw IOException("The MTP GGUF has an invalid split.count=${summary.splitCount}.")
            }
            if (summary.splitNo != null && summary.splitNo != 0L) {
                throw IOException("The MTP GGUF reports split.no=${summary.splitNo}; a standalone sidecar must be the first/only shard.")
            }
            if (!temp.renameTo(destination)) throw IOException("Could not finalize the copied MTP sidecar in app storage.")
            try {
                attachDraft(target.canonicalPath, destination.canonicalPath)
            } catch (t: Throwable) {
                destination.delete()
                throw t
            }
            ModelFile(destination.canonicalPath, destination.name, destination.length(), true)
        } catch (t: Throwable) {
            temp.delete()
            throw t
        } finally {
            ACTIVE_IMPORT_PATHS.remove(active)
        }
    }

    /**
     * UI-facing import entry point. Regular, single-file GGUF imports keep the existing code path;
     * split shards are staged incrementally so users may select one shard per session.
     */
    suspend fun importModelsIncrementally(
        resolver: ContentResolver,
        uris: List<Uri>,
        targetImportId: String? = null,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit
    ): ModelImportResult {
        if (uris.isEmpty()) throw IOException("No files were selected.")
        val rows = withContext(io) {
            uris.map { uri ->
                val (displayName, size) = queryNameAndSize(resolver, uri)
                val name = sanitizeFileName(displayName ?: "model.gguf")
                if (!name.endsWith(".gguf", ignoreCase = true)) throw IOException("Only .gguf files are supported (picked: $name).")
                Triple(uri, name, size)
            }
        }
        val parsed = rows.map { row -> parseShardName(row.second) }
        val shardRows = parsed.count { it != null }
        if (shardRows == 0) {
            if (targetImportId != null) throw IOException("The selected file is not a numbered shard for this incomplete model.")
            // Header inspection includes tensor descriptors, so keep it off the main thread even
            // for ordinary-looking filenames that might actually belong to a split model.
            val malformedSplit = withContext(io) {
                rows.firstNotNullOfOrNull { row ->
                    val summary = runCatching {
                        resolver.openInputStream(row.first)?.use { GgufInspector.inspect(it) }
                    }.getOrNull()
                    summary?.splitCount?.takeIf { it > 1L }?.let { row.second to it }
                }
            }
            if (malformedSplit != null) {
                throw IOException("${malformedSplit.first} declares split.count=${malformedSplit.second}, but its filename does not match llama.cpp's required -00001-of-000NN.gguf pattern. Rename/correct the shard set before importing.")
            }
            val imported = importModels(resolver, uris, onProgress)
            return ModelImportResult(imported, null, "Imported ${imported.size} GGUF file(s).")
        }
        if (shardRows != rows.size) throw IOException("Do not mix split-model shards with unrelated GGUF files in the same import. Import auxiliary mmproj/MTP files separately.")
        if (rows.any { isDraftName(it.second) || isProjectorName(it.second) }) {
            throw IOException("MTP/draft and mmproj files are auxiliary components, not ordinary text-model shards. Import them separately.")
        }
        val sources = rows.map { row ->
            SplitShardSource(row.second, row.third) {
                resolver.openInputStream(row.first) ?: throw IOException("Could not open ${row.second}. The storage provider may have revoked access or the file may be unavailable.")
            }
        }
        return importSplitShardSources(sources, targetImportId, onProgress)
    }

    /** Shared incremental importer, also exercised by JVM tests using local streams. */
    suspend fun importSplitShardSources(
        sources: List<SplitShardSource>,
        targetImportId: String? = null,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): ModelImportResult = withContext(io) {
        if (sources.isEmpty()) throw IOException("No split GGUF shards were selected.")
        val parsedRows = sources.map { source ->
            val safeName = sanitizeFileName(source.name)
            val parsed = parseShardName(safeName) ?: throw IOException("${source.name} does not use llama.cpp split naming (-00001-of-000NN.gguf).")
            if (isDraftName(safeName) || isProjectorName(safeName)) {
                throw IOException("${source.name} looks like an MTP/draft or mmproj auxiliary file, not a regular model shard. Import it separately.")
            }
            if (parsed.count < 2 || parsed.index !in 1..parsed.count) throw IOException("Invalid shard numbering in ${source.name}.")
            if (parsed.count > 65535) throw IOException("This llama.cpp build uses a 16-bit shard count and cannot load ${parsed.count} shards (${source.name}).")
            source.copy(name = safeName) to parsed
        }
        val first = parsedRows.first().second
        if (parsedRows.any { !it.second.prefix.equals(first.prefix, ignoreCase = true) || it.second.count != first.count }) {
            throw IOException("Selected shards have different filename prefixes or conflicting total shard counts.")
        }
        val duplicateIndexes = parsedRows.groupBy { it.second.index }.filterValues { it.size > 1 }.keys
        if (duplicateIndexes.isNotEmpty()) throw IOException("Duplicate shard(s) selected: ${duplicateIndexes.sorted().joinToString { it.toString().padStart(5, '0') }}.")
        val expectedId = splitImportId(first.prefix, first.count)
        if (targetImportId != null && targetImportId != expectedId) {
            throw IOException("These files do not match the selected incomplete import. Select shards named ${first.prefix}-NNNNN-of-${first.count}.gguf.")
        }
        val allPending = listIncompleteImportsInternal()
        val existingConflict = allPending.firstOrNull { it.displayName.equals(first.prefix, true) && it.expectedShardCount != first.count }
        if (existingConflict != null) {
            throw IOException("An incomplete import named '${existingConflict.displayName}' expects ${existingConflict.expectedShardCount} shards, but these filenames declare ${first.count}. Remove the old incomplete import or select the correct files.")
        }
        val readyPrefixConflict = modelsDir.listFiles().orEmpty().firstOrNull { file ->
            if (!file.isFile) false else parseShardName(file.name)?.let { existing ->
                existing.index == 1 && existing.prefix.equals(first.prefix, true) && existing.count != first.count
            } == true
        }
        if (readyPrefixConflict != null) {
            val existingCount = parseShardName(readyPrefixConflict.name)?.count
            throw IOException("A model named '${first.prefix}' already exists with $existingCount shards; the selected filenames declare ${first.count}. Remove or rename the existing split model before importing a conflicting shard total.")
        }
        val pendingDir = File(incompleteRoot(), expectedId)
        val manifest = File(pendingDir, "manifest.properties")
        val props = Properties()
        if (manifest.isFile) manifest.inputStream().use(props::load)
        if (props.isNotEmpty()) {
            val oldPrefix = props.getProperty("prefix", first.prefix)
            val oldCount = props.getProperty("expectedCount", "0").toIntOrNull() ?: 0
            if (!oldPrefix.equals(first.prefix, true)) throw IOException("The selected shards conflict with the existing incomplete import name.")
            if (oldCount != first.count) throw IOException("Conflicting shard totals: this import expects $oldCount, selected files declare ${first.count}.")
            if (props.getProperty("status") == "INVALID") {
                throw IOException("This incomplete import is marked invalid: ${props.getProperty("error", "remove it and retry")}. Remove it first to start a fresh import.")
            }
        }
        val dirExistsBefore = pendingDir.exists()
        if (!pendingDir.exists() && !pendingDir.mkdirs()) throw IOException("Could not create a private staging folder for this model.")
        val groupPrefix = props.getProperty("prefix") ?: first.prefix
        val targetNames = (1..first.count).map { shardFileName(groupPrefix, it, first.count) }
        val finalTargets = targetNames.map { File(modelsDir, it) }
        if (finalTargets.any { it.exists() }) {
            if (!manifest.exists()) pendingDir.deleteRecursively()
            throw IOException("A model with this split filename already exists in the model library. Rename or remove it before importing another copy.")
        }
        parsedRows.forEach { (_, parsed) ->
            val expectedName = shardFileName(groupPrefix, parsed.index, parsed.count)
            if (File(pendingDir, expectedName).exists()) {
                throw IOException("Shard ${parsed.index.toString().padStart(5, '0')} is already imported for '${groupPrefix}'. Duplicate shards are not overwritten. Remove the incomplete import to replace one.")
            }
            val tmpName = File(pendingDir, ".$expectedName.part")
            if (tmpName.exists()) {
                val path = runCatching { tmpName.canonicalPath }.getOrDefault(tmpName.absolutePath)
                if (path in ACTIVE_IMPORT_PATHS) throw IOException("Shard ${parsed.index} is already being imported by another operation.")
                tmpName.delete()
            }
        }
        val totalKnown = parsedRows.sumOf { it.first.sizeBytes.takeIf { n -> n > 0 } ?: 0L }
        if (totalKnown > 0 && modelsDir.usableSpace < totalKnown + SPACE_MARGIN) {
            if (!manifest.exists() && dirExistsBefore.not()) pendingDir.deleteRecursively()
            throw IOException("Not enough free storage for the selected shards: need at least ${formatBytes(totalKnown)} plus temporary space; ${formatBytes(modelsDir.usableSpace)} is free.")
        }
        if (props.isEmpty()) {
            props.setProperty("id", expectedId)
            props.setProperty("prefix", groupPrefix)
            props.setProperty("displayName", groupPrefix)
            props.setProperty("expectedCount", first.count.toString())
            props.setProperty("status", "WAITING")
            props.setProperty("createdAt", System.currentTimeMillis().toString())
        }
        val activeManifestPath = runCatching { manifest.canonicalPath }.getOrDefault(manifest.absolutePath)
        if (!ACTIVE_IMPORT_PATHS.add(activeManifestPath)) {
            throw IOException("This split model is already being imported by another operation.")
        }
        try {
        props.remove("error")
        persistManifest(manifest, props)
        var copiedThisBatch = 0L
        try {
            for ((source, parsed) in parsedRows.sortedBy { it.second.index }) {
                currentCoroutineContext().ensureActive()
                val finalName = shardFileName(groupPrefix, parsed.index, parsed.count)
                val target = File(pendingDir, finalName)
                val tmp = File(pendingDir, ".$finalName.part")
                val activeTempPath = runCatching { tmp.canonicalPath }.getOrDefault(tmp.absolutePath)
                if (!ACTIVE_IMPORT_PATHS.add(activeTempPath)) throw IOException("Shard ${parsed.index} is already being imported by another operation.")
                try {
                var copiedForThisFile = 0L
                source.openStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(4 * 1024 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (modelsDir.usableSpace < n + SPACE_MARGIN) throw IOException("Storage is nearly full while copying ${source.name}; this partial shard was discarded.")
                            output.write(buffer, 0, n)
                            copiedForThisFile += n
                            copiedThisBatch += n
                            onProgress(copiedThisBatch, totalKnown)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
                if (source.sizeBytes > 0 && copiedForThisFile != source.sizeBytes) {
                    throw IOException("${source.name} changed or was truncated while copying (expected ${formatBytes(source.sizeBytes)}, received ${formatBytes(copiedForThisFile)}).")
                }
                if (!hasGgufMagic(tmp)) throw IOException("${source.name} does not start with a valid GGUF header.")
                val summary = GgufInspector.inspect(tmp)
                if (summary.kind == ModelKind.UNKNOWN) {
                    throw IOException("${source.name} has an unreadable or unsupported GGUF metadata/tensor header.")
                }
                if (summary.tensorCount <= 0L) throw IOException("${source.name} has no tensor descriptors in its GGUF header; refusing to treat it as a complete shard.")
                if (parsed.index == 1 && summary.architecture.isBlank()) {
                    throw IOException("The first shard ${source.name} is missing general.architecture metadata.")
                }
                if (summary.splitTensorCount != null && summary.splitTensorCount < summary.tensorCount) {
                    throw IOException("${source.name} reports split.tensors.count=${summary.splitTensorCount}, below its own tensor count ${summary.tensorCount}.")
                }
                if (parsed.index == 1 && summary.splitCount != parsed.count.toLong()) {
                    throw IOException("The first shard ${source.name} declares split.count=${summary.splitCount ?: "missing"}; the filename declares ${parsed.count}.")
                }
                if (summary.splitCount != null && summary.splitCount != parsed.count.toLong()) {
                    throw IOException("${source.name} declares split.count=${summary.splitCount}, but the filename declares ${parsed.count}.")
                }
                val splitNo = summary.splitNo ?: throw IOException("${source.name} is missing the required GGUF split.no metadata.")
                val expectedSplitNo = parsed.index - 1L
                if (splitNo != expectedSplitNo) throw IOException("${source.name} reports split.no=$splitNo; this llama.cpp build expects $expectedSplitNo for filename index ${parsed.index}.")
                val savedArch = props.getProperty("architecture")
                if (savedArch != null && summary.architecture.isNotBlank() && savedArch != summary.architecture) {
                    throw IOException("${source.name} uses architecture '${summary.architecture}', but the existing shards use '$savedArch'. They are not the same model.")
                }
                val savedIdentity = props.stringPropertyNames().filter { it.startsWith("identity.") }.associate { it.removePrefix("identity.") to props.getProperty(it) }
                val mismatch = identityMapsMismatch(savedIdentity, summary.identityMetadata)
                if (mismatch != null) throw IOException("${source.name} has incompatible model metadata: $mismatch")
                if (savedArch == null && summary.architecture.isNotBlank()) props.setProperty("architecture", summary.architecture)
                summary.identityMetadata.forEach { (key, value) -> if (value.isNotBlank() && !props.containsKey("identity.$key")) props.setProperty("identity.$key", value) }
                if (!tmp.renameTo(target)) throw IOException("Could not finalize staged shard ${source.name}.")
                props.remove("error")
                props.setProperty("status", "WAITING")
                updatePendingManifest(props, pendingDir, groupPrefix, first.count)
                persistManifest(manifest, props)
                } finally {
                    ACTIVE_IMPORT_PATHS.remove(activeTempPath)
                }
            }
        } catch (t: Throwable) {
            // Keep completed shards for incremental continuation. Only remove numbered shard temp files;
            // manifest.properties.part is a transactional write and must never be swept by this cleanup.
            pendingDir.listFiles { file -> isSplitShardTempName(file.name) }?.forEach { file ->
                val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                if (path !in ACTIVE_IMPORT_PATHS) file.delete()
            }
            val retainedShards = listShardFiles(pendingDir, groupPrefix, first.count).isNotEmpty()
            if (!retainedShards && !dirExistsBefore) {
                // An invalid first selection must not create a misleading zero-shard import card.
                pendingDir.deleteRecursively()
            } else {
                props.setProperty("status", "WAITING")
                props.setProperty("error", t.message ?: "The split-shard import failed.")
                runCatching { updatePendingManifest(props, pendingDir, groupPrefix, first.count); persistManifest(manifest, props) }
            }
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (t is IOException) throw t
            throw IOException(t.message ?: "The split-shard import failed.", t)
        }

        val present = listShardFiles(pendingDir, groupPrefix, first.count)
        val presentIndices = present.mapNotNull { parseShardName(it.name)?.index }.toSet()
        if (presentIndices.size < first.count) {
            props.setProperty("status", "WAITING")
            props.remove("error")
            updatePendingManifest(props, pendingDir, groupPrefix, first.count)
            persistManifest(manifest, props)
            return@withContext ModelImportResult(
                emptyList(), incompleteFrom(pendingDir, props),
                "Saved ${presentIndices.size}/${first.count} shards for '$groupPrefix'. Missing: " + (1..first.count).filterNot { it in presentIndices }.joinToString { it.toString().padStart(5, '0') }
            )
        }
        return@withContext promoteStagedSplitGroup(pendingDir, groupPrefix, first.count, manifest, props)
        } finally {
            ACTIVE_IMPORT_PATHS.remove(activeManifestPath)
        }
    }

    /** Validate and promote one complete set. Files remain separate and are never concatenated. */
    private fun promoteStagedSplitGroup(
        pendingDir: File,
        groupPrefix: String,
        count: Int,
        manifest: File,
        props: Properties
    ): ModelImportResult {
        val firstShard = File(pendingDir, shardFileName(groupPrefix, 1, count))
        val validationIssue = splitSetIssue(firstShard)
        if (validationIssue != null) {
            props.setProperty("status", "INVALID")
            props.setProperty("error", validationIssue)
            persistManifest(manifest, props)
            return ModelImportResult(emptyList(), incompleteFrom(pendingDir, props), "All shard files are present, but validation failed: $validationIssue")
        }
        val finalTargets = (1..count).map { File(modelsDir, shardFileName(groupPrefix, it, count)) }
        if (finalTargets.any { it.exists() }) {
            val error = "A destination shard filename already exists; no staged shard was moved into the model library. Remove/rename the conflicting model first."
            props.setProperty("status", "CONFLICT")
            props.setProperty("error", error)
            persistManifest(manifest, props)
            return ModelImportResult(emptyList(), incompleteFrom(pendingDir, props), error)
        }

        // Persist a recoverable marker before the first atomic rename. If Android kills the app
        // mid-promotion, listIncompleteImportsInternal() restores moved shards or completes the set.
        props.remove("error")
        props.setProperty("status", "PROMOTING")
        updatePendingManifest(props, pendingDir, groupPrefix, count)
        persistManifest(manifest, props)

        val moved = mutableListOf<Pair<File, File>>()
        val loaded = File(modelsDir, shardFileName(groupPrefix, 1, count))
        try {
            for (i in 1..count) {
                val from = File(pendingDir, shardFileName(groupPrefix, i, count))
                val to = File(modelsDir, from.name)
                if (!from.renameTo(to)) throw IOException("Could not register shard ${from.name} in the model library.")
                moved += from to to
            }
            props.setProperty("status", "READY")
            props.setProperty("firstShardPath", loaded.canonicalPath)
            props.setProperty("presentShardIndices", (1..count).joinToString(","))
            props.setProperty("totalSizeBytes", logicalModelSize(loaded).toString())
            for (i in 1..count) props.setProperty("shard.$i.path", File(modelsDir, shardFileName(groupPrefix, i, count)).canonicalPath)
            persistManifest(completeManifestFile(loaded), props)
        } catch (t: Throwable) {
            runCatching { completeManifestFile(loaded).delete() }
            moved.asReversed().forEach { (from, to) -> if (to.exists()) to.renameTo(from) }
            props.setProperty("status", "CONFLICT")
            props.setProperty("error", t.message ?: "Could not finalize the split model.")
            runCatching { updatePendingManifest(props, pendingDir, groupPrefix, count); persistManifest(manifest, props) }
            return ModelImportResult(emptyList(), incompleteFrom(pendingDir, props), props.getProperty("error"))
        }
        pendingDir.deleteRecursively()
        val model = ModelFile(loaded.canonicalPath, loaded.name, logicalModelSize(loaded), true, props.getProperty("displayName", groupPrefix))
        return ModelImportResult(listOf(model), null, "Imported '${model.displayName}' as one model ($count shards, ${formatBytes(model.sizeBytes)}).")
    }

    /**
     * Recovers the short promotion window after a process death. A completed ready manifest means all
     * moves finished; otherwise any moved shards are returned to staging. If staging then contains the
     * full valid group, promotion is safely resumed. Returns true when the pending card was consumed.
     */
    private fun recoverInterruptedPromotion(dir: File, props: Properties, prefix: String, count: Int): Boolean {
        if (props.getProperty("status") != "PROMOTING") return false
        val manifest = File(dir, "manifest.properties")
        val manifestPath = runCatching { manifest.canonicalPath }.getOrDefault(manifest.absolutePath)
        if (!ACTIVE_IMPORT_PATHS.add(manifestPath)) return false
        try {
            val firstManaged = File(modelsDir, shardFileName(prefix, 1, count))
            val completed = completeManifestFile(firstManaged)
            if (completed.isFile) {
                val ready = Properties()
                runCatching { completed.inputStream().use(ready::load) }
                if (ready.getProperty("status") == "READY" &&
                    ready.getProperty("firstShardPath") == firstManaged.canonicalPath && hasCompleteShardFiles(firstManaged)) {
                    dir.deleteRecursively()
                    return true
                }
                // A stale completion manifest must not prevent recovery from the pending copies.
                if (ready.getProperty("firstShardPath") == firstManaged.canonicalPath) completed.delete()
            }

            var recoveryError: String? = null
            for (index in 1..count) {
                val name = shardFileName(prefix, index, count)
                val managed = File(modelsDir, name)
                val staged = File(dir, name)
                if (managed.isFile && !staged.exists()) {
                    if (!managed.renameTo(staged)) recoveryError = "Could not restore ${managed.name} from the model folder after an interrupted promotion."
                } else if (managed.exists() && staged.exists()) {
                    recoveryError = "Both staged and model-folder copies exist for ${managed.name}; manual cleanup is required to avoid overwriting a file."
                }
            }
            updatePendingManifest(props, dir, prefix, count)
            if (recoveryError != null) {
                props.setProperty("status", "CONFLICT")
                props.setProperty("error", recoveryError)
                persistManifest(manifest, props)
                return false
            }
            val stagedFirst = File(dir, shardFileName(prefix, 1, count))
            val present = listShardFiles(dir, prefix, count).mapNotNull { parseShardName(it.name)?.index }.toSet()
            if (present.size == count) {
                val issue = splitSetIssue(stagedFirst)
                if (issue != null) {
                    props.setProperty("status", "INVALID")
                    props.setProperty("error", issue)
                    persistManifest(manifest, props)
                    return false
                }
                val result = promoteStagedSplitGroup(dir, prefix, count, manifest, props)
                return result.importedModels.isNotEmpty() && result.incompleteImport == null
            }
            props.setProperty("status", "WAITING")
            props.remove("error")
            persistManifest(manifest, props)
            return false
        } finally {
            ACTIVE_IMPORT_PATHS.remove(manifestPath)
        }
    }

    /** A quick file-presence check keeps crash-interrupted shard sets out of the selector. */
    private fun hasCompleteShardFiles(first: File): Boolean {
        val match = SHARD.find(first.name) ?: return true
        val index = match.groupValues[1].toIntOrNull() ?: return false
        val count = match.groupValues[2].toIntOrNull() ?: return false
        if (index != 1 || count !in 2..65535) return false
        val prefix = first.name.substringBeforeLast(match.value)
        return (1..count).all { shardIndex ->
            val path = File(first.parentFile, shardFileName(prefix, shardIndex, count))
            path.isFile && path.length() > 0L
        }
    }

    private fun cleanupStaleSingleFileImportTemps() {
        modelsDir.listFiles { file -> file.isFile && file.name.matches(Regex("\\.import-\\d+-\\d+\\.part")) }
            .orEmpty().forEach { file ->
                val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                if (path !in ACTIVE_IMPORT_PATHS) file.delete()
            }
    }

    private fun incompleteRoot(): File = File(modelsDir, ".incomplete-split-imports")

    private fun completedManifestRoot(): File = File(modelsDir, ".model-manifests")

    private fun completeManifestFile(first: File): File {
        val shard = parseShardName(first.name)
        val prefix = shard?.let { first.name.substringBeforeLast(SHARD.find(first.name)!!.value) } ?: first.name.substringBeforeLast('.')
        return File(completedManifestRoot(), "${splitImportId(prefix, shard?.count)}.properties")
    }

    private fun completedDisplayNames(): Map<String, String> {
        val root = completedManifestRoot()
        if (!root.isDirectory) return emptyMap()
        return root.listFiles { f -> f.isFile && f.name.endsWith(".properties") }.orEmpty().mapNotNull { file ->
            val props = Properties()
            runCatching { file.inputStream().use(props::load) }.getOrNull() ?: return@mapNotNull null
            val path = props.getProperty("firstShardPath")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = props.getProperty("displayName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            path to name
        }.toMap()
    }

    private fun removeCompletedManifest(firstShardPath: String) {
        val file = File(firstShardPath)
        val manifest = completeManifestFile(file)
        if (!manifest.isFile) return
        val props = Properties()
        runCatching { manifest.inputStream().use(props::load) }
        if (props.getProperty("firstShardPath") == firstShardPath) manifest.delete()
    }

    private fun loadOrCreateReadyManifest(first: File, count: Int): Properties {
        val manifest = completeManifestFile(first)
        val props = Properties()
        if (manifest.isFile) manifest.inputStream().use(props::load)
        val shard = parseShardName(first.name)
        val prefix = shard?.let { first.name.substringBeforeLast(SHARD.find(first.name)!!.value) } ?: first.name.substringBeforeLast('.')
        props.setProperty("id", splitImportId(prefix, count))
        props.setProperty("prefix", prefix)
        props.setProperty("expectedCount", count.toString())
        props.setProperty("status", "READY")
        props.setProperty("firstShardPath", first.canonicalPath)
        props.setProperty("presentShardIndices", (1..count).joinToString(","))
        props.setProperty("totalSizeBytes", logicalModelSize(first).toString())
        for (i in 1..count) {
            val shardPath = File(first.parentFile, shardFileName(prefix, i, count)).canonicalPath
            props.setProperty("shard.$i.path", shardPath)
        }
        val summary = GgufInspector.inspect(first)
        if (summary.architecture.isNotBlank()) props.setProperty("architecture", summary.architecture)
        summary.identityMetadata.forEach { (key, value) -> if (value.isNotBlank()) props.setProperty("identity.$key", value) }
        return props
    }

    private fun listIncompleteImportsInternal(): List<IncompleteModelImport> {
        val root = incompleteRoot()
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory && f.name.matches(Regex("split-[a-f0-9]{24}")) }.orEmpty().mapNotNull { dir ->
            // Recover interrupted writes on next access. The manifest path is marked active while
            // importing, so an ordinary UI refresh cannot delete its in-progress atomic-write temp.
            val manifest = File(dir, "manifest.properties")
            val manifestPath = runCatching { manifest.canonicalPath }.getOrDefault(manifest.absolutePath)
            val manifestTemp = File(dir, "manifest.properties.part")
            if (manifestTemp.isFile && manifestPath !in ACTIVE_IMPORT_PATHS) manifestTemp.delete()
            // Only remove orphaned numbered-shard partials. Never sweep arbitrary .part files.
            dir.listFiles { f -> f.isFile && isSplitShardTempName(f.name) }?.forEach { file ->
                val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                if (path !in ACTIVE_IMPORT_PATHS) file.delete()
            }
            if (!manifest.isFile) return@mapNotNull null
            val props = Properties()
            runCatching { manifest.inputStream().use(props::load) }.getOrElse { return@mapNotNull null }
            val prefix = props.getProperty("prefix") ?: return@mapNotNull null
            val count = props.getProperty("expectedCount")?.toIntOrNull()?.takeIf { it in 2..65535 } ?: return@mapNotNull null
            if (recoverInterruptedPromotion(dir, props, prefix, count)) return@mapNotNull null
            incompleteFrom(dir, props, prefix, count)
        }
    }

    /** Persist per-shard managed paths, indices, and sizes alongside the on-disk staging files. */
    private fun updatePendingManifest(props: Properties, dir: File, prefix: String, count: Int) {
        val files = listShardFiles(dir, prefix, count)
        val indices = files.mapNotNull { parseShardName(it.name)?.index }.distinct().sorted()
        props.setProperty("presentShardIndices", indices.joinToString(","))
        props.setProperty("totalSizeBytes", files.sumOf { it.length() }.toString())
        val present = indices.toSet()
        props.stringPropertyNames().filter { it.matches(Regex("shard\\.\\d+\\.(path|sizeBytes)")) }.forEach { key ->
            val index = key.removePrefix("shard.").substringBefore('.').toIntOrNull()
            if (index == null || index !in present) props.remove(key)
        }
        files.forEach { file ->
            val index = parseShardName(file.name)?.index ?: return@forEach
            props.setProperty("shard.$index.path", file.canonicalPath)
            props.setProperty("shard.$index.sizeBytes", file.length().toString())
        }
    }

    private fun incompleteFrom(
        dir: File,
        props: Properties,
        prefix: String = props.getProperty("prefix", dir.name),
        count: Int = props.getProperty("expectedCount", "0").toIntOrNull() ?: 0
    ): IncompleteModelImport {
        val presentFiles = listShardFiles(dir, prefix, count)
        val indices = presentFiles.mapNotNull { parseShardName(it.name)?.index }.distinct().sorted()
        val total = presentFiles.sumOf { it.length() }
        val status = when (props.getProperty("status", "WAITING")) {
            "INVALID" -> "Validation failed"
            "CONFLICT" -> "Needs attention"
            else -> "Waiting for remaining files"
        }
        return IncompleteModelImport(
            props.getProperty("id", dir.name), props.getProperty("displayName", prefix), count,
            indices, total, status, props.getProperty("error")
        )
    }

    private fun listShardFiles(dir: File, prefix: String, count: Int): List<File> =
        dir.listFiles { f ->
            if (!f.isFile) false else {
                val shard = parseShardName(f.name)
                shard != null && shard.prefix.equals(prefix, true) && shard.count == count && f.length() > 0
            }
        }.orEmpty().sortedBy { parseShardName(it.name)?.index ?: Int.MAX_VALUE }

    private fun persistManifest(file: File, props: Properties) {
        val parent = file.parentFile ?: throw IOException("Manifest has no parent directory.")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create manifest directory: $parent")
        val tmp = File(parent, file.name + ".part")
        tmp.outputStream().use { props.store(it, "BigMoeOnEdge+ split GGUF model manifest") }
        if (!tmp.renameTo(file)) {
            file.outputStream().use { props.store(it, "BigMoeOnEdge+ incremental split GGUF manifest") }
            tmp.delete()
        }
    }

    private fun logicalModelSize(first: File): Long {
        val shard = parseShardName(first.name) ?: return first.length()
        val prefix = first.name.substringBeforeLast(SHARD.find(first.name)!!.value)
        return (1..shard.count).sumOf { i ->
            File(first.parentFile, shardFileName(prefix, i, shard.count)).takeIf { it.isFile }?.length() ?: 0L
        }.takeIf { it > 0 } ?: first.length()
    }

    private fun splitImportId(prefix: String, count: Int? = null): String {
        // Include shard total so distinct split layouts that reuse a base filename cannot overwrite
        // each other's manifests. Case is normalized because shard matching is case-insensitive.
        val identity = prefix.lowercase(Locale.ROOT) + (count?.let { "\nshards=$it" } ?: "")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return "split-" + digest.take(12).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun shardFileName(prefix: String, index: Int, count: Int): String =
        "$prefix-${index.toString().padStart(5, '0')}-of-${count.toString().padStart(5, '0')}.gguf"

    private fun identityMismatch(a: GgufSummary, b: GgufSummary): String? = identityMapsMismatch(a.identityMetadata, b.identityMetadata)

    private fun identityMapsMismatch(a: Map<String, String>, b: Map<String, String>): String? {
        // Later llama.cpp split shards can legitimately omit model-wide metadata; compare only keys present
        // on both sides. When a key is shared, a different value is a strong indication of a mixed set.
        val common = a.keys.intersect(b.keys)
        val conflict = common.firstOrNull { key -> a[key].orEmpty().isNotBlank() && b[key].orEmpty().isNotBlank() && a[key] != b[key] }
        return conflict?.let { key -> "$key differs ('${a[key]}' versus '${b[key]}')" }
    }

    private fun parseShardName(fileName: String): ParsedShardName? {
        val match = SHARD.find(fileName) ?: return null
        val index = match.groupValues[1].toIntOrNull() ?: return null
        val count = match.groupValues[2].toIntOrNull() ?: return null
        val prefix = fileName.substringBeforeLast(match.value)
        if (prefix.isBlank()) return null
        return ParsedShardName(prefix, index, count)
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
        private val ACTIVE_IMPORT_PATHS = ConcurrentHashMap.newKeySet<String>()
        private const val SPACE_MARGIN = 256L * 1024 * 1024
        private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

        private val SHARD = Regex("-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)
        private val SPLIT_SHARD_TEMP = Regex("\\..+-\\d{5}-of-\\d{5}\\.gguf\\.part$", RegexOption.IGNORE_CASE)

        private fun isSplitShardTempName(name: String): Boolean = SPLIT_SHARD_TEMP.matches(name)

        fun isDraftName(fileName: String): Boolean {
            val n = fileName.lowercase()
            return n.startsWith("mtp-") || n.startsWith("mtp_") || n.contains("-mtp-") || n.contains("_mtp_") || n.contains("-shared-mtp")
        }

        fun isProjectorName(fileName: String): Boolean = fileName.contains("mmproj", ignoreCase = true) && fileName.endsWith(".gguf", ignoreCase = true)

        /** Picks a likely projector by normalized-name prefix, without treating quantisation suffixes as identity. */
        fun suggestProjector(model: ModelFile, projectors: List<ModelFile>): ModelFile? {
            if (projectors.isEmpty()) return null
            val modelKey = normalizeProjectorKey(model.displayName)
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
