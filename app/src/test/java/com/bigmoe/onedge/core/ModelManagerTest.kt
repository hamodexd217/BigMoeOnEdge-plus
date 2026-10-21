package com.bigmoe.onedge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelManagerTest {

    private val cfg = testLoadConfig()
    private val gib = 1024L * 1024 * 1024

    private fun tempGguf(header: ByteArray = "GGUF".toByteArray(), size: Int = 64): File =
        File.createTempFile("model", ".gguf").apply {
            deleteOnExit()
            writeBytes(header + ByteArray(size))
        }

    @Test
    fun sanitizeFileName() {
        assertEquals("model.gguf", ModelManager.sanitizeFileName("../../etc/model.gguf"))
        assertEquals("a_b.gguf", ModelManager.sanitizeFileName("a/b\\a_b.gguf".substringAfterLast('\\')))
        assertEquals("model.gguf", ModelManager.sanitizeFileName(""))
        assertEquals("model.gguf", ModelManager.sanitizeFileName("..."))
        assertEquals("Qwen3-30B-A3B-Q4_K_M.gguf", ModelManager.sanitizeFileName("Qwen3-30B-A3B-Q4_K_M.gguf"))
    }

    @Test
    fun formatBytes() {
        assertEquals("18.6 GB", ModelManager.formatBytes((18.6 * gib).toLong()))
        assertEquals("512 MB", ModelManager.formatBytes(512L * 1024 * 1024))
    }

    @Test
    fun missingOrNonGgufFileIsAnError() {
        assertFalse(ModelManager.adviseLoad(File("/nope.gguf"), 12 * gib, cfg).canLoad)
        assertFalse(ModelManager.adviseLoad(tempGguf(header = "NOPE".toByteArray()), 12 * gib, cfg).canLoad)
    }

    @Test
    fun validFileOnBigDeviceLoadsWithoutRamWarning() {
        val a = ModelManager.adviseLoad(tempGguf(), 12 * gib, cfg)
        assertTrue(a.canLoad)
        assertFalse(a.warnings.any { it.contains("GB of RAM") })
    }

    @Test
    fun smallRamAndRiskyConfigsWarnButDoNotBlock() {
        val risky = cfg.copy(cacheMode = CacheMode.FIXED, cacheMb = 6000, denseWeights = DenseWeights.PINNED)
        val a = ModelManager.adviseLoad(tempGguf(), 6L * gib - 1, risky)
        assertTrue(a.canLoad)
        assertTrue(a.warnings.any { it.contains("GB of RAM") })
        assertTrue(a.warnings.any { it.contains("60%") })
        assertTrue(a.warnings.any { it.contains("Pinned") })
    }

    @Test
    fun deleteOnlyInsideTheModelsDirectory() = kotlinx.coroutines.runBlocking {
        val dir = File.createTempFile("models", "").apply { delete(); mkdirs(); deleteOnExit() }
        val inside = File(dir, "a.gguf").apply { writeText("x") }
        val outside = File.createTempFile("outside", ".gguf").apply { deleteOnExit() }
        val mm = ModelManager(dir)
        assertFalse(mm.delete(outside.path))
        assertTrue(outside.exists())
        assertTrue(mm.delete(inside.path))
        assertFalse(inside.exists())
    }

    @Test
    fun listsOnlyGgufFilesSorted() = kotlinx.coroutines.runBlocking {
        val dir = File.createTempFile("models", "").apply { delete(); mkdirs(); deleteOnExit() }
        File(dir, "b.gguf").writeText("1"); File(dir, "A.GGUF").writeText("22"); File(dir, "c.txt").writeText("3"); File(dir, "d.gguf.part").writeText("4")
        val names = ModelManager(dir).listModels().map { it.name }
        assertEquals(listOf("A.GGUF", "b.gguf"), names)
    }

    @Test
    fun shardDetection() {
        assertFalse(ModelManager.isLaterShard("model-00001-of-00003.gguf"))
        assertTrue(ModelManager.isLaterShard("model-00002-of-00003.gguf"))
        assertTrue(ModelManager.isLaterShard("Model-00003-OF-00003.GGUF"))
        assertFalse(ModelManager.isLaterShard("plain.gguf"))
    }

    @Test
    fun splitSetValidationListsMissingShardsAndAcceptsCompleteSet() {
        val dir = File.createTempFile("splitmodels", "").apply { delete(); mkdirs(); deleteOnExit() }
        val first = File(dir, "Qwen-00001-of-00003.gguf").apply { writeMinimalGguf(0, 3) }
        val missing = ModelManager(dir).splitSetIssue(first)
        assertTrue(missing.orEmpty().contains("00002-of-00003.gguf"))
        assertTrue(missing.orEmpty().contains("00003-of-00003.gguf"))
        File(dir, "Qwen-00002-of-00003.gguf").writeMinimalGguf(1, 3)
        File(dir, "Qwen-00003-of-00003.gguf").writeMinimalGguf(2, 3)
        assertEquals(null, ModelManager(dir).splitSetIssue(first))
    }

    @Test
    fun renamingSplitModelChangesDisplayNameButPreservesShardFiles() = kotlinx.coroutines.runBlocking {
        val dir = File.createTempFile("splitrename", "").apply { delete(); mkdirs(); deleteOnExit() }
        val first = File(dir, "Qwen-00001-of-00002.gguf").apply { writeMinimalGguf(0, 2) }
        val second = File(dir, "Qwen-00002-of-00002.gguf").apply { writeMinimalGguf(1, 2) }
        val manager = ModelManager(dir)
        val renamed = manager.rename(first.path, "Flash-Next")
        assertEquals("Qwen-00001-of-00002.gguf", renamed.name)
        assertEquals("Flash-Next", renamed.displayName)
        assertTrue(first.isFile)
        assertTrue(second.isFile)
        val listed = manager.listModels().single()
        assertEquals("Flash-Next", listed.displayName)
        assertEquals(first.canonicalPath, listed.path)
    }

    @Test
    fun deleteFirstShardDeletesAllExistingShards() = kotlinx.coroutines.runBlocking {
        val dir = File.createTempFile("splitdelete", "").apply { delete(); mkdirs(); deleteOnExit() }
        val first = File(dir, "Qwen-00001-of-00002.gguf").apply { writeText("one") }
        val second = File(dir, "Qwen-00002-of-00002.gguf").apply { writeText("two") }
        assertTrue(ModelManager(dir).delete(first.path))
        assertFalse(first.exists())
        assertFalse(second.exists())
    }

    private fun File.writeMinimalGguf(
        splitNo: Long? = null,
        splitCount: Long? = null,
        architecture: String = "qwen4exp",
        modelName: String? = null,
        fullMetadata: Boolean = false
    ) {
        val hasModelMetadata = splitNo == null || splitNo == 0L || fullMetadata
        val entries = (if (hasModelMetadata) 1 + (if (modelName != null) 1 else 0) else 0) +
            (if (splitNo != null) 1 else 0) + (if (splitCount != null) 1 else 0) + (if (splitCount != null) 1 else 0)
        val b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        b.put("GGUF".toByteArray())
        b.putInt(3); b.putLong(1); b.putLong(entries.toLong())
        fun str(v: String) { val bytes = v.toByteArray(Charsets.UTF_8); b.putLong(bytes.size.toLong()); b.put(bytes) }
        if (hasModelMetadata) {
            str("general.architecture"); b.putInt(8); str(architecture)
            if (modelName != null) { str("general.name"); b.putInt(8); str(modelName) }
        }
        if (splitNo != null) { str("split.no"); b.putInt(10); b.putLong(splitNo) }
        if (splitCount != null) {
            str("split.count"); b.putInt(10); b.putLong(splitCount)
            str("split.tensors.count"); b.putInt(5); b.putInt(splitCount.toInt())
        }
        str("test.tensor"); b.putInt(1); b.putLong(1); b.putInt(0); b.putLong(0)
        outputStream().use { it.write(b.array(), 0, b.position()); it.write(ByteArray(8)) }
    }

    private fun tempDirectory(prefix: String): File =
        File.createTempFile(prefix, "").apply { delete(); mkdirs(); deleteOnExit() }

    private fun shardSource(file: File): SplitShardSource =
        SplitShardSource(file.name, file.length()) { file.inputStream() }

    private fun createSplitShards(
        dir: File,
        prefix: String,
        count: Int,
        architecture: String = "qwen4exp",
        modelName: String = "test model"
    ): List<File> = (1..count).map { index ->
        File(dir, "$prefix-${index.toString().padStart(5, '0')}-of-${count.toString().padStart(5, '0')}.gguf")
            .apply { writeMinimalGguf((index - 1).toLong(), count.toLong(), architecture, modelName) }
    }

    private fun expectIOException(block: () -> Unit): java.io.IOException {
        try {
            block()
        } catch (e: java.io.IOException) {
            return e
        }
        throw AssertionError("Expected IOException")
    }

    @Test
    fun incrementalImportSurvivesNewManagerInstanceAndRegistersOneLogicalModel() = kotlinx.coroutines.runBlocking {
        val modelsDir = tempDirectory("modinc")
        val sourceDir = tempDirectory("srcinc")
        val sourceShards = createSplitShards(sourceDir, "Qwen-Next", 3)
        val firstManager = ModelManager(modelsDir)
        val first = firstManager.importSplitShardSources(listOf(shardSource(sourceShards[1])))
        assertTrue(first.importedModels.isEmpty())
        assertEquals(listOf(2), first.incompleteImport?.presentShardIndices)
        assertEquals(listOf(1, 3), first.incompleteImport?.missingShardIndices)
        assertTrue(firstManager.listModels().isEmpty())

        val managerAfterRestart = ModelManager(modelsDir)
        assertEquals(listOf(2), managerAfterRestart.listIncompleteImports().single().presentShardIndices)
        val finished = managerAfterRestart.importSplitShardSources(listOf(shardSource(sourceShards[2]), shardSource(sourceShards[0])))
        assertEquals(1, finished.importedModels.size)
        assertEquals("Qwen-Next", finished.importedModels.single().displayName)
        assertEquals(sourceShards.sumOf { it.length() }, finished.importedModels.single().sizeBytes)
        assertEquals(1, managerAfterRestart.listModels().size)
        assertEquals("Qwen-Next", managerAfterRestart.listModels().single().displayName)
        assertTrue(File(modelsDir, "Qwen-Next-00002-of-00003.gguf").isFile)
        assertTrue(managerAfterRestart.listIncompleteImports().isEmpty())
        val completedManifest = File(modelsDir, ".model-manifests").listFiles()!!.single()
        val manifestProperties = java.util.Properties().also { completedManifest.inputStream().use(it::load) }
        assertEquals("READY", manifestProperties.getProperty("status"))
        assertEquals("1,2,3", manifestProperties.getProperty("presentShardIndices"))
        assertTrue(manifestProperties.getProperty("shard.3.path").orEmpty().endsWith("Qwen-Next-00003-of-00003.gguf"))
    }

    @Test
    fun completeFourShardImportAcceptsOutOfOrderSelection() = kotlinx.coroutines.runBlocking {
        val modelsDir = tempDirectory("modfour")
        val sourceDir = tempDirectory("srcfour")
        val shards = createSplitShards(sourceDir, "BigModel", 4)
        val result = ModelManager(modelsDir).importSplitShardSources(shards.reversed().map(::shardSource))
        assertEquals(1, result.importedModels.size)
        assertEquals(4, (1..4).count { File(modelsDir, "BigModel-${it.toString().padStart(5, '0')}-of-00004.gguf").isFile })
    }

    @Test
    fun duplicateShardIsRejectedWithoutOverwritingExistingPartialShard() = kotlinx.coroutines.runBlocking {
        val modelsDir = tempDirectory("moddup")
        val sourceDir = tempDirectory("srcdup")
        val shards = createSplitShards(sourceDir, "DupModel", 2)
        val manager = ModelManager(modelsDir)
        val e = expectIOException { kotlinx.coroutines.runBlocking { manager.importSplitShardSources(listOf(shardSource(shards[0]), shardSource(shards[0]))) } }
        assertTrue(e.message.orEmpty().contains("Duplicate shard"))
        val first = manager.importSplitShardSources(listOf(shardSource(shards[0])))
        assertTrue(first.incompleteImport != null)
        val duplicate = expectIOException { kotlinx.coroutines.runBlocking { manager.importSplitShardSources(listOf(shardSource(shards[0]))) } }
        assertTrue(duplicate.message.orEmpty().contains("already imported"))
    }

    @Test
    fun conflictingShardTotalIsRejectedWhenSamePrefixIsAlreadyComplete() = kotlinx.coroutines.runBlocking {
        val modelsDir = tempDirectory("modreadyconflict")
        val sourceDir = tempDirectory("srcreadyconflict")
        val two = createSplitShards(sourceDir, "SamePrefix", 2)
        val three = createSplitShards(sourceDir, "SamePrefix", 3)
        val manager = ModelManager(modelsDir)
        val complete = manager.importSplitShardSources(two.map(::shardSource))
        assertEquals(1, complete.importedModels.size)
        val error = expectIOException { kotlinx.coroutines.runBlocking { manager.importSplitShardSources(listOf(shardSource(three[0]))) } }
        assertTrue(error.message.orEmpty().contains("already exists with 2 shards"))
        assertEquals(1, manager.listModels().size)
    }

    @Test
    fun conflictingTotalsAreRejected() = kotlinx.coroutines.runBlocking {
        val modelsDir = tempDirectory("modconf")
        val sourceDir = tempDirectory("srcconf")
        val three = createSplitShards(sourceDir, "Conflict", 3)
        val four = File(sourceDir, "Conflict-00002-of-00004.gguf").apply { writeMinimalGguf(1, 4, modelName = "test model") }
        val manager = ModelManager(modelsDir)
        manager.importSplitShardSources(listOf(shardSource(three[0])))
        val e = expectIOException { kotlinx.coroutines.runBlocking { manager.importSplitShardSources(listOf(shardSource(four))) } }
        assertTrue(e.message.orEmpty().contains("expects 3 shards") || e.message.orEmpty().contains("expects 3"))
    }

    @Test
    fun incompatibleIdentityMetadataDoesNotCombineSimilarNames() = kotlinx.coroutines.runBlocking {
        val modelsDir = tempDirectory("modmeta")
        val sourceDir = tempDirectory("srcmeta")
        val first = File(sourceDir, "Similar-00001-of-00002.gguf").apply { writeMinimalGguf(0, 2, modelName = "Model A") }
        val other = File(sourceDir, "Similar-00002-of-00002.gguf").apply { writeMinimalGguf(1, 2, modelName = "Model B", fullMetadata = true) }
        val manager = ModelManager(modelsDir)
        manager.importSplitShardSources(listOf(shardSource(first)))
        val e = expectIOException { kotlinx.coroutines.runBlocking { manager.importSplitShardSources(listOf(shardSource(other))) } }
        assertTrue(e.message.orEmpty().contains("general.name"))
        assertEquals(listOf(1), manager.listIncompleteImports().single().presentShardIndices)
    }

    @Test
    fun incompleteSplitIsNotLoadableAndCanBeRemovedSafely() = kotlinx.coroutines.runBlocking {
        val modelsDir = tempDirectory("modload")
        val sourceDir = tempDirectory("srcload")
        val shards = createSplitShards(sourceDir, "WaitModel", 2)
        val manager = ModelManager(modelsDir)
        val incomplete = manager.importSplitShardSources(listOf(shardSource(shards[0]))).incompleteImport!!
        val stagedFirst = File(modelsDir, ".incomplete-split-imports/${incomplete.id}/${shards[0].name}")
        assertFalse(ModelManager.adviseLoad(stagedFirst, 16 * gib, cfg).canLoad)
        assertTrue(manager.removeIncompleteImport(incomplete.id))
        assertTrue(manager.listIncompleteImports().isEmpty())
        assertTrue("Only app-owned copies are removed", shards[0].isFile)
    }

    @Test
    fun inaccessibleAdditionalShardKeepsEarlierShardAndPersistsErrorState() = kotlinx.coroutines.runBlocking {
        val modelsDir = tempDirectory("modaccess")
        val sourceDir = tempDirectory("srcaccess")
        val shards = createSplitShards(sourceDir, "AccessModel", 2)
        val manager = ModelManager(modelsDir)
        val firstResult = manager.importSplitShardSources(listOf(shardSource(shards[0])))
        val pending = firstResult.incompleteImport!!
        val blocked = SplitShardSource(shards[1].name, shards[1].length()) {
            throw java.io.FileNotFoundException("SAF permission revoked")
        }
        val error = expectIOException {
            kotlinx.coroutines.runBlocking {
                manager.importSplitShardSources(listOf(blocked), targetImportId = pending.id)
            }
        }
        assertTrue(error.message.orEmpty().contains("SAF permission revoked"))
        val stillPending = manager.listIncompleteImports().single()
        assertEquals(listOf(1), stillPending.presentShardIndices)
        assertTrue(stillPending.validationMessage.orEmpty().contains("SAF permission revoked"))
        val manifest = File(modelsDir, ".incomplete-split-imports/${pending.id}/manifest.properties")
        val properties = java.util.Properties().also { manifest.inputStream().use(it::load) }
        assertEquals("1", properties.getProperty("presentShardIndices"))
        assertTrue(properties.getProperty("shard.1.path").orEmpty().endsWith(shards[0].name))
        assertTrue(shards[1].isFile) // An original SAF/source file is never removed by a failed import.
    }


    @Test
    fun interruptedPromotionIsRecoveredAndCompletedBeforeModelListing() = kotlinx.coroutines.runBlocking {
        val modelsDir = tempDirectory("modrecoverpromote")
        val sourceDir = tempDirectory("srcrecoverpromote")
        val shards = createSplitShards(sourceDir, "RecoverModel", 2)
        val manager = ModelManager(modelsDir)
        val pending = manager.importSplitShardSources(listOf(shardSource(shards[0]))).incompleteImport!!
        val pendingDir = File(modelsDir, ".incomplete-split-imports/${pending.id}")
        val stagedSecond = File(pendingDir, shards[1].name)
        shards[1].copyTo(stagedSecond)
        val stagedFirst = File(pendingDir, shards[0].name)
        val managedFirst = File(modelsDir, shards[0].name)
        assertTrue(stagedFirst.renameTo(managedFirst))
        val manifest = File(pendingDir, "manifest.properties")
        val props = java.util.Properties().also { manifest.inputStream().use(it::load) }
        props.setProperty("status", "PROMOTING")
        manifest.outputStream().use { props.store(it, "simulate process death during split promotion") }

        assertTrue(manager.listModels().isEmpty())
        assertTrue(manager.listIncompleteImports().isEmpty())
        assertEquals(1, manager.listModels().size)
        assertTrue(File(modelsDir, shards[0].name).isFile)
        assertTrue(File(modelsDir, shards[1].name).isFile)
        assertTrue(manager.listIncompleteImports().isEmpty())
    }

    @Test
    fun staleManifestTempIsCleanedWithoutRemovingThePendingImport() = kotlinx.coroutines.runBlocking {
        val modelsDir = tempDirectory("modmanifesttemp")
        val sourceDir = tempDirectory("srcmanifesttemp")
        val first = createSplitShards(sourceDir, "ManifestTemp", 2).first()
        val manager = ModelManager(modelsDir)
        val pending = manager.importSplitShardSources(listOf(shardSource(first))).incompleteImport!!
        val pendingDir = File(modelsDir, ".incomplete-split-imports/${pending.id}")
        val manifest = File(pendingDir, "manifest.properties")
        val staleTemp = File(pendingDir, "manifest.properties.part").apply { writeText("partial manifest") }
        assertTrue(manifest.isFile)
        assertTrue(manager.listIncompleteImports().single().presentShardIndices == listOf(1))
        assertFalse(staleTemp.exists())
        assertTrue(manifest.isFile)
    }

    @Test
    fun oneBasedSplitMetadataIsRejectedBecauseNativeLoaderRequiresZeroBasedIndexes() = kotlinx.coroutines.runBlocking {
        val modelsDir = tempDirectory("modbase")
        val sourceDir = tempDirectory("srcbase")
        val first = File(sourceDir, "OneBased-00001-of-00002.gguf").apply { writeMinimalGguf(1, 2, modelName = "one based", fullMetadata = true) }
        val e = expectIOException { kotlinx.coroutines.runBlocking { ModelManager(modelsDir).importSplitShardSources(listOf(shardSource(first))) } }
        assertTrue(e.message.orEmpty().contains("expects 0"))
        assertTrue(ModelManager(modelsDir).listIncompleteImports().isEmpty())
    }

    @Test
    fun malformedTensorDirectoryIsRejectedRatherThanSavedAsShard() = kotlinx.coroutines.runBlocking {
        val modelsDir = tempDirectory("modtensor")
        val sourceDir = tempDirectory("srctensor")
        val malformed = File(sourceDir, "Malformed-00001-of-00002.gguf").apply {
            writeMinimalGguf(0, 2, modelName = "broken")
            // Truncate after the metadata block, leaving the declared tensor directory incomplete.
            val bytes = readBytes()
            writeBytes(bytes.copyOf(bytes.size - 20))
        }
        val e = expectIOException { kotlinx.coroutines.runBlocking { ModelManager(modelsDir).importSplitShardSources(listOf(shardSource(malformed))) } }
        assertTrue(e.message.orEmpty().contains("header") || e.message.orEmpty().contains("GGUF"))
        assertTrue(ModelManager(modelsDir).listIncompleteImports().isEmpty())
    }

    @Test
    fun insufficientStorageIsReportedBeforeReadingShardData() = kotlinx.coroutines.runBlocking {
        val modelsDir = tempDirectory("modspace")
        val source = SplitShardSource("Large-00001-of-00002.gguf", 100L * 1024 * 1024 * 1024 * 1024) {
            throw AssertionError("Should not try to read when preflight storage check fails")
        }
        val e = expectIOException { kotlinx.coroutines.runBlocking { ModelManager(modelsDir).importSplitShardSources(listOf(source)) } }
        assertTrue(e.message.orEmpty().contains("free storage"))
    }

    @Test
    fun existingPathWithSpacesRegistersWithoutCopying() = kotlinx.coroutines.runBlocking {
        val dir = File.createTempFile("models with spaces", "").apply { delete(); mkdirs(); deleteOnExit() }
        val source = File(dir, "Qwen 3.5 model Q4.gguf").apply { writeBytes("GGUF".toByteArray() + ByteArray(32)) }
        val appDir = File.createTempFile("app models", "").apply { delete(); mkdirs(); deleteOnExit() }
        val manager = ModelManager(appDir)
        val registered = manager.registerExistingPath(source.absolutePath)
        assertEquals(source.canonicalPath, registered.path)
        assertFalse(registered.inAppStorage)
        assertTrue(source.isFile)
    }

    @Test
    fun relativeExistingPathGetsActionableError() = kotlinx.coroutines.runBlocking {
        val dir = File.createTempFile("appmodels", "").apply { delete(); mkdirs(); deleteOnExit() }
        val manager = ModelManager(dir)
        try {
            manager.registerExistingPath("model.gguf")
            throw AssertionError("relative path should be rejected")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("absolute filesystem path"))
        }
    }

    @Test
    fun existingPathsRegisterWithoutTakingOwnershipAndCanBeUnregistered() = kotlinx.coroutines.runBlocking {
        val dir = File.createTempFile("models", "").apply { delete(); mkdirs(); deleteOnExit() }
        val sourceDir = File.createTempFile("source", "").apply { delete(); mkdirs(); deleteOnExit() }
        val source = File(sourceDir, "Qwen.gguf").apply { writeBytes("GGUF".toByteArray() + ByteArray(32)) }
        val manager = ModelManager(dir)
        val registered = manager.registerExistingPath(source.path)
        assertFalse(registered.inAppStorage)
        assertTrue(manager.listModels().any { it.path == source.canonicalPath })
        assertTrue(manager.delete(source.path))
        assertTrue("unregister must never delete user-owned source files", source.exists())
    }

    @Test
    fun sidecarIsNotListedAsTargetAndCanBeAttachedByModelPath() = kotlinx.coroutines.runBlocking {
        val dir = File.createTempFile("models", "").apply { delete(); mkdirs(); deleteOnExit() }
        val model = File(dir, "Qwen.gguf").apply { writeBytes("GGUF".toByteArray() + ByteArray(32)) }
        val draft = File(dir, "mtp-Qwen-shared-Q8_0.gguf").apply { writeBytes("GGUF".toByteArray() + ByteArray(32)) }
        val manager = ModelManager(dir)
        assertTrue(manager.listModels().none { it.path == draft.canonicalPath })
        assertEquals(draft.name, manager.listDrafts().single().name)
        manager.attachDraft(model.path, draft.path)
        assertEquals(draft.canonicalPath, manager.draftForModel(model.path))
        manager.attachDraft(model.path, null)
        assertEquals(null, manager.draftForModel(model.path))
    }

    @Test
    fun projectorNamesAndMatching() {
        val model = ModelFile("/models/Qwen3-VL-30B-A3B-Q4_K_M.gguf", "Qwen3-VL-30B-A3B-Q4_K_M.gguf", 1)
        val p1 = ModelFile("/models/mmproj-Qwen3-VL-30B-A3B-F16.gguf", "mmproj-Qwen3-VL-30B-A3B-F16.gguf", 2)
        val p2 = ModelFile("/models/mmproj-other-F16.gguf", "mmproj-other-F16.gguf", 2)
        assertTrue(ModelManager.isProjectorName(p1.name))
        assertFalse(ModelManager.isProjectorName(model.name))
        assertEquals(p1, ModelManager.suggestProjector(model, listOf(p2, p1)))
        assertEquals(null, ModelManager.suggestProjector(ModelFile("x", "tiny.gguf", 1), listOf(p2)))
    }

    @Test
    fun genericProjectorIsNeverPickedByName() {
        val generic = ModelFile("/models/mmproj-F16.gguf", "mmproj-F16.gguf", 2)
        val other = ModelFile("/models/Qwen3.8-Flash-Next-Q4_K_M.gguf", "Qwen3.8-Flash-Next-Q4_K_M.gguf", 1)
        assertEquals(null, ModelManager.suggestProjector(other, listOf(generic)))
        val named = ModelFile("/models/mmproj-Qwen3.8-Flash-Next-F16.gguf", "mmproj-Qwen3.8-Flash-Next-F16.gguf", 2)
        assertEquals(named, ModelManager.suggestProjector(other, listOf(generic, named)))
    }
}
