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
     * One increment of the answer and/or of the reasoning ("thinking") span; either array may be empty
     * but not both. Deltas always end on a complete UTF-8 character.
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
