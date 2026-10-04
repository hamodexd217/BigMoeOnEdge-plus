package com.bigmoe.onedge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelManagerTest {

    private val cfg = testLoadConfig()
    private val gib = 1024L * 1024 * 1024

    private fun tempGguf(header: ByteArray = "GGUF".toByteArray(), size: Int = 64): File =
        File.createTempFile("m", ".gguf").apply {
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
