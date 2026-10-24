package com.bigmoe.onedge.core

import com.bigmoe.onedge.data.local.datastore.CACHE_AUTO_MB
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the wire format shared with native-bridge.cpp (IntIdx) and the defaults taken from the original app. */
class LoadConfigTest {

    private val d = EngineSettings().toLoadConfig()

    @Test
    fun guessAheadForcesGreedyDecoding() {
        assertEquals(0.7f, EngineSettings().toLoadConfig().temperature)
        val ngram = EngineSettings(spec = SpecSource.NGRAM, temperature = 0.7f).toLoadConfig()
        assertEquals(0f, ngram.temperature)
    }

    @Test
    fun arrayLengthsMatchTheNativeEnums() {
        assertEquals(LoadConfig.INT_COUNT, d.toIntArray().size)
        assertEquals(LoadConfig.FLOAT_COUNT, d.toFloatArray().size)
    }

    @Test
    fun indexLayoutMatchesNativeBridge() {
        val c = d.copy(
            nCtx = 8192, nThreads = 6, topK = 33, cacheMode = CacheMode.FIXED, cacheMb = 1750, cacheFloorMb = 11,
            cacheCeilMb = 4000, ioThreads = 8, denseWeights = DenseWeights.PINNED, nExpertUsed = 3, dropColdPct = 50,
            substitutePct = 15, prefetchLayers = 2, predictPrefetch = true, predictSpecMax = 4, routeAhead = 1,
            spec = SpecSource.NGRAM, draftMax = 5, mtpPMinPct = 60, oDirect = false, overlap = false,
            rowStream = true, releaseMmap = true, mmapBaseline = true,
            nThreadsBatch = 7, batchWidth = 1024, seed = 12345, ioTwoWave = true, rowStreamMb = 128,
            npuMinTokens = 64, npuRoutedPrefill = false, npuRoutedFullPct = 70,
            ngramMinMatch = 4, ngramMaxMatch = 24, dropRenorm = false, dropInPrefill = true,
            predictionProbe = true, loadAllExpertsForBenchmark = true,
            decisionPrefixCache = DecisionPrefixCache.ON, forceCache = false
        )
        val v = c.toIntArray()
        // index order == enum IntIdx in native-bridge.cpp
        val withNpu = c.copy(npuPrefill = true, npuLoaders = 12).toIntArray()
        assertEquals(1, withNpu[26])          // I_NPU_PREFILL
        assertEquals(12, withNpu[27])         // I_NPU_LOADERS
        assertEquals(8192, v[0])             // I_N_CTX
        assertEquals(8192, v[1])             // I_N_BATCH (one-batch prefill)
        assertEquals(512, v[2])              // I_N_UBATCH
        assertEquals(6, v[3])                // I_N_THREADS
        assertEquals(33, v[4])               // I_TOP_K
        assertEquals(1, v[5])                // I_CACHE_MODE fixed
        assertEquals(1750, v[6])             // I_CACHE_MB
        assertEquals(11, v[7])               // I_CACHE_FLOOR_MB
        assertEquals(4000, v[8])             // I_CACHE_CEIL_MB
        assertEquals(8, v[9])                // I_IO_THREADS
        assertEquals(3, v[10])               // I_DENSE_MODE pinned
        assertEquals(3, v[11])               // I_N_EXPERT_USED
        assertEquals(50, v[12])              // I_DROP_COLD_PCT
        assertEquals(15, v[13])              // I_SUBSTITUTE_PCT
        assertEquals(2, v[14])               // I_PREFETCH_LAYERS
        assertEquals(1, v[15])               // I_PREDICT_PREFETCH
        assertEquals(4, v[16])               // I_PREDICT_SPEC_MAX
        assertEquals(1, v[17])               // I_ROUTE_AHEAD
        assertEquals(2, v[18])               // I_SPEC_SOURCE ngram
        assertEquals(5, v[19])               // I_DRAFT_MAX
        assertEquals(60, v[20])              // I_MTP_P_MIN_PCT
        assertEquals(0, v[21])               // I_O_DIRECT
        assertEquals(0, v[22])               // I_OVERLAP
        assertEquals(1, v[23])               // I_ROW_STREAM
        assertEquals(1, v[24])               // I_RELEASE_MMAP
        assertEquals(1, v[25])               // I_MMAP_BASELINE
        assertEquals(0, v[26])               // I_NPU_PREFILL
        assertEquals(8, v[27])               // I_NPU_LOADERS
        assertEquals(7, v[28])               // I_N_THREADS_BATCH
        assertEquals(1024, v[29])            // I_BATCH_WIDTH
        assertEquals(12345, v[30])           // I_SEED
        assertEquals(1, v[31])               // I_IO_TWO_WAVE
        assertEquals(128, v[32])              // I_ROW_STREAM_MB
        assertEquals(64, v[33])               // I_NPU_MIN_TOKENS
        assertEquals(0, v[34])                // I_NPU_ROUTED
        assertEquals(70, v[35])               // I_NPU_ROUTED_FULL_PCT
        assertEquals(4, v[36])                // I_NGRAM_MIN_MATCH
        assertEquals(24, v[37])               // I_NGRAM_MAX_MATCH
        assertEquals(0, v[38])                // I_DROP_RENORM
        assertEquals(1, v[39])                // I_DROP_IN_PREFILL
        assertEquals(1, v[40])                // I_PREDICTION_PROBE
        assertEquals(1, v[41])                // I_LOAD_ALL_EXPERTS
        assertEquals(1, v[42])                // I_DECIDE_PREFIX_CACHE (On)
        assertEquals(0, v[43])                // I_FORCE_CACHE
    }

