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

/** One scripted stateless chat generation (the API path). */
data class ScriptedChat(
    val answerTokens: List<String> = emptyList(),
    val reasoning: String = "",
    val toolCalls: List<ChatToolCall> = emptyList(),
    val stopReason: String = "end_turn",
    val ok: Boolean = true,
    val error: String = "",
    /** If set, generateChat() blocks until stop() is called, after emitting the tokens, and reports cancelled. */
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
    var draftLoadError: String? = null
    val loadAttempts = ArrayList<Pair<LoadConfig, String?>>()
    var lastDraftPath: String? = null
    var freed = false

    private val stopLatch = CountDownLatch(1)
    @Volatile var stopCalls = 0
    val started = CountDownLatch(1)

    var lastCsvPath: String? = null
    var lastConfig: LoadConfig? = null
    override fun capabilities() = EngineCaps(overlapHook = true, multimodal = true, npuPrefill = false)

    override fun load(modelPath: String, config: LoadConfig, csvPath: String?, mmprojPath: String?, draftPath: String?): String? {
        lastCsvPath = csvPath
        lastConfig = config
        lastDraftPath = draftPath
        loadAttempts += config to draftPath
        return if (draftPath != null) draftLoadError ?: loadError else loadError
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

    val chatScript = ArrayDeque<ScriptedChat>()
    val chatCalls = ArrayList<ChatGenRequest>()
    val chatStarted = CountDownLatch(1)

    override fun generateChat(request: ChatGenRequest, callback: NativeChatCallback) {
        chatCalls.add(request)
        val turn = chatScript.removeFirstOrNull() ?: ScriptedChat(ok = false, error = "chat script exhausted")
        chatStarted.countDown()
        val answer = StringBuilder()
        var stopped = false
        for (t in turn.answerTokens) {
            answer.append(t)
            if (!callback.onToken(t.toByteArray(Charsets.UTF_8), ByteArray(0))) { stopped = true; break }
        }
        if (turn.blockUntilStopped && !stopped) stopLatch.await(10, TimeUnit.SECONDS)
        val cancelled = stopped || turn.blockUntilStopped
        val flat = ArrayList<ByteArray>()
        for (c in turn.toolCalls) {
            flat.add(c.id.toByteArray(Charsets.UTF_8))
            flat.add(c.name.toByteArray(Charsets.UTF_8))
            flat.add(c.arguments.toByteArray(Charsets.UTF_8))
        }
        callback.onFinished(
            ok = turn.ok, cancelled = cancelled, error = turn.error.toByteArray(Charsets.UTF_8),
            answer = answer.toString().toByteArray(Charsets.UTF_8), reasoning = turn.reasoning.toByteArray(Charsets.UTF_8),
            toolCalls = flat.toTypedArray(), stopReason = turn.stopReason.toByteArray(Charsets.UTF_8),
            stopSequence = ByteArray(0), nGenerated = 5, nPromptTotal = 100, nReused = 60, nPast = 105,
            tokensPerSecond = 4.0, prefillSeconds = 1.0, cacheHitPct = -1.0
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
