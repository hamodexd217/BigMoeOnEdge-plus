package com.bigmoe.onedge.core

/**
 * Receives events from native-bridge.cpp's nativeGenerate(). Both methods are invoked from the native
 * thread that called nativeGenerate() (i.e. the IO thread running the generation), never concurrently.
 *
 * All text arrives as raw UTF-8 bytes: native code never builds a jstring, because JNI's *modified*
 * UTF-8 cannot represent emoji and would mangle any multi-byte character split across tokens.
 * Decode with [decodeUtf8].
 */
interface NativeGenerationCallback {
    /**
     * Called once for every confirmed generated token. The visible answer/reasoning deltas may both be
     * empty for a hidden/special token; progress accounting must still count that callback. Deltas always
     * end on a complete UTF-8 character.
     * @return true to continue, false to cancel the generation.
     */
    fun onToken(answerDelta: ByteArray, reasoningDelta: ByteArray): Boolean

    /**
     * Always called exactly once, last. [answer]/[reasoning] are the authoritative complete texts
     * (chat-template aware). [cacheHitPct] is -1 when the expert cache is off.
     */
    fun onFinished(
        ok: Boolean,
        cancelled: Boolean,
        error: ByteArray,
        answer: ByteArray,
        reasoning: ByteArray,
        nGenerated: Int,
        nPrompt: Int,
        nPast: Int,
        tokensPerSecond: Double,
        prefillSeconds: Double,
        cacheHitPct: Double
    )
}

/**
 * Receives events from native-bridge.cpp's nativeGenerateChat() (the local API server's path). Same threading and
 * UTF-8 rules as [NativeGenerationCallback]. The parameter order of [onFinished] is the JNI signature
 * "(ZZ[B[B[B[[B[B[BIIIIDDD)V" in native-bridge.cpp; do not reorder.
 */
interface NativeChatCallback {
    /** Once per confirmed token. Deltas may both be empty. Return false to cancel. */
    fun onToken(answerDelta: ByteArray, reasoningDelta: ByteArray): Boolean

    /**
     * Always called exactly once, last. [toolCalls] is flat: id0, name0, arguments0, id1, ... ([id] may be empty).
     * [stopReason] is "end_turn" | "max_tokens" | "stop_sequence" | "tool_use" (empty on failure).
     * [nPromptTotal] is the rendered prompt length, [nReused] how much of it was already in the KV.
     */
    fun onFinished(
        ok: Boolean,
        cancelled: Boolean,
        error: ByteArray,
        answer: ByteArray,
        reasoning: ByteArray,
        toolCalls: Array<ByteArray>,
        stopReason: ByteArray,
        stopSequence: ByteArray,
        nGenerated: Int,
        nPromptTotal: Int,
        nReused: Int,
        nPast: Int,
        tokensPerSecond: Double,
        prefillSeconds: Double,
        cacheHitPct: Double
    )
}

/** The JNI surface. Function names are bound to native-bridge.cpp by name; do not rename. */
class EngineNativeBridge {

    init {
        System.loadLibrary("onedge-engine")
    }

    /**
     * Blocking (tens of seconds for big models). Returns an EMPTY array on success, else the error text.
     * [ints]/[floats] are LoadConfig.toIntArray()/toFloatArray(); [csvPath] is empty for "no metrics CSV".
     */
    external fun nativeLoadModel(
        modelPath: ByteArray,
        ints: IntArray,
        floats: FloatArray,
        csvPath: ByteArray,
        mmprojPath: ByteArray
    ): ByteArray

    /** Bit 0: expert-ready hook compiled in (overlap possible). Bit 1: built with multimodal (mtmd). */
    external fun nativeCapabilities(): Int

    /** "key=value\n" lines: arch, n_ctx, n_expert_used, load_seconds, think_control, overlap, streaming, vision. */
    external fun nativeModelInfo(): ByteArray

    /**
     * Blocking generation. [system] and [historyFlat] ([role0, content0, role1, content1, ...]) are only
     * used when [clearKv] is true. [maxTokens] <= 0 means "fill the remaining context".
     */
    external fun nativeGenerate(
        system: ByteArray,
        historyFlat: Array<ByteArray>,
        prompt: ByteArray,
        maxTokens: Int,
        clearKv: Boolean,
        think: Boolean,
        imageDims: IntArray,
        imageData: Array<ByteArray>,
        callback: NativeGenerationCallback
    ): Boolean

    /**
     * Blocking, stateless chat generation (patch 0004). [messagesJson] is an OpenAI-format messages array,
     * [toolsJson] an OpenAI `tools` array or empty, [toolChoice] "", "auto", "required" or "none". The engine reuses
     * whatever KV prefix the previous request left behind. [maxTokens] <= 0 = as much as the context allows.
     * When [overrideSampling] is false, [temperature]/[topP]/[topK]/[seed] are ignored (the session's own sampler is used).
     */
    external fun nativeGenerateChat(
        messagesJson: ByteArray,
        toolsJson: ByteArray,
        toolChoice: ByteArray,
        stop: Array<ByteArray>,
        maxTokens: Int,
        think: Boolean,
        overrideSampling: Boolean,
        temperature: Float,
        topP: Float,
        topK: Int,
        seed: Int,
        callback: NativeChatCallback
    ): Boolean

    external fun nativeDecide(
        prefix: ByteArray,
        suffix: ByteArray,
        choices: Array<ByteArray>,
        reusePrefix: Boolean
    ): ByteArray

    external fun nativeStopGeneration()

    /** Cancels any generation, waits for it to unwind, then releases the model. */
    external fun nativeFreeModel()
}

fun decodeUtf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
fun encodeUtf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
