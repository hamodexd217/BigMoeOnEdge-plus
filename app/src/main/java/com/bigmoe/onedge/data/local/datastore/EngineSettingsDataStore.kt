package com.bigmoe.onedge.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bigmoe.onedge.core.CacheMode
import com.bigmoe.onedge.core.DenseWeights
import com.bigmoe.onedge.core.DecisionPrefixCache
import com.bigmoe.onedge.core.LoadConfig
import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.core.TunedValues
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "engine_settings")

const val DEFAULT_SYSTEM_PROMPT = ""

/** When web search is switched on: the model decides (tool call) or the app searches for every message. */
enum class WebSearchMode { AUTO, ALWAYS }

/** Cache budget sentinel values (same convention as the original app's CACHE_AUTO / 0 / N MiB). */
const val CACHE_AUTO_MB = -1
const val CACHE_OFF_MB = 0

/**
 * User settings. Three groups:
 *  - ENGINE (everything in [toLoadConfig]): the engine fixes these when a model is opened
 *    (bmoe::SessionConfig). Changing them only takes effect after the model is reloaded. The defaults are the
 *    original BigMoeOnEdge Android app's defaults (docs/settings-parity.md).
 *  - PER-REQUEST (maxTokens, thinkingEnabled, systemPrompt, web search): applied on the next message.
 *  - APP (theme, last model): never reach the engine.
 */
