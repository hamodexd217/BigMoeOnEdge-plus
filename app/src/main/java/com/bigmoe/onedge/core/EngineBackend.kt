package com.bigmoe.onedge.core

/**
 * The engine as the app sees it. The production implementation is [NativeEngineBackend]; tests use a fake,
 * so the controller/agent logic runs on the JVM without libonedge-engine.so.
 */
interface EngineBackend {
    /** Blocking. Returns null on success, otherwise the error message. */
    fun load(modelPath: String, config: LoadConfig, csvPath: String?, mmprojPath: String?): String?

    /** Build-time features of the native library. */
    fun capabilities(): EngineCaps

    /** Parsed "key=value" lines from the loaded model (arch, n_ctx, ...). */
    fun modelInfo(): Map<String, String>

    /** Blocking; see [NativeGenerationCallback]. */
    fun generate(
        system: String,
        historyFlat: List<ChatTurn>,
        prompt: String,
        maxTokens: Int,
        clearKv: Boolean,
        think: Boolean,
        images: List<ImageData>,
        callback: NativeGenerationCallback
    )

    /** Thread-safe; makes a running generate() return promptly. */
    /** Blocking multiple-choice decision; returns a probability distribution over [choices]. */
    fun decide(request: DecisionRequest): DecisionResult

    fun stop()

    /** Cancels, waits, releases. */
    fun free()
}

class NativeEngineBackend(private val bridge: EngineNativeBridge) : EngineBackend {

    override fun capabilities(): EngineCaps = EngineCaps.fromBits(bridge.nativeCapabilities())

    override fun load(modelPath: String, config: LoadConfig, csvPath: String?, mmprojPath: String?): String? {
        val err = bridge.nativeLoadModel(
            modelPath = encodeUtf8(modelPath),
            ints = config.toIntArray(),
            floats = config.toFloatArray(),
            csvPath = encodeUtf8(csvPath.orEmpty()),
            mmprojPath = encodeUtf8(mmprojPath.orEmpty())
        )
        return if (err.isEmpty()) null else decodeUtf8(err)
    }

    override fun modelInfo(): Map<String, String> =
        decodeUtf8(bridge.nativeModelInfo()).lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()

    override fun generate(
        system: String,
        historyFlat: List<ChatTurn>,
        prompt: String,
        maxTokens: Int,
        clearKv: Boolean,
        think: Boolean,
        images: List<ImageData>,
        callback: NativeGenerationCallback
    ) {
        val flat = ArrayList<ByteArray>(historyFlat.size * 2)
        for (t in historyFlat) {
            flat.add(encodeUtf8(t.role))
            flat.add(encodeUtf8(t.content))
        }
        bridge.nativeGenerate(
            system = encodeUtf8(system),
            historyFlat = flat.toTypedArray(),
            prompt = encodeUtf8(prompt),
            maxTokens = maxTokens,
            clearKv = clearKv,
            think = think,
            imageDims = IntArray(images.size * 2) { i -> if (i % 2 == 0) images[i / 2].width else images[i / 2].height },
            imageData = Array(images.size) { images[it].rgb },
            callback = callback
        )
    }

    override fun decide(request: DecisionRequest): DecisionResult {
        if (request.choices.size < 2) return DecisionResult(ok = false, error = "Give at least two options.")
        val json = decodeUtf8(
            bridge.nativeDecide(
                encodeUtf8(request.prefix),
                encodeUtf8(request.suffix),
                request.choices.map(::encodeUtf8).toTypedArray(),
                request.reusePrefix
            )
        )
        return parseDecision(json, request.choices)
    }

    override fun stop() = bridge.nativeStopGeneration()


    override fun free() = bridge.nativeFreeModel()
}


private fun parseDecision(json: String, choices: List<String>): DecisionResult {
    return runCatching {
        val o = org.json.JSONObject(json)
        val ok = o.optBoolean("ok")
        val error = o.optString("error")
        val cancelled = o.optBoolean("cancelled")
        val logs = o.optJSONArray("choice_logp")
        val scores = choices.indices.map { i ->
            val lp = logs?.optDouble(i, Double.NEGATIVE_INFINITY) ?: Double.NEGATIVE_INFINITY
            val p = if (lp.isFinite()) kotlin.math.exp(lp) else 0.0
            ChoiceScore(
                label = ('A'.code + i).toChar().toString(),
                text = choices[i],
                probability = p
            )
        }
        val metrics = buildString {
            append(String.format(java.util.Locale.US, "prefill %.1fs", o.optDouble("prefill_s", 0.0)))
            append(" (${o.optInt("n_prefilled", 0)} tok")
            val reused = o.optInt("n_reused", 0)
            if (reused > 0) append(", $reused reused")
            append(")")
            val mib = o.optDouble("prefix_state_mib", 0.0)
            if (mib > 0) append(String.format(java.util.Locale.US, " · context kept %.0f MiB", mib))
        }
        DecisionResult(ok = ok, error = error, cancelled = cancelled, scores = scores, best = o.optInt("best", -1), metrics = metrics)
    }.getOrElse { DecisionResult(ok = false, error = "Malformed decide response: ${it.message}") }
}