    @Test
    fun npuMinTokensClearsTheSpeculativeVerifyWidth() {
        // Plain decode is one token wide, so the floor is 2 and a small threshold is left alone.
        assertEquals(2, d.copy(spec = SpecSource.OFF).npuMinTokensFloor)
        assertEquals(8, d.copy(spec = SpecSource.OFF, npuMinTokens = 8).toIntArray()[33])
        // With Guess ahead the CPU verify batch is draftMax + 1 wide; the NPU threshold must exceed it.
        val mtp = d.copy(spec = SpecSource.MTP, draftMax = 8, npuMinTokens = 8, npuPrefill = true)
        assertEquals(10, mtp.npuMinTokensFloor)
        assertEquals(10, mtp.toIntArray()[33])   // I_NPU_MIN_TOKENS raised above the 9-wide verify batch
        assertEquals(1, mtp.toIntArray()[26])    // I_NPU_PREFILL stays on together with MTP
        // A threshold already above the verify width is untouched.
        assertEquals(64, mtp.copy(npuMinTokens = 64).toIntArray()[33])
    }

    @Test
    fun defaultsAreTheOriginalAppsDefaults() {
        assertEquals(4096, d.nCtx)
        assertEquals(4, d.nThreads)
        assertEquals(4, d.ioThreads)
        assertEquals(CacheMode.FIXED, d.cacheMode)
        assertEquals(2000, d.cacheMb)
        assertEquals(3000, d.cacheCeilMb)
        assertEquals(75, d.dropColdPct)
        assertEquals(0, d.nExpertUsed)
        assertTrue(d.oDirect && d.overlap)
        assertTrue(d.forceCache)
        assertEquals(DecisionPrefixCache.AUTO, d.decisionPrefixCache)
        assertEquals(DenseWeights.ANONYMOUS, d.denseWeights)
        assertEquals(SpecSource.OFF, d.spec)
        assertTrue(!d.rowStream && !d.releaseMmap && !d.predictPrefetch)
    }

    @Test
    fun cacheSentinels() {
        assertEquals(CacheMode.AUTO, EngineSettings(cacheMb = CACHE_AUTO_MB).cacheMode)
        assertEquals(CacheMode.OFF, EngineSettings(cacheMb = 0).cacheMode)
        assertEquals(CacheMode.FIXED, EngineSettings(cacheMb = 500).cacheMode)
        assertEquals(0, EngineSettings(cacheMb = CACHE_AUTO_MB).toLoadConfig().cacheMb)
    }

    @Test
    fun ubatchNeverExceedsContext() {
        assertEquals(256, d.copy(nCtx = 256).nUbatch)
        assertEquals(512, d.copy(nCtx = 4096).nUbatch)
    }

    @Test
    fun metricsCsvPathIsOnlyAskedWhenEnabled() = kotlinx.coroutines.test.runTest {
        val fake = FakeEngineBackend()
        var asked = 0
        val file = java.io.File.createTempFile("m", ".gguf").apply { deleteOnExit() }
        val c = EngineController({ fake }, kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { asked++; "/x/session.csv" }
        c.loadModel(file.path, d.copy(metricsCsv = false))
        assertEquals(0, asked)
        assertEquals(null, fake.lastCsvPath)
        c.loadModel(file.path, d.copy(metricsCsv = true))
        assertEquals(1, asked)
        assertEquals("/x/session.csv", fake.lastCsvPath)
    }
}