data class EngineSettings(
    // ---- engine (needs reload) ----
    val contextLength: Int = 4096,
    val threadCount: Int = 4,
    /** Threads used by multi-token prompt batches; independent of single-token decode threads. */
    val batchThreadCount: Int = 4,
    /** Widest graph evaluated at once. Lower values conserve RAM; capped to the context at load. */
    val batchWidth: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    /** -1 asks llama.cpp to choose a fresh random seed for each generation. */
    val seed: Int = -1,
    /** -1 = auto, 0 = off, otherwise a fixed budget in MiB. */
    val cacheMb: Int = 2000,
    val cacheCeilMb: Int = 3000,
    val cacheFloorMb: Int = 1536,
    val ioThreads: Int = 4,
    val oDirect: Boolean = true,
    /** Enabled by default: hide expert flash-read latency behind CPU FFN compute when the hook exists. */
    val overlap: Boolean = true,
    val ioTwoWave: Boolean = false,
    val denseWeights: DenseWeights = DenseWeights.ANONYMOUS,
    val rowStreamMb: Int = 64,
    val nExpertUsed: Int = 0,
    val dropColdPct: Int = 75,
    val dropRenorm: Boolean = true,
    val dropInPrefill: Boolean = false,
    val substitutePct: Int = 0,
    val prefetchLayers: Int = 0,
    val predictPrefetch: Boolean = false,
    val predictSpecMax: Int = 0,
    val predictionProbe: Boolean = false,
    val routeAhead: Int = 0,
    val rowStream: Boolean = false,
    val loadAllExpertsForBenchmark: Boolean = false,
    val releaseMmap: Boolean = false,
    val spec: SpecSource = SpecSource.OFF,
    val draftMax: Int = 3,
    val mtpPMinPct: Int = 0,
    /** Explicitly opt into a separate MTP draft GGUF; target-model expert streaming stays enabled for MoE. */
    val separateMtpDraftEnabled: Boolean = false,
    // Optional diagnostics perform per-token formatting and memory snapshots; keep the
    // interactive decode path lean unless the user explicitly enables Metrics CSV.
    val metricsCsv: Boolean = false,
    /** Enable optional Hexagon/NPU prefill; off by default so CPU-only APKs remain normal. */
    val npuPrefill: Boolean = false,
    val npuLoaders: Int = 8,
    val npuMinTokens: Int = 32,
    val npuRoutedPrefill: Boolean = true,
    val npuRoutedFullPct: Int = 85,
    val ngramMinMatch: Int = 3,
    val ngramMaxMatch: Int = 12,
    /** Prefix cache policy for the scored-choice API; Auto selects it per backend. */
    val decisionPrefixCache: DecisionPrefixCache = DecisionPrefixCache.AUTO,
    /** Preserve the app's existing ability to choose a cache below the engine's 1500 MiB floor. */
    val forceCache: Boolean = true,
    // ---- per-request ----
    /** <= 0 = as long as the context allows. */
    val maxTokens: Int = -1,
    val thinkingEnabled: Boolean = false,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val isWebSearchEnabled: Boolean = false,
    val webSearchMode: WebSearchMode = WebSearchMode.ALWAYS,
    val toolsFilesEnabled: Boolean = false,
    val toolsCodeEnabled: Boolean = false,
    /** Offline helpers: date/time, calculator, unit converter, random numbers, device status. */
    val toolsUtilitiesEnabled: Boolean = false,
    /** Longest tool result handed back to the model, in characters. The model reads every one of them before it can go on. */
    val toolResultChars: Int = DEFAULT_TOOL_RESULT_CHARS,
    /** Reason again after a tool result. Off by default: reasoning tokens are generated at the model's (slow) decode speed. */
    val thinkAfterTools: Boolean = false,
    /** Longest side of pictures sent to the model, in pixels (more = sharper, but many more tokens). */
    val imageMaxDim: Int = 512,
    /** Frames sampled evenly from an attached video. */
    val videoFrames: Int = 4,
    val braveApiKey: String = "",
    val searxngUrl: String = "",
    // ---- app ----
    val themeId: String = "paper",
    /** JSON array of user-made palettes, see ui/theme/PaletteStore. */
    val customPalettesJson: String = "[]",
    val lastModelPath: String = "",
    val lastMmprojPath: String = "",
    val autoLoadLastModel: Boolean = false,
    // ---- local API server (docs/API_SERVER.md) ----
    val serverEnabled: Boolean = false,
    val serverPort: Int = DEFAULT_SERVER_PORT,
    /** "" until the server is first started, which generates one. */
    val serverApiKey: String = "",
    /** Only honoured when the server listens on loopback; a server reachable from the network always needs the key. */
    val serverRequireKey: Boolean = true,
    /** false = 127.0.0.1 only (default); true = all interfaces. */
    val serverBindAll: Boolean = false,
    /** Override request sampling/thinking/max_tokens with the server's own values. */
    val serverForceSettings: Boolean = false,
    /** How long a request may wait in the queue before the server answers 529. */
    val serverQueueTimeoutSec: Int = DEFAULT_SERVER_QUEUE_SEC
) {

    val cacheMode: CacheMode
        get() = when {
            cacheMb == CACHE_AUTO_MB -> CacheMode.AUTO
            cacheMb <= CACHE_OFF_MB -> CacheMode.OFF
            else -> CacheMode.FIXED
        }

    fun toLoadConfig(): LoadConfig = LoadConfig(
        nCtx = contextLength,
        nThreads = threadCount,
        nThreadsBatch = batchThreadCount,
        batchWidth = batchWidth,
        seed = seed,
        // The engine refuses to open a model when "guess ahead" (speculation) is on together with sampling:
        // verification is exact only under greedy decoding. So guess ahead means temperature 0.
        temperature = if (spec != SpecSource.OFF) 0f else temperature,
        topP = topP,
        topK = topK,
        // The streaming/ordinary-load choice is made per model when it is opened (EngineController.loadModel).
        mmapBaseline = false,
        cacheMode = cacheMode,
        cacheMb = cacheMb.coerceAtLeast(0),
        cacheFloorMb = cacheFloorMb,
        cacheCeilMb = cacheCeilMb,
        ioThreads = ioThreads,
        oDirect = oDirect,
        overlap = overlap,
        ioTwoWave = ioTwoWave,
        denseWeights = denseWeights,
        rowStreamMb = rowStreamMb,
        nExpertUsed = nExpertUsed,
        dropColdPct = dropColdPct,
        dropRenorm = dropRenorm,
        dropInPrefill = dropInPrefill,
        substitutePct = substitutePct,
        prefetchLayers = prefetchLayers,
        predictPrefetch = predictPrefetch,
        predictSpecMax = predictSpecMax,
        predictionProbe = predictionProbe,
        routeAhead = routeAhead,
        rowStream = rowStream,
        loadAllExpertsForBenchmark = loadAllExpertsForBenchmark,
        releaseMmap = releaseMmap,
        spec = spec,
        draftMax = draftMax,
        mtpPMinPct = mtpPMinPct,
        metricsCsv = metricsCsv,
        npuPrefill = npuPrefill,
        npuLoaders = npuLoaders,
        npuMinTokens = npuMinTokens,
        npuRoutedPrefill = npuRoutedPrefill,
        npuRoutedFullPct = npuRoutedFullPct,
        ngramMinMatch = ngramMinMatch,
        ngramMaxMatch = ngramMaxMatch,
        decisionPrefixCache = decisionPrefixCache,
        forceCache = forceCache
    )
}

