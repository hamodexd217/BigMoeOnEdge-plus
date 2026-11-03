package com.bigmoe.onedge.core

import com.bigmoe.onedge.data.local.datastore.EngineSettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The three speed/accuracy trade-offs the Auto settings screen can build. */
enum class AutoTier(val id: String, val title: String, val tagline: String) {
    FASTEST("fastest", "Fastest", "Highest speed. Answers may differ slightly from the original model."),
    BALANCED("balanced", "Balanced", "Good speed with answers very close to the original model."),
    ACCURATE("accurate", "Most accurate (slowest)", "Slowest. Uses the model exactly as trained, with the largest safe context.");

    companion object {
        fun fromId(id: String?): AutoTier = values().firstOrNull { it.id == id } ?: BALANCED
    }
}

/** What the phone can do. Filled by [DeviceScanner]; plain data so the planner is JVM-testable. */
data class DeviceProfile(
    val totalRamMb: Long,
    val availRamMb: Long,
    val cores: Int,
    /** Cores whose max frequency is within ~75% of the fastest core (the ones worth running decode on). */
    val fastCores: Int,
    val soc: String,
    /** Measured multi-thread memory copy throughput, GB/s. */
    val memBandwidthGBs: Double,
    /** True only when the engine reported a working Hexagon HTP runtime. */
    val npuRuntime: Boolean
)

/** What the GGUF header says about the chosen model. */
data class ModelFacts(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val kind: ModelKind,
    val expertCount: Int,
    /** Experts the model uses per token by default; 0 when the header does not say. */
    val expertUsed: Int
) {
    val sizeMb: Long get() = sizeBytes / (1024L * 1024L)
    val sizeGb: Double get() = sizeBytes / (1024.0 * 1024.0 * 1024.0)
    val isMoe: Boolean get() = kind == ModelKind.MOE
}

/**
 * The engine settings Auto settings owns. Everything else (sampling, tools, theme, …) is left alone, so a
 * save only changes how fast / how exactly the model runs.
 */
data class TunedValues(
    val contextLength: Int,
    val threadCount: Int,
    val batchThreadCount: Int,
    val batchWidth: Int,
    val cacheMb: Int,
    val ioThreads: Int,
    val oDirect: Boolean,
    val overlap: Boolean,
    val ioTwoWave: Boolean,
    val nExpertUsed: Int,
    val dropColdPct: Int,
    val dropRenorm: Boolean,
    val substitutePct: Int,
    val prefetchLayers: Int,
    val predictPrefetch: Boolean,
    val predictSpecMax: Int,
    val routeAhead: Int,
    val spec: SpecSource,
    val draftMax: Int,
    val npuPrefill: Boolean
)

/** Applies a save on top of the user's current settings. */
fun TunedValues.applyTo(s: EngineSettings): EngineSettings = s.copy(
    contextLength = contextLength,
    threadCount = threadCount,
    batchThreadCount = batchThreadCount,
    batchWidth = batchWidth,
    cacheMb = cacheMb,
    ioThreads = ioThreads,
    oDirect = oDirect,
    overlap = overlap,
    ioTwoWave = ioTwoWave,
    nExpertUsed = nExpertUsed,
    dropColdPct = dropColdPct,
    dropRenorm = dropRenorm,
    substitutePct = substitutePct,
    prefetchLayers = prefetchLayers,
    predictPrefetch = predictPrefetch,
    predictSpecMax = predictSpecMax,
    routeAhead = routeAhead,
    spec = spec,
    draftMax = draftMax,
    npuPrefill = npuPrefill
)

/**
 * Builds settings for a device + model and estimates decode speed.
 *
 * The estimate is a physical model (decode is limited by memory bandwidth, and for streamed MoE models by
 * flash reads) scaled by a fixed factor per tier. It is deliberately labelled an estimate in the UI:
 * measuring on the device is the only exact number.
 */
object AutoTuner {

    /** Same choice lists as the manual settings screen, so a save never holds a value the UI can't show. */
    private val CACHE_CHOICES = listOf(500, 1000, 1250, 1500, 1750, 2000, 3000, 4000, 5000, 6000)
    private val CTX_CHOICES = listOf(1024, 2048, 4096, 8192, 16384)
    private val TOPK_CHOICES = listOf(2, 3, 4, 6)

