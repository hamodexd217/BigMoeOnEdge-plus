package com.bigmoe.onedge.data.local

import com.bigmoe.onedge.core.AutoSave
import com.bigmoe.onedge.core.AutoTier
import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.core.TunedValues
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSaveStoreProfileTest {
    private fun save() = AutoSave(
        id = "profile-save-1", name = "Balanced", tier = AutoTier.BALANCED,
        kind = "DENSE", modelName = "same.gguf", createdAt = 1L,
        values = TunedValues(
            contextLength = 4096, threadCount = 4, batchThreadCount = 4, batchWidth = 512,
            cacheMb = 2000, ioThreads = 4, oDirect = true, overlap = true, ioTwoWave = false,
            nExpertUsed = 0, dropColdPct = 75, dropRenorm = true, substitutePct = 0,
            prefetchLayers = 0, predictPrefetch = false, predictSpecMax = 0, routeAhead = 0,
            spec = SpecSource.OFF, draftMax = 3, npuPrefill = false
        ), estTps = 0.0
    )

    @Test fun profileBindingPersistsAndDeletingSaveClearsIt() = runBlocking {
        val dir = Files.createTempDirectory("autosave-profiles").toFile()
        try {
            val store = AutoSaveStore(dir)
            val saved = save()
            assertTrue(store.upsert(saved))
            assertTrue(store.setModelProfile("model-sha256-abc", saved.id))
            assertEquals(saved.id, store.profileFor("model-sha256-abc")?.id)

            val restored = AutoSaveStore(dir)
            restored.load()
            assertEquals(saved.id, restored.profileFor("model-sha256-abc")?.id)
            assertTrue(restored.setAutoApplyModelProfilesEnabled(false))
            val nextLaunch = AutoSaveStore(dir)
            nextLaunch.load()
            assertFalse(nextLaunch.autoApplyModelProfilesEnabled.value)
            assertTrue(nextLaunch.delete(saved.id).let { true })
            nextLaunch.load()
            assertNull(nextLaunch.profileFor("model-sha256-abc"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