const val DEFAULT_TOOL_RESULT_CHARS = 3500
const val DEFAULT_SERVER_PORT = 8080
const val DEFAULT_SERVER_QUEUE_SEC = 60

/** True when a model loaded with [loaded] would behave differently after a reload with [settings]. */
/** The streaming-or-ordinary mode is chosen automatically per model, so it never counts as a changed setting. */
fun loadConfigDiffers(loaded: LoadConfig, settings: EngineSettings): Boolean =
    loaded.copy(mmapBaseline = false) != settings.toLoadConfig().copy(mmapBaseline = false)

class EngineSettingsDataStore(private val context: Context) {

    private object Keys {
        val CONTEXT_LENGTH = intPreferencesKey("context_length")
        val THREAD_COUNT = intPreferencesKey("thread_count")
        val BATCH_THREAD_COUNT = intPreferencesKey("batch_thread_count")
        val BATCH_WIDTH = intPreferencesKey("batch_width")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_P = floatPreferencesKey("top_p")
        val TOP_K = intPreferencesKey("top_k")
        val SEED = intPreferencesKey("sampling_seed")
        val CACHE_MB = intPreferencesKey("cache_mb_v2")
        val CACHE_CEIL_MB = intPreferencesKey("cache_ceil_mb")
        val CACHE_FLOOR_MB = intPreferencesKey("cache_floor_mb")
        val IO_THREADS = intPreferencesKey("io_threads")
        val O_DIRECT = booleanPreferencesKey("o_direct")
        val OVERLAP = booleanPreferencesKey("overlap")
        val IO_TWO_WAVE = booleanPreferencesKey("io_two_wave")
        val DENSE_WEIGHTS = stringPreferencesKey("dense_weights")
        val ROW_STREAM_MB = intPreferencesKey("row_stream_mb")
        val N_EXPERT_USED = intPreferencesKey("n_expert_used")
        val DROP_COLD_PCT = intPreferencesKey("drop_cold_pct")
        val DROP_RENORM = booleanPreferencesKey("drop_renorm")
        val DROP_IN_PREFILL = booleanPreferencesKey("drop_in_prefill")
        val SUBSTITUTE_PCT = intPreferencesKey("substitute_pct")
        val PREFETCH_LAYERS = intPreferencesKey("prefetch_layers")
        val PREDICT_PREFETCH = booleanPreferencesKey("predict_prefetch")
        val PREDICT_SPEC_MAX = intPreferencesKey("predict_spec_max")
        val PREDICTION_PROBE = booleanPreferencesKey("prediction_probe")
        val ROUTE_AHEAD = intPreferencesKey("route_ahead")
        val ROW_STREAM = booleanPreferencesKey("row_stream")
        val LOAD_ALL_EXPERTS = booleanPreferencesKey("load_all_experts_benchmark")
        val RELEASE_MMAP = booleanPreferencesKey("release_mmap")
        val SPEC = stringPreferencesKey("spec")
        val DRAFT_MAX = intPreferencesKey("draft_max")
        val MTP_P_MIN_PCT = intPreferencesKey("mtp_p_min_pct")
        val SEPARATE_MTP_DRAFT_ENABLED = booleanPreferencesKey("separate_mtp_draft_enabled")
        val METRICS_CSV = booleanPreferencesKey("metrics_csv")
        val NPU_PREFILL = booleanPreferencesKey("npu_prefill")
        val NPU_LOADERS = intPreferencesKey("npu_loaders")
        val NPU_MIN_TOKENS = intPreferencesKey("npu_min_tokens")
        val NPU_ROUTED = booleanPreferencesKey("npu_routed_prefill")
        val NPU_ROUTED_FULL_PCT = intPreferencesKey("npu_routed_full_pct")
        val NGRAM_MIN_MATCH = intPreferencesKey("ngram_min_match")
        val NGRAM_MAX_MATCH = intPreferencesKey("ngram_max_match")
        val DECISION_PREFIX_CACHE = stringPreferencesKey("decision_prefix_cache")
        val FORCE_CACHE = booleanPreferencesKey("force_cache")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val THINKING = booleanPreferencesKey("thinking_enabled_v2")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val WEB_SEARCH = booleanPreferencesKey("is_web_search_enabled")
        val WEB_SEARCH_MODE = stringPreferencesKey("web_search_mode")
        val TOOLS_FILES = booleanPreferencesKey("tools_files")
        val TOOLS_CODE = booleanPreferencesKey("tools_code")
        val TOOLS_UTILITIES = booleanPreferencesKey("tools_utilities")
        val TOOL_RESULT_CHARS = intPreferencesKey("tool_result_chars")
        val THINK_AFTER_TOOLS = booleanPreferencesKey("think_after_tools")
        val IMAGE_MAX_DIM = intPreferencesKey("image_max_dim")
        val VIDEO_FRAMES = intPreferencesKey("video_frames")
        val LAST_MMPROJ = stringPreferencesKey("last_mmproj_path")
        val BRAVE_API_KEY = stringPreferencesKey("brave_api_key")
        val SEARXNG_URL = stringPreferencesKey("searxng_url")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_PALETTES = stringPreferencesKey("custom_palettes")
        val LAST_MODEL = stringPreferencesKey("last_model_path")
        val AUTO_LOAD = booleanPreferencesKey("auto_load_last_model")
        val SERVER_ENABLED = booleanPreferencesKey("server_enabled")
        val SERVER_PORT = intPreferencesKey("server_port")
        val SERVER_API_KEY = stringPreferencesKey("server_api_key")
        val SERVER_REQUIRE_KEY = booleanPreferencesKey("server_require_key")
        val SERVER_BIND_ALL = booleanPreferencesKey("server_bind_all")
        val SERVER_FORCE = booleanPreferencesKey("server_force_settings")
        val SERVER_QUEUE_SEC = intPreferencesKey("server_queue_timeout_sec")
    }

