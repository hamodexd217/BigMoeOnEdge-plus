package com.bigmoe.onedge.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One scripted native generation. */
data class ScriptedTurn(
    val tokens: List<String> = emptyList(),
    val ok: Boolean = true,
    val cancelled: Boolean = false,
    val error: String = "",
    val stats: GenerationStats = GenerationStats(nGenerated = 5, tokensPerSecond = 2.5, prefillSeconds = 0.5, cacheHitPct = 40.0),
    /** If set, generate() blocks (like the real engine) until stop() is called, after emitting [tokens]. */
    val blockUntilStopped: Boolean = false
)

data class RecordedCall(
    val system: String,
    val history: List<ChatTurn>,
    val prompt: String,
    val maxTokens: Int,
    val clearKv: Boolean,
    val think: Boolean,
    val imageCount: Int = 0
)

/** Scriptable stand-in for libonedge-engine.so: no JNI, runs on the JVM. */
class FakeEngineBackend(
    private val info: Map<String, String> = mapOf("arch" to "fake", "n_ctx" to "4096", "n_expert_used" to "8", "load_seconds" to "1.0", "think_control" to "template")
) : EngineBackend {

    val script = ArrayDeque<ScriptedTurn>()
    val calls = ArrayList<RecordedCall>()
    var loadError: String? = null
    var freed = false

    private val stopLatch = CountDownLatch(1)
    @Volatile var stopCalls = 0
    val started = CountDownLatch(1)

    var lastCsvPath: String? = null
    var lastConfig: LoadConfig? = null
    override fun capabilities() = EngineCaps(overlapHook = true, multimodal = true, npuPrefill = false)

    override fun load(modelPath: String, config: LoadConfig, csvPath: String?, mmprojPath: String?): String? {
        lastCsvPath = csvPath
        lastConfig = config
        return loadError
    }
    override fun modelInfo(): Map<String, String> = info

    override fun decide(request: DecisionRequest): DecisionResult =
        DecisionResult(
            ok = request.choices.size >= 2,
            error = if (request.choices.size >= 2) "" else "Give at least two options.",
            scores = request.choices.mapIndexed { i, choice -> ChoiceScore(('A'.code + i).toChar().toString(), choice, 1.0 / request.choices.size.coerceAtLeast(1)) },
            best = 0,
            metrics = "fake"
        )

    override fun generate(
        system: String, historyFlat: List<ChatTurn>, prompt: String, maxTokens: Int,
        clearKv: Boolean, think: Boolean, images: List<ImageData>, callback: NativeGenerationCallback
    ) {
        calls.add(RecordedCall(system, historyFlat, prompt, maxTokens, clearKv, think, images.size))
        val turn = script.removeFirstOrNull() ?: ScriptedTurn(ok = false, error = "script exhausted")
        started.countDown()
        val answer = StringBuilder()
        var stopped = false
        for (t in turn.tokens) {
            answer.append(t)
            if (!callback.onToken(t.toByteArray(Charsets.UTF_8), ByteArray(0))) { stopped = true; break }
        }
        if (turn.blockUntilStopped && !stopped) stopLatch.await(10, TimeUnit.SECONDS)
        val cancelled = turn.cancelled || stopped || turn.blockUntilStopped
        callback.onFinished(
            ok = turn.ok, cancelled = cancelled, error = turn.error.toByteArray(),
            answer = answer.toString().toByteArray(Charsets.UTF_8), reasoning = ByteArray(0),
            nGenerated = turn.stats.nGenerated, nPrompt = turn.stats.nPrompt, nPast = turn.stats.nPast,
            tokensPerSecond = turn.stats.tokensPerSecond, prefillSeconds = turn.stats.prefillSeconds,
            cacheHitPct = turn.stats.cacheHitPct
        )
    }

    override fun stop() {
        stopCalls++
        stopLatch.countDown()
    }

    override fun free() {
        freed = true
    }
}
