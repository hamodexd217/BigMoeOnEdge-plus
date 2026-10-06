package com.bigmoe.onedge.core

/** How the expert cache is sized. Fixed at model load. */
enum class CacheMode(val code: Int) {
    /** Sized once from the device's free RAM minus a floor (optionally capped by a ceiling). */
    AUTO(0),
    /** A fixed budget in MiB. Budgets under 1500 MiB are the engine's "pathological band" and are forced. */
    FIXED(1),
    /** No cache: every routed expert is re-read from flash each token. */
    OFF(2)
}

/** How the non-expert weights are kept resident. Mirrors bmoe::DenseWeightsMode. Fixed at model load. */
enum class DenseWeights(val code: Int) {
    MMAP(0),
    WARMED(1),
    ANONYMOUS(2),
    PINNED(3)
}

/** Self-speculative decoding source. Mirrors bmoe::DraftSource. */
enum class SpecSource(val code: Int) {
    OFF(0),
    /** The model's own multi-token-prediction head; the engine refuses to open a model without one. */
    MTP(1),
    /** Prompt-lookup drafting; works on every model. */
    NGRAM(2)
}

/**
 * Everything the engine can only take when the model is (re)loaded. Field for field this is the original
 * BigMoeOnEdge Android app's AppSettings (see docs/settings-parity.md). [toIntArray]/[toFloatArray] define
 * the wire format shared with native-bridge.cpp (IntIdx / FloatIdx) and are pinned by a unit test.
 */
data class LoadConfig(
    val nCtx: Int,
    val nThreads: Int,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    /** true = llama.cpp's ordinary mmap load, no expert streaming (the original app's "mmap baseline"). */
    val mmapBaseline: Boolean,
    val cacheMode: CacheMode,
    val cacheMb: Int,
    val cacheFloorMb: Int,
    val cacheCeilMb: Int,
    val ioThreads: Int,
    val oDirect: Boolean,
    val overlap: Boolean,
    val denseWeights: DenseWeights,
    /** 0 = the model's own top-k; lower = faster, changes the output. */
    val nExpertUsed: Int,
    val dropColdPct: Int,
    val substitutePct: Int,
    val prefetchLayers: Int,
    val predictPrefetch: Boolean,
    val predictSpecMax: Int,
    val routeAhead: Int,
    val rowStream: Boolean,
    val releaseMmap: Boolean,
    val spec: SpecSource,
    val draftMax: Int,
    val mtpPMinPct: Int,
    val metricsCsv: Boolean,
    /** Optional Snapdragon Hexagon prefill. Disabled by default and ignored unless the native build has it. */
    val npuPrefill: Boolean = false,
    /** Number of background loaders used by the NPU prefill path. */
    val npuLoaders: Int = 8
) {
    /** One-batch prefill for any prompt that fits the context, like the engine's own session_config_from. */
    val nBatch: Int get() = nCtx
    /** Never reserve a graph wider than the context itself (the original app passes --ubatch min(512, ctx)). */
    val nUbatch: Int get() = minOf(UBATCH, nCtx)

    fun toIntArray(): IntArray = intArrayOf(
        nCtx, nBatch, nUbatch, nThreads, topK,
        cacheMode.code, cacheMb, cacheFloorMb, cacheCeilMb, ioThreads,
        denseWeights.code,
        nExpertUsed, dropColdPct, substitutePct, prefetchLayers, if (predictPrefetch) 1 else 0,
        predictSpecMax, routeAhead,
        spec.code,
        draftMax, mtpPMinPct, if (oDirect) 1 else 0, if (overlap) 1 else 0, if (rowStream) 1 else 0,
        if (releaseMmap) 1 else 0,
        if (mmapBaseline) 1 else 0,
        if (npuPrefill) 1 else 0,
        npuLoaders.coerceIn(1, 16)
    )

    fun toFloatArray(): FloatArray = floatArrayOf(temperature, topP)

    companion object {
        const val UBATCH = 512
        /** Length of [toIntArray]; must equal I_COUNT in native-bridge.cpp. */
        const val INT_COUNT = 28
        const val FLOAT_COUNT = 2
    }
}

data class ModelInfo(
    val path: String,
    val name: String,
    val arch: String,
    val nCtx: Int,
    val nExpertUsed: Int,
    val loadSeconds: Double,
    /** "template" | "prefill" | "none": whether the "thinking" switch can really turn reasoning off. */
    val thinkControl: String,
    /** Whether I/O–compute overlap is really active (needs patch 0002 compiled in and streaming on). */
    val overlapActive: Boolean,
    /** True when a vision projector (mmproj) is loaded and images are accepted. */
    val visionActive: Boolean,
    val mmprojPath: String?,
    val config: LoadConfig
)

sealed interface EngineState {
    data object Unloaded : EngineState
    data class Loading(val path: String, val startedAtMs: Long) : EngineState
    data class Ready(val info: ModelInfo) : EngineState
    data class Failed(val path: String?, val message: String) : EngineState
}

/** A decoded image for one turn: tightly packed RGB8 ([rgb].size == width * height * 3). */
class ImageData(val width: Int, val height: Int, val rgb: ByteArray) {
    init {
        require(width > 0 && height > 0 && rgb.size == width * height * 3) { "bad image buffer" }
    }
}

/** What this libonedge-engine.so was built with (see EngineNativeBridge.nativeCapabilities). */
data class EngineCaps(val overlapHook: Boolean, val multimodal: Boolean, val npuPrefill: Boolean) {
    companion object {
        fun fromBits(bits: Int) = EngineCaps(
            overlapHook = bits and 1 != 0,
            multimodal = bits and 2 != 0,
            npuPrefill = bits and 4 != 0
        )
    }
}

/** One prior turn of the conversation as the engine's chat template will see it. */
data class ChatTurn(val role: String, val content: String) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

data class GenerationStats(
    val nGenerated: Int = 0,
    val nPrompt: Int = 0,
    val nPast: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val prefillSeconds: Double = 0.0,
    val cacheHitPct: Double = -1.0
)

sealed interface EngineEvent {
    data class Progress(val generatedTokens: Int) : EngineEvent
    data class Answer(val delta: String) : EngineEvent
    data class Reasoning(val delta: String) : EngineEvent
    data class Finished(
        val ok: Boolean,
        val cancelled: Boolean,
        val error: String,
        val answer: String,
        val reasoning: String,
        val stats: GenerationStats
    ) : EngineEvent
}

/**
 * @property expectedKey identifies the conversation state the CALLER believes the engine holds
 *   (see [ContextPlanner.stateKey]). If the engine's actual state differs, the engine is reset and the
 *   conversation is replayed from [history].
 * @property history committed turns BEFORE [prompt]; only used when a replay is needed.
 */
data class GenerationRequest(
    val system: String,
    val history: List<ChatTurn>,
    val prompt: String,
    val expectedKey: String,
    /** <= 0 = as long as the context allows. */
    val maxTokens: Int,
    val think: Boolean,
    /** Images for THIS turn only; needs a loaded vision projector. */
    val images: List<ImageData> = emptyList()
)


/** A multiple-choice decision scored from the first token after one prompt prefill. */
data class DecisionRequest(
    val prefix: String,
    val suffix: String,
    val choices: List<String>,
    val reusePrefix: Boolean = true
)

data class ChoiceScore(
    val label: String,
    val text: String,
    val probability: Double
)

data class DecisionResult(
    val ok: Boolean,
    val error: String = "",
    val cancelled: Boolean = false,
    val scores: List<ChoiceScore> = emptyList(),
    val best: Int = -1,
    val metrics: String = ""
)