    val settingsFlow: Flow<EngineSettings> = context.dataStore.data.map { p ->
        val d = EngineSettings()
        EngineSettings(
            contextLength = p[Keys.CONTEXT_LENGTH] ?: d.contextLength,
            threadCount = p[Keys.THREAD_COUNT] ?: d.threadCount,
            batchThreadCount = p[Keys.BATCH_THREAD_COUNT] ?: d.batchThreadCount,
            batchWidth = p[Keys.BATCH_WIDTH] ?: d.batchWidth,
            temperature = p[Keys.TEMPERATURE] ?: d.temperature,
            topP = p[Keys.TOP_P] ?: d.topP,
            topK = p[Keys.TOP_K] ?: d.topK,
            seed = p[Keys.SEED] ?: d.seed,
            cacheMb = p[Keys.CACHE_MB] ?: d.cacheMb,
            cacheCeilMb = p[Keys.CACHE_CEIL_MB] ?: d.cacheCeilMb,
            cacheFloorMb = p[Keys.CACHE_FLOOR_MB] ?: d.cacheFloorMb,
            ioThreads = p[Keys.IO_THREADS] ?: d.ioThreads,
            oDirect = p[Keys.O_DIRECT] ?: d.oDirect,
            overlap = p[Keys.OVERLAP] ?: d.overlap,
            ioTwoWave = p[Keys.IO_TWO_WAVE] ?: d.ioTwoWave,
            denseWeights = enumOrDefault(p[Keys.DENSE_WEIGHTS], d.denseWeights),
            rowStreamMb = p[Keys.ROW_STREAM_MB] ?: d.rowStreamMb,
            nExpertUsed = p[Keys.N_EXPERT_USED] ?: d.nExpertUsed,
            dropColdPct = p[Keys.DROP_COLD_PCT] ?: d.dropColdPct,
            dropRenorm = p[Keys.DROP_RENORM] ?: d.dropRenorm,
            dropInPrefill = p[Keys.DROP_IN_PREFILL] ?: d.dropInPrefill,
            substitutePct = p[Keys.SUBSTITUTE_PCT] ?: d.substitutePct,
            prefetchLayers = p[Keys.PREFETCH_LAYERS] ?: d.prefetchLayers,
            predictPrefetch = p[Keys.PREDICT_PREFETCH] ?: d.predictPrefetch,
            predictSpecMax = p[Keys.PREDICT_SPEC_MAX] ?: d.predictSpecMax,
            predictionProbe = p[Keys.PREDICTION_PROBE] ?: d.predictionProbe,
            routeAhead = p[Keys.ROUTE_AHEAD] ?: d.routeAhead,
            rowStream = p[Keys.ROW_STREAM] ?: d.rowStream,
            loadAllExpertsForBenchmark = p[Keys.LOAD_ALL_EXPERTS] ?: d.loadAllExpertsForBenchmark,
            releaseMmap = p[Keys.RELEASE_MMAP] ?: d.releaseMmap,
            spec = enumOrDefault(p[Keys.SPEC], d.spec),
            draftMax = p[Keys.DRAFT_MAX] ?: d.draftMax,
            mtpPMinPct = p[Keys.MTP_P_MIN_PCT] ?: d.mtpPMinPct,
            separateMtpDraftEnabled = p[Keys.SEPARATE_MTP_DRAFT_ENABLED] ?: d.separateMtpDraftEnabled,
            metricsCsv = p[Keys.METRICS_CSV] ?: d.metricsCsv,
            npuPrefill = p[Keys.NPU_PREFILL] ?: d.npuPrefill,
            npuLoaders = p[Keys.NPU_LOADERS] ?: d.npuLoaders,
            npuMinTokens = p[Keys.NPU_MIN_TOKENS] ?: d.npuMinTokens,
            npuRoutedPrefill = p[Keys.NPU_ROUTED] ?: d.npuRoutedPrefill,
            npuRoutedFullPct = p[Keys.NPU_ROUTED_FULL_PCT] ?: d.npuRoutedFullPct,
            ngramMinMatch = p[Keys.NGRAM_MIN_MATCH] ?: d.ngramMinMatch,
            ngramMaxMatch = p[Keys.NGRAM_MAX_MATCH] ?: d.ngramMaxMatch,
            decisionPrefixCache = enumOrDefault(p[Keys.DECISION_PREFIX_CACHE], d.decisionPrefixCache),
            forceCache = p[Keys.FORCE_CACHE] ?: d.forceCache,
            maxTokens = p[Keys.MAX_TOKENS] ?: d.maxTokens,
            thinkingEnabled = p[Keys.THINKING] ?: d.thinkingEnabled,
            systemPrompt = p[Keys.SYSTEM_PROMPT] ?: d.systemPrompt,
            isWebSearchEnabled = p[Keys.WEB_SEARCH] ?: d.isWebSearchEnabled,
            webSearchMode = enumOrDefault(p[Keys.WEB_SEARCH_MODE], d.webSearchMode),
            toolsFilesEnabled = p[Keys.TOOLS_FILES] ?: d.toolsFilesEnabled,
            toolsCodeEnabled = p[Keys.TOOLS_CODE] ?: d.toolsCodeEnabled,
            toolsUtilitiesEnabled = p[Keys.TOOLS_UTILITIES] ?: d.toolsUtilitiesEnabled,
            toolResultChars = p[Keys.TOOL_RESULT_CHARS] ?: d.toolResultChars,
            thinkAfterTools = p[Keys.THINK_AFTER_TOOLS] ?: d.thinkAfterTools,
            imageMaxDim = p[Keys.IMAGE_MAX_DIM] ?: d.imageMaxDim,
            videoFrames = p[Keys.VIDEO_FRAMES] ?: d.videoFrames,
            lastMmprojPath = p[Keys.LAST_MMPROJ] ?: d.lastMmprojPath,
            braveApiKey = p[Keys.BRAVE_API_KEY] ?: d.braveApiKey,
            searxngUrl = p[Keys.SEARXNG_URL] ?: d.searxngUrl,
            themeId = p[Keys.THEME_ID] ?: d.themeId,
            customPalettesJson = p[Keys.CUSTOM_PALETTES] ?: d.customPalettesJson,
            lastModelPath = p[Keys.LAST_MODEL] ?: d.lastModelPath,
            autoLoadLastModel = p[Keys.AUTO_LOAD] ?: d.autoLoadLastModel,
            serverEnabled = p[Keys.SERVER_ENABLED] ?: d.serverEnabled,
            serverPort = p[Keys.SERVER_PORT] ?: d.serverPort,
            serverApiKey = p[Keys.SERVER_API_KEY] ?: d.serverApiKey,
            serverRequireKey = p[Keys.SERVER_REQUIRE_KEY] ?: d.serverRequireKey,
            serverBindAll = p[Keys.SERVER_BIND_ALL] ?: d.serverBindAll,
            serverForceSettings = p[Keys.SERVER_FORCE] ?: d.serverForceSettings,
            serverQueueTimeoutSec = p[Keys.SERVER_QUEUE_SEC] ?: d.serverQueueTimeoutSec
        )
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    // ---- engine ----
    /** Writes every setting Auto settings owns in one DataStore transaction (used when a save is switched on). */
    suspend fun applyTunedValues(v: TunedValues) {
        context.dataStore.edit { p ->
            p[Keys.CONTEXT_LENGTH] = v.contextLength.coerceAtLeast(1)
            p[Keys.THREAD_COUNT] = v.threadCount.coerceIn(1, 32)
            p[Keys.BATCH_THREAD_COUNT] = v.batchThreadCount.coerceIn(1, 32)
            p[Keys.BATCH_WIDTH] = v.batchWidth.coerceIn(32, 8192)
            p[Keys.CACHE_MB] = v.cacheMb.coerceAtLeast(CACHE_AUTO_MB)
            p[Keys.IO_THREADS] = v.ioThreads.coerceIn(1, 8)
            p[Keys.O_DIRECT] = v.oDirect
            p[Keys.OVERLAP] = v.overlap
            p[Keys.IO_TWO_WAVE] = v.ioTwoWave
            p[Keys.N_EXPERT_USED] = v.nExpertUsed.coerceAtLeast(0)
            p[Keys.DROP_COLD_PCT] = v.dropColdPct.coerceIn(0, 100)
            p[Keys.DROP_RENORM] = v.dropRenorm
            p[Keys.SUBSTITUTE_PCT] = v.substitutePct.coerceIn(0, 100)
            p[Keys.PREFETCH_LAYERS] = v.prefetchLayers.coerceIn(0, 8)
            p[Keys.PREDICT_PREFETCH] = v.predictPrefetch
            p[Keys.PREDICT_SPEC_MAX] = v.predictSpecMax.coerceIn(0, 8)
            p[Keys.ROUTE_AHEAD] = v.routeAhead.coerceIn(0, 8)
            p[Keys.SPEC] = v.spec.name
            p[Keys.DRAFT_MAX] = v.draftMax.coerceIn(1, 8)
            p[Keys.NPU_PREFILL] = v.npuPrefill
        }
    }

    suspend fun updateContextLength(v: Int) = set(Keys.CONTEXT_LENGTH, v.coerceAtLeast(1))
    suspend fun updateThreadCount(v: Int) = set(Keys.THREAD_COUNT, v.coerceIn(1, 32))
    suspend fun updateBatchThreadCount(v: Int) = set(Keys.BATCH_THREAD_COUNT, v.coerceIn(1, 32))
    suspend fun updateBatchWidth(v: Int) = set(Keys.BATCH_WIDTH, v.coerceIn(32, 8192))
    suspend fun updateTemperature(v: Float) = set(Keys.TEMPERATURE, v.coerceIn(0f, 2f))
    suspend fun updateTopP(v: Float) = set(Keys.TOP_P, v.coerceIn(0.01f, 1f))
    suspend fun updateTopK(v: Int) = set(Keys.TOP_K, v.coerceIn(0, 200))
    suspend fun updateSeed(v: Int) = set(Keys.SEED, v.coerceAtLeast(-1))
    suspend fun updateCacheMb(v: Int) = set(Keys.CACHE_MB, v.coerceAtLeast(CACHE_AUTO_MB))
    suspend fun updateCacheCeilMb(v: Int) = set(Keys.CACHE_CEIL_MB, v.coerceAtLeast(0))
    suspend fun updateCacheFloorMb(v: Int) = set(Keys.CACHE_FLOOR_MB, v.coerceAtLeast(0))
    suspend fun updateIoThreads(v: Int) = set(Keys.IO_THREADS, v.coerceIn(1, 8))
    suspend fun updateODirect(v: Boolean) = set(Keys.O_DIRECT, v)
    suspend fun updateOverlap(v: Boolean) = set(Keys.OVERLAP, v)
    suspend fun updateIoTwoWave(v: Boolean) = set(Keys.IO_TWO_WAVE, v)
    suspend fun updateDenseWeights(v: DenseWeights) = set(Keys.DENSE_WEIGHTS, v.name)
    suspend fun updateRowStreamMb(v: Int) = set(Keys.ROW_STREAM_MB, v.coerceIn(16, 4096))
    suspend fun updateNExpertUsed(v: Int) = set(Keys.N_EXPERT_USED, v.coerceAtLeast(0))
    suspend fun updateDropColdPct(v: Int) = set(Keys.DROP_COLD_PCT, v.coerceIn(0, 100))
    suspend fun updateDropRenorm(v: Boolean) = set(Keys.DROP_RENORM, v)
    suspend fun updateDropInPrefill(v: Boolean) = set(Keys.DROP_IN_PREFILL, v)
    suspend fun updateSubstitutePct(v: Int) = set(Keys.SUBSTITUTE_PCT, v.coerceIn(0, 100))
    suspend fun updatePrefetchLayers(v: Int) = set(Keys.PREFETCH_LAYERS, v.coerceIn(0, 8))
    suspend fun updatePredictPrefetch(v: Boolean) = set(Keys.PREDICT_PREFETCH, v)
    suspend fun updatePredictSpecMax(v: Int) = set(Keys.PREDICT_SPEC_MAX, v.coerceIn(0, 8))
    suspend fun updatePredictionProbe(v: Boolean) = set(Keys.PREDICTION_PROBE, v)
    suspend fun updateRouteAhead(v: Int) = set(Keys.ROUTE_AHEAD, v.coerceIn(0, 8))
    suspend fun updateRowStream(v: Boolean) = set(Keys.ROW_STREAM, v)
    suspend fun updateLoadAllExpertsForBenchmark(v: Boolean) = set(Keys.LOAD_ALL_EXPERTS, v)
    suspend fun updateReleaseMmap(v: Boolean) = set(Keys.RELEASE_MMAP, v)
    suspend fun updateSpec(v: SpecSource) = set(Keys.SPEC, v.name)
    suspend fun updateDraftMax(v: Int) = set(Keys.DRAFT_MAX, v.coerceIn(1, 8))
    suspend fun updateMtpPMinPct(v: Int) = set(Keys.MTP_P_MIN_PCT, v.coerceIn(0, 100))
    suspend fun updateSeparateMtpDraftEnabled(v: Boolean) = set(Keys.SEPARATE_MTP_DRAFT_ENABLED, v)
    suspend fun updateMetricsCsv(v: Boolean) = set(Keys.METRICS_CSV, v)
    suspend fun updateNpuPrefill(v: Boolean) = set(Keys.NPU_PREFILL, v)
    suspend fun updateNpuLoaders(v: Int) = set(Keys.NPU_LOADERS, v.coerceIn(1, 16))
    suspend fun updateNpuMinTokens(v: Int) = set(Keys.NPU_MIN_TOKENS, v.coerceIn(2, 2048))
    suspend fun updateNpuRoutedPrefill(v: Boolean) = set(Keys.NPU_ROUTED, v)
    suspend fun updateNpuRoutedFullPct(v: Int) = set(Keys.NPU_ROUTED_FULL_PCT, v.coerceIn(1, 100))
    suspend fun updateNgramMinMatch(v: Int) = set(Keys.NGRAM_MIN_MATCH, v.coerceIn(1, 64))
    suspend fun updateNgramMaxMatch(v: Int) = set(Keys.NGRAM_MAX_MATCH, v.coerceIn(1, 64))
    suspend fun updateDecisionPrefixCache(v: DecisionPrefixCache) = set(Keys.DECISION_PREFIX_CACHE, v.name)
    suspend fun updateForceCache(v: Boolean) = set(Keys.FORCE_CACHE, v)

    // ---- per request ----
    suspend fun updateMaxTokens(v: Int) = set(Keys.MAX_TOKENS, v)
    suspend fun updateThinkingEnabled(v: Boolean) = set(Keys.THINKING, v)
    suspend fun updateSystemPrompt(v: String) = set(Keys.SYSTEM_PROMPT, v)
    suspend fun updateWebSearchEnabled(v: Boolean) = set(Keys.WEB_SEARCH, v)
    suspend fun updateWebSearchMode(v: WebSearchMode) = set(Keys.WEB_SEARCH_MODE, v.name)
    suspend fun updateToolsFilesEnabled(v: Boolean) = set(Keys.TOOLS_FILES, v)
    suspend fun updateToolsCodeEnabled(v: Boolean) = set(Keys.TOOLS_CODE, v)
    suspend fun updateToolsUtilitiesEnabled(v: Boolean) = set(Keys.TOOLS_UTILITIES, v)
    suspend fun updateToolResultChars(v: Int) = set(Keys.TOOL_RESULT_CHARS, v.coerceIn(500, 20_000))
    suspend fun updateThinkAfterTools(v: Boolean) = set(Keys.THINK_AFTER_TOOLS, v)
    suspend fun updateImageMaxDim(v: Int) = set(Keys.IMAGE_MAX_DIM, v.coerceIn(224, 1344))
    suspend fun updateVideoFrames(v: Int) = set(Keys.VIDEO_FRAMES, v.coerceIn(1, 16))
    suspend fun updateLastMmprojPath(v: String) = set(Keys.LAST_MMPROJ, v)
    suspend fun updateBraveApiKey(v: String) = set(Keys.BRAVE_API_KEY, v)
    suspend fun updateSearxngUrl(v: String) = set(Keys.SEARXNG_URL, v)

    // ---- app ----
    suspend fun updateThemeId(v: String) = set(Keys.THEME_ID, v)
    suspend fun updateCustomPalettesJson(v: String) = set(Keys.CUSTOM_PALETTES, v)
    suspend fun updateLastModelPath(v: String) = set(Keys.LAST_MODEL, v)
    suspend fun updateAutoLoadLastModel(v: Boolean) = set(Keys.AUTO_LOAD, v)

    // ---- local API server ----
    suspend fun updateServerEnabled(v: Boolean) = set(Keys.SERVER_ENABLED, v)
    suspend fun updateServerPort(v: Int) = set(Keys.SERVER_PORT, v.coerceIn(1024, 65535))
    suspend fun updateServerApiKey(v: String) = set(Keys.SERVER_API_KEY, v.trim())
    suspend fun updateServerRequireKey(v: Boolean) = set(Keys.SERVER_REQUIRE_KEY, v)
    suspend fun updateServerBindAll(v: Boolean) = set(Keys.SERVER_BIND_ALL, v)
    suspend fun updateServerForceSettings(v: Boolean) = set(Keys.SERVER_FORCE, v)
    suspend fun updateServerQueueTimeoutSec(v: Int) = set(Keys.SERVER_QUEUE_SEC, v.coerceIn(1, 600))
}

private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