    /** Android, the app UI and other apps: never plan on this much of the phone's RAM. */
    private const val SYSTEM_OVERHEAD_MB = 1200L
    private const val DENSE_PART_OF_MOE = 0.08
    private const val RAM_EFFICIENCY = 0.55
    private const val FLASH_GBS = 1.5

    /**
     * RAM the engine may plan on: at most 55% of the phone minus a fixed system overhead, and never more than
     * what is realistically free. Deliberately cautious: a model that doesn't fit gets the app killed by Android.
     */
    fun usableMb(d: DeviceProfile): Long {
        val cap = (d.totalRamMb * 0.55).toLong() - SYSTEM_OVERHEAD_MB
        val free = d.availRamMb + (d.totalRamMb * 0.10).toLong()
        return max(768L, min(cap, free))
    }

    /** Compute/graph buffers grow with the model; big models get a bigger reserve. */
    fun computeReserveMb(m: ModelFacts): Long = (700L + (m.sizeMb * 0.04).toLong()).coerceIn(700L, 1800L)

    /** Rough resident memory for a load with [v]: weights that stay in RAM + expert cache + KV + compute buffers. */
    fun footprintMb(m: ModelFacts, v: TunedValues): Long {
        val kv = (v.contextLength * kvMbPerToken(m)).toLong()
        val weights = if (m.isMoe) (m.sizeMb * DENSE_PART_OF_MOE).toLong() + min(v.cacheMb.toLong().coerceAtLeast(0L), m.sizeMb) else m.sizeMb
        return weights + kv + computeReserveMb(m)
    }

    /**
     * Removes the settings that crashed a real device (expert substitution, two-wave reads, a wide prediction
     * window). Applied to saves made by an older version and to imported files.
     */
    fun sanitize(v: TunedValues): TunedValues = v.copy(
        substitutePct = 0,
        ioTwoWave = false,
        predictSpecMax = v.predictSpecMax.coerceAtMost(2)
    )

    /** False when a load with [v] would likely exceed the RAM budget (so it must not be attempted). */
    fun fitsInRam(d: DeviceProfile, m: ModelFacts, v: TunedValues): Boolean = footprintMb(m, v) <= usableMb(d)

    /**
     * A strictly smaller version of [v] (after a crash or out-of-memory kill): lower expert cache, half the
     * context, narrow prompt batches, and none of the NPU / look-ahead extras. Returns null when it is
     * already at the floor, meaning this model cannot run safely on this phone.
     */
    fun safer(v: TunedValues): TunedValues? {
        // Back off one knob at a time so recovery preserves as much performance as possible.
        val cacheLower = CACHE_CHOICES.filter { it < v.cacheMb }.maxOrNull()
        if (cacheLower != null) return v.copy(cacheMb = cacheLower)

        val ctxLower = CTX_CHOICES.filter { it < v.contextLength }.maxOrNull()
        if (ctxLower != null) return v.copy(contextLength = ctxLower)

        if (v.batchWidth > 128) return v.copy(batchWidth = (v.batchWidth / 2).coerceAtLeast(128))
        if (v.batchThreadCount > 1) return v.copy(batchThreadCount = max(1, v.batchThreadCount - 1))
        if (v.threadCount > 1) return v.copy(threadCount = max(1, v.threadCount - 1))
        if (v.npuPrefill) return v.copy(npuPrefill = false)
        if (v.predictPrefetch) return v.copy(predictPrefetch = false)
        if (v.predictSpecMax > 0) return v.copy(predictSpecMax = 0)
        if (v.prefetchLayers > 0) return v.copy(prefetchLayers = 0)
        if (v.routeAhead > 0) return v.copy(routeAhead = 0)
        if (v.spec != SpecSource.OFF) return v.copy(spec = SpecSource.OFF)
        if (v.nExpertUsed > 0) return v.copy(nExpertUsed = 0)
        if (v.dropColdPct > 0) return v.copy(dropColdPct = 0)
        if (v.ioTwoWave) return v.copy(ioTwoWave = false)
        if (v.overlap) return v.copy(overlap = false)
        return null
    }

    private fun kvMbPerToken(m: ModelFacts): Double = (if (m.isMoe) 0.04 else 0.12) * max(m.sizeGb, 0.5)

