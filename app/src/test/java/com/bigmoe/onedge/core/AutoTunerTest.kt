package com.bigmoe.onedge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTunerTest {

    private val phone = DeviceProfile(
        totalRamMb = 12 * 1024L, availRamMb = 6 * 1024L, cores = 8, fastCores = 5,
        soc = "Test SoC", memBandwidthGBs = 25.0, npuRuntime = true
    )
    private val moe = ModelFacts("/m/moe.gguf", "moe", 17L * 1024 * 1024 * 1024, ModelKind.MOE, expertCount = 128, expertUsed = 8)
    private val dense = ModelFacts("/m/dense.gguf", "dense", 4L * 1024 * 1024 * 1024, ModelKind.DENSE, expertCount = 0, expertUsed = 0)

    private val cacheChoices = setOf(500, 1000, 1250, 1500, 1750, 2000, 3000, 4000, 5000, 6000)
    private val ctxChoices = setOf(1024, 2048, 4096, 8192, 16384)

    @Test
    fun everyPlanUsesValuesTheManualScreenCanShow() {
        for (m in listOf(moe, dense)) for (t in AutoTier.values()) {
            val v = AutoTuner.plan(phone, m, t)
            assertTrue("ctx ${v.contextLength}", v.contextLength in ctxChoices)
            assertTrue("threads ${v.threadCount}", v.threadCount in 1..8)
            assertTrue("cache ${v.cacheMb}", v.cacheMb in cacheChoices || !m.isMoe)
            assertTrue(v.nExpertUsed in setOf(0, 2, 3, 4, 6))
            assertTrue(v.dropColdPct in setOf(0, 50, 75, 100))
            assertTrue(v.substitutePct in setOf(0, 10, 15, 20, 30))
        }
    }

    @Test
    fun accurateMoeIsLosslessAndFastestIsNot() {
        val acc = AutoTuner.plan(phone, moe, AutoTier.ACCURATE)
        assertEquals(0, acc.nExpertUsed); assertEquals(0, acc.dropColdPct); assertEquals(0, acc.substitutePct)
        assertFalse(acc.npuPrefill)
        val fast = AutoTuner.plan(phone, moe, AutoTier.FASTEST)
        assertTrue(fast.nExpertUsed in 1..7); assertTrue(fast.dropColdPct > 0)
    }

    @Test
    fun prefetchModesNeverConflict() {
        for (m in listOf(moe, dense)) for (t in AutoTier.values()) {
            val v = AutoTuner.plan(phone, m, t)
            assertFalse("both prefetchers", v.predictPrefetch && v.prefetchLayers > 0)
            assertEquals(0, v.routeAhead)
        }
    }

    @Test
    fun speedOrderingMatchesTheTiers() {
        for (m in listOf(moe, dense)) {
            val tps = AutoTier.values().map { AutoTuner.estimateTps(phone, m, it, AutoTuner.plan(phone, m, it)) }
            assertTrue("$tps", tps[0] > tps[1] && tps[1] > tps[2])
        }
    }

    @Test
    fun denseFastestUsesGuessAheadOthersDoNot() {
        assertEquals(SpecSource.NGRAM, AutoTuner.plan(phone, dense, AutoTier.FASTEST).spec)
        assertEquals(SpecSource.OFF, AutoTuner.plan(phone, dense, AutoTier.BALANCED).spec)
        assertEquals(SpecSource.OFF, AutoTuner.plan(phone, moe, AutoTier.FASTEST).spec)
    }

    @Test
    fun tinyPhoneStillGetsAValidPlan() {
        val tiny = phone.copy(totalRamMb = 3 * 1024L, availRamMb = 800L, fastCores = 2, cores = 4, npuRuntime = false)
        val v = AutoTuner.plan(tiny, moe, AutoTier.ACCURATE)
        assertTrue(v.contextLength >= 1024); assertTrue(v.cacheMb >= 500); assertFalse(v.npuPrefill)
        val tps = AutoTuner.estimateTps(tiny, moe, AutoTier.ACCURATE, v)
        assertTrue(tps in 0.1..200.0)
    }

    @Test
    fun codecRoundTripsAndRejectsGarbage() {
        val v = AutoTuner.plan(phone, moe, AutoTier.BALANCED)
        val save = AutoSave("abc-123", "My save", AutoTier.BALANCED, "MOE", "moe", 5L, v, 4.2, measuredTps = 3.9, measuredAt = 9L)
        val back = AutoSaveCodec.parse(AutoSaveCodec.toJson(save))
        assertNotNull(back)
        assertEquals(save, back)
        assertNull(AutoSaveCodec.parse("not json"))
        assertNull(AutoSaveCodec.parse("{\"format\":\"other\",\"version\":1}"))
    }

    @Test
    fun codecClampsHostileValues() {
        val v = AutoTuner.plan(phone, moe, AutoTier.BALANCED)
        val json = AutoSaveCodec.toJson(AutoSave("x1", "n", AutoTier.BALANCED, "MOE", "m", 1L, v, 1.0))
            .replace("\"threadCount\": ${v.threadCount}", "\"threadCount\": 9999")
        val parsed = AutoSaveCodec.parse(json)!!
        assertEquals(32, parsed.values.threadCount)
    }

    @Test
    fun namesAreCleaned() {
        assertEquals("a b", AutoSaveCodec.cleanName("  a   b  "))
        assertEquals(AutoSaveCodec.MAX_NAME, AutoSaveCodec.cleanName("x".repeat(500)).length)
    }

    private val big = ModelFacts("/m/big.gguf", "big", 20L * 1024 * 1024 * 1024, ModelKind.MOE, expertCount = 128, expertUsed = 8)

    @Test
    fun npuIsOffUnlessAskedAndNeverOnAccurate() {
        for (t in AutoTier.values()) assertFalse(AutoTuner.plan(phone, moe, t).npuPrefill)
        assertTrue(AutoTuner.plan(phone, moe, AutoTier.FASTEST, allowNpu = true).npuPrefill)
        assertTrue(AutoTuner.plan(phone, moe, AutoTier.BALANCED, allowNpu = true).npuPrefill)
        assertFalse(AutoTuner.plan(phone, moe, AutoTier.ACCURATE, allowNpu = true).npuPrefill)
        assertFalse(AutoTuner.plan(phone, dense, AutoTier.FASTEST, allowNpu = true).npuPrefill)
    }

    @Test
    fun noTierUsesTheSettingsThatCrashedADevice() {
        for (m in listOf(moe, big, dense)) for (t in AutoTier.values()) {
            val v = AutoTuner.plan(phone, m, t, allowNpu = true)
            assertEquals("substitute $t", 0, v.substitutePct)
            assertFalse("two-wave $t", v.ioTwoWave)
            assertTrue("spec max $t", v.predictSpecMax <= 2)
        }
        // The top-k cut is mild: 8 experts -> 6, never lower.
        assertEquals(6, AutoTuner.plan(phone, moe, AutoTier.FASTEST).nExpertUsed)
    }

    @Test
    fun sanitizeStripsOldRiskySettings() {
        val risky = AutoTuner.plan(phone, moe, AutoTier.FASTEST).copy(substitutePct = 20, ioTwoWave = true, predictSpecMax = 4)
        val clean = AutoTuner.sanitize(risky)
        assertEquals(0, clean.substitutePct); assertFalse(clean.ioTwoWave); assertEquals(2, clean.predictSpecMax)
        assertEquals(clean, AutoTuner.sanitize(clean))
    }

    @Test
    fun plansForABigModelStayInsideTheRamBudget() {
        for (t in AutoTier.values()) {
            val v = AutoTuner.plan(phone, big, t)
            assertTrue("$t footprint ${AutoTuner.footprintMb(big, v)} > ${AutoTuner.usableMb(phone)}", AutoTuner.fitsInRam(phone, big, v))
            assertEquals(256, v.batchWidth)
        }
    }

    @Test
    fun aModelThatCannotFitIsReportedAsNotFitting() {
        val small = phone.copy(totalRamMb = 6 * 1024L, availRamMb = 2 * 1024L)
        for (t in AutoTier.values()) assertFalse(AutoTuner.fitsInRam(small, big, AutoTuner.plan(small, big, t)))
    }

    @Test
    fun saferShrinksUntilTheFloorThenGivesUp() {
        var v: TunedValues? = AutoTuner.plan(phone, moe, AutoTier.FASTEST, allowNpu = true).copy(contextLength = 8192, cacheMb = 6000)
        var steps = 0
        while (true) {
            val next = AutoTuner.safer(v!!) ?: break
            assertTrue(next.cacheMb <= v.cacheMb && next.contextLength <= v.contextLength)
            assertFalse(next.npuPrefill)
            v = next
            steps++
            assertTrue("did not converge", steps < 20)
        }
        assertEquals(500, v!!.cacheMb); assertEquals(1024, v.contextLength); assertTrue(steps > 0)
    }
}
