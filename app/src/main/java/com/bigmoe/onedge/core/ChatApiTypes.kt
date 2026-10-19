package com.bigmoe.onedge.core

/** Per-request sampling for the API path. Absent (null in [ChatGenRequest]) = the loaded session's own sampler. */
data class SamplingOverride(
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    /** -1 = random. */
    val seed: Int = -1
)

/**
 * One stateless chat generation (the local API server). [messagesJson]/[toolsJson] are OpenAI wire format; the
 * server translates Anthropic requests into it. Unlike [GenerationRequest] there is no engine-state key: the engine
 * compares the rendered prompt with its KV and reuses the common prefix on its own.
 */
data class ChatGenRequest(
    val messagesJson: String,
    val toolsJson: String = "",
    /** "", "auto", "required" or "none". */
    val toolChoice: String = "",
    /** <= 0 = as long as the context allows; larger values are clamped to the room left. */
    val maxTokens: Int,
    val think: Boolean,
    val stop: List<String> = emptyList(),
    val sampling: SamplingOverride? = null
)

data class ChatToolCall(val id: String, val name: String, val arguments: String)

data class ChatStats(
    val nGenerated: Int = 0,
    /** Length of the rendered prompt in tokens. */
    val nPromptTotal: Int = 0,
    /** Of those, tokens already in the KV (not prefilled again). */
    val nReused: Int = 0,
    val nPast: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val prefillSeconds: Double = 0.0,
    val cacheHitPct: Double = -1.0
)

sealed interface ChatEvent {
    data class Answer(val delta: String) : ChatEvent
    data class Reasoning(val delta: String) : ChatEvent

    /**
     * Last event of every [EngineController.generateChat] flow. [stopReason] is "end_turn", "max_tokens",
     * "stop_sequence" or "tool_use" (empty when [ok] is false). [preempted] = the in-app chat took the engine away.
     */
    data class Finished(
        val ok: Boolean,
        val cancelled: Boolean,
        val error: String,
        val answer: String,
        val reasoning: String,
        val toolCalls: List<ChatToolCall>,
        val stopReason: String,
        val stopSequence: String,
        val stats: ChatStats,
        val preempted: Boolean = false
    ) : ChatEvent
}

/** Parses the flat id/name/arguments triples the JNI layer sends. Incomplete trailing entries are dropped. */
fun parseNativeToolCalls(flat: Array<ByteArray>): List<ChatToolCall> {
    val out = ArrayList<ChatToolCall>(flat.size / 3)
    var i = 0
    while (i + 2 < flat.size) {
        out.add(ChatToolCall(decodeUtf8(flat[i]), decodeUtf8(flat[i + 1]), decodeUtf8(flat[i + 2])))
        i += 3
    }
    return out
}