    private fun contextFor(tier: AutoTier, d: DeviceProfile, m: ModelFacts, reserveMb: Long): Int {
        val wanted = when (tier) {
            AutoTier.FASTEST -> 2048
            AutoTier.BALANCED -> 4096
            AutoTier.ACCURATE -> 16384
        }
        val room = usableMb(d) - reserveMb - computeReserveMb(m)
        val fit = CTX_CHOICES.filter { it <= wanted && it * kvMbPerToken(m) <= room }
        return fit.maxOrNull() ?: CTX_CHOICES.first()
    }

    private fun pickCache(budgetMb: Long, expertMb: Long): Int {
        val capped = min(budgetMb, max(expertMb, CACHE_CHOICES.first().toLong()))
        return CACHE_CHOICES.filter { it <= capped }.maxOrNull() ?: CACHE_CHOICES.first()
    }

    /** A mild cut (8 → 6), never the harsh 8 → 4. 0 leaves the model's own value. */
    private fun reducedTopK(used: Int): Int = when {
        used >= 7 -> 6
        used >= 5 -> 4
        used == 4 -> 3
        used == 3 -> 2
        else -> 0 // 0/1/2: leave the model's own value
    }.let { if (it in TOPK_CHOICES) it else 0 }

    /**
     * [allowNpu] is opt-in: NPU prefill is the newest, least proven path, so a save never turns it on unless the
     * user asked for it (and only on tiers where a tiny numeric difference is acceptable).
     */
    fun plan(device: DeviceProfile, model: ModelFacts, tier: AutoTier, allowNpu: Boolean = false): TunedValues {
        val fast = device.fastCores.coerceIn(1, 8)
        val npu = allowNpu && device.npuRuntime && model.isMoe && tier != AutoTier.ACCURATE
        return if (model.isMoe) planMoe(device, model, tier, fast, npu) else planDense(device, model, tier, fast)
    }

    private fun planMoe(d: DeviceProfile, m: ModelFacts, tier: AutoTier, fast: Int, npu: Boolean): TunedValues {
        val denseMb = (m.sizeMb * DENSE_PART_OF_MOE).toLong()
        val expertMb = max(0L, m.sizeMb - denseMb)
        // Context first (it decides how much RAM is left for the expert cache).
        val ctx = contextFor(tier, d, m, reserveMb = denseMb)
        val kvMb = (ctx * kvMbPerToken(m)).toLong()
        val cacheBudget = usableMb(d) - denseMb - computeReserveMb(m) - kvMb
        val cache = pickCache(cacheBudget, expertMb)
        val base = TunedValues(
            contextLength = ctx,
            threadCount = fast,
            batchThreadCount = fast,
            batchWidth = if (m.sizeGb > 12.0) 256 else 512,
            cacheMb = cache,
            ioThreads = 4,
            oDirect = true,
            overlap = true,
            ioTwoWave = false,
            nExpertUsed = 0,
            dropColdPct = 0,
            dropRenorm = true,
            substitutePct = 0,
            prefetchLayers = 0,
            predictPrefetch = false,
            predictSpecMax = 0,
            routeAhead = 0,
            spec = SpecSource.OFF,
            draftMax = 3,
            npuPrefill = npu
        )
        return when (tier) {
            // Only combinations that have run on a real device. Expert substitution and two-wave reads are NOT used:
            // the first Fastest save used them (plus a bigger prefetch and a harder top-k cut) and it was the only
            // save that crashed on a 35B-A3B model.
            AutoTier.FASTEST -> base.copy(
                nExpertUsed = reducedTopK(m.expertUsed),
                dropColdPct = 75,
                predictPrefetch = true,
                predictSpecMax = 2
            )
            AutoTier.BALANCED -> base.copy(
                dropColdPct = 50,
                predictPrefetch = true,
                predictSpecMax = 2
            )
            // Lossless: the model's own top-k, no dropped or substituted experts. Prefetch only reorders reads.
            AutoTier.ACCURATE -> base.copy(prefetchLayers = 1)
        }
    }

    private fun planDense(d: DeviceProfile, m: ModelFacts, tier: AutoTier, fast: Int): TunedValues {
        val ctx = contextFor(tier, d, m, reserveMb = m.sizeMb)
        return TunedValues(
            contextLength = ctx,
            threadCount = fast,
            batchThreadCount = fast,
            batchWidth = 512,
            // The streaming settings are inert for a dense model; keep neutral, lossless values.
            cacheMb = 2000,
            ioThreads = 4,
            oDirect = true,
            overlap = true,
            ioTwoWave = false,
            nExpertUsed = 0,
            dropColdPct = 0,
            dropRenorm = true,
            substitutePct = 0,
            prefetchLayers = 0,
            predictPrefetch = false,
            predictSpecMax = 0,
            routeAhead = 0,
            // Repeated-text guessing is exact (it only keeps what the model would have written) but forces greedy decoding.
            spec = if (tier == AutoTier.FASTEST) SpecSource.NGRAM else SpecSource.OFF,
            draftMax = 4,
            npuPrefill = false
        )
    }

    /** Estimated decode speed in tokens/second, rounded to 0.1. */
    fun estimateTps(d: DeviceProfile, m: ModelFacts, tier: AutoTier, v: TunedValues): Double {
        val bw = max(1.0, d.memBandwidthGBs) * RAM_EFFICIENCY
        val sizeGb = max(m.sizeGb, 0.05)
        val raw = if (m.isMoe) {
            val denseGb = sizeGb * DENSE_PART_OF_MOE
            val expertGb = sizeGb - denseGb
            val n = max(m.expertCount, 1)
            val k = if (m.expertUsed > 0) m.expertUsed else min(8, max(2, n / 8))
            val activeFrac = min(1.0, k.toDouble() / n)
            val cacheGb = min(v.cacheMb / 1024.0, expertGb)
            val hit = min(0.97, sqrt(cacheGb / max(expertGb, 0.01)))
            val perTokenExpertGb = expertGb * activeFrac
            val seconds = denseGb / bw + perTokenExpertGb * (hit / bw + (1 - hit) / FLASH_GBS)
            1.0 / seconds
        } else {
            val usableGb = usableMb(d) / 1024.0
            val speed = bw / sizeGb
            // A dense model larger than the RAM budget is paged from flash on every token.
            if (sizeGb > usableGb * 0.9) speed * 0.15 else speed
        }
        val factor = if (m.isMoe) when (tier) {
            AutoTier.FASTEST -> 1.7
            AutoTier.BALANCED -> 1.25
            AutoTier.ACCURATE -> 1.0
        } else when (tier) {
            AutoTier.FASTEST -> 1.15
            AutoTier.BALANCED -> 1.0
            AutoTier.ACCURATE -> 0.92
        }
        return ((raw * factor).coerceIn(0.1, 200.0) * 10).roundToInt() / 10.0
    }

    /** Rough wall-clock time to measure [saves] saves of this model, in seconds (load + 64 generated tokens each). */
    fun estimateMeasureSeconds(m: ModelFacts, saves: Int, tps: Double): Int {
        val load = m.sizeGb * 2.5 + 5.0
        val gen = 64.0 / max(tps, 0.2) + 5.0
        return ((load + gen) * saves).roundToInt()
    }

    /** One short, plain-language line per notable setting, shown on a save card. */
    fun describe(v: TunedValues, m: ModelFacts): List<String> {
        val out = ArrayList<String>()
        out += "Context ${v.contextLength} tokens, ${v.threadCount} CPU threads"
        if (m.isMoe) {
            out += "Expert cache ${v.cacheMb} MB of RAM"
            when {
                v.nExpertUsed > 0 -> out += "Uses ${v.nExpertUsed} experts per token instead of the model's own ${if (m.expertUsed > 0) m.expertUsed.toString() else "default"}"
                else -> out += "Uses the model's own number of experts per token"
            }
            if (v.dropColdPct > 0) out += "Skips the ${v.dropColdPct}% least-used experts"
            if (v.substitutePct > 0) out += "Prefers already-cached experts (${v.substitutePct}%)"
            if (v.predictPrefetch || v.prefetchLayers > 0) out += "Reads the next experts ahead of time"
            if (v.npuPrefill) out += "Prompt prefill on the NPU"
        } else if (v.spec == SpecSource.NGRAM) {
            out += "Guesses repeated text ahead (uses greedy decoding)"
        }
        return out
    }
}
