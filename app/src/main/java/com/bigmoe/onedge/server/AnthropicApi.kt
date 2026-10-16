package com.bigmoe.onedge.server

import com.bigmoe.onedge.core.ChatEvent
import com.bigmoe.onedge.core.ChatToolCall
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom

/** A client mistake; becomes HTTP 400 `invalid_request_error`. */
class ApiRequestException(message: String) : Exception(message)

/** One server-sent event. [event] null = a bare `data:` line (OpenAI style). */
data class SseFrame(val event: String?, val data: String) {
    fun encode(): String = if (event != null) "event: $event\ndata: $data\n\n" else "data: $data\n\n"
}

internal object Ids {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val rnd = SecureRandom()
    fun random(prefix: String, n: Int = 24): String {
        val sb = StringBuilder(prefix)
        for (i in 0 until n) sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)])
        return sb.toString()
    }
}

/** Anthropic Messages API <-> the engine's OpenAI-format chat request. Pure functions: no Android, no engine. */
object AnthropicApi {

    // ------------------------------------------------------------------------------------------------ request

    fun parse(body: JSONObject): ParsedChat {
        if (!body.has("messages") || body.optJSONArray("messages") == null) throw ApiRequestException("'messages' is required and must be an array")
        val ignored = ArrayList<String>()
        val oai = JSONArray()

        // System prompt: a string, or an array of text blocks (concatenated, like llama-server does).
        val sys = body.opt("system")
        val sysParts = ArrayList<String>()
        when (sys) {
            is String -> sysParts.add(cleanSystemText(sys))
            is JSONArray -> for (i in 0 until sys.length()) {
                val b = sys.optJSONObject(i) ?: continue
                if (b.optString("type") == "text") sysParts.add(cleanSystemText(b.optString("text")))
            }
        }
        val sysText = sysParts.filter { it.isNotEmpty() }.joinToString("\n\n")
        if (sysText.isNotEmpty()) oai.put(JSONObject().put("role", "system").put("content", sysText))

        val msgs = body.getJSONArray("messages")
        var droppedImages = false
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: throw ApiRequestException("messages[$i] is not an object")
            val role = m.optString("role")
            if (role != "user" && role != "assistant") throw ApiRequestException("messages[$i].role must be 'user' or 'assistant'")
            val content = m.opt("content")
            if (content == null || content == JSONObject.NULL) {
                continue
            }
            if (content is String) {
                oai.put(JSONObject().put("role", role).put("content", content))
                continue
            }
            if (content !is JSONArray) throw ApiRequestException("messages[$i].content must be a string or an array of blocks")

            val texts = ArrayList<String>()
            val toolCalls = JSONArray()
            val toolResults = ArrayList<JSONObject>()
            val reasoning = StringBuilder()
            for (j in 0 until content.length()) {
                val b = content.optJSONObject(j) ?: continue
                when (b.optString("type")) {
                    "text" -> texts.add(b.optString("text"))
                    "thinking" -> reasoning.append(b.optString("thinking"))
                    "redacted_thinking" -> {}
                    "image" -> {
                        droppedImages = true
                        texts.add(IMAGE_PLACEHOLDER)
                    }
                    "tool_use" -> {
                        val input = b.optJSONObject("input") ?: JSONObject()
                        toolCalls.put(
                            JSONObject()
                                .put("id", b.optString("id"))
                                .put("type", "function")
                                .put("function", JSONObject().put("name", b.optString("name")).put("arguments", input.toString()))
                        )
                    }
                    "tool_result" -> {
                        val text = StringBuilder()
                        val rc = b.opt("content")
                        if (rc is String) text.append(rc)
                        else if (rc is JSONArray) {
                            for (k in 0 until rc.length()) {
                                val c = rc.optJSONObject(k) ?: continue
                                when (c.optString("type")) {
                                    "text" -> text.append(c.optString("text"))
                                    "image" -> {
                                        droppedImages = true
                                        text.append(IMAGE_PLACEHOLDER)
                                    }
                                }
                            }
                        }
                        toolResults.add(
                            JSONObject().put("role", "tool").put("tool_call_id", b.optString("tool_use_id")).put("content", text.toString())
                        )
                    }
                    else -> {}
                }
            }

            if (role == "assistant") {
                if (texts.isNotEmpty() || toolCalls.length() > 0 || reasoning.isNotEmpty()) {
                    val a = JSONObject().put("role", "assistant").put("content", texts.joinToString("\n\n"))
                    if (toolCalls.length() > 0) a.put("tool_calls", toolCalls)
                    if (reasoning.isNotEmpty()) a.put("reasoning_content", reasoning.toString())
                    oai.put(a)
                }
            } else {
                // The tool results answer the PREVIOUS assistant turn's calls, so they come first; the user's own text
                // (Claude Code appends <system-reminder> blocks after the results) follows. llama-server's converter
                // emits them the other way round, which breaks the assistant->tool pairing templates expect.
                for (r in toolResults) oai.put(r)
                if (texts.isNotEmpty()) oai.put(JSONObject().put("role", "user").put("content", texts.joinToString("\n\n")))
            }
        }
        if (droppedImages) ignored.add("image blocks (replaced by a text placeholder)")

        // Tools.
        val oaiTools = JSONArray()
        val tools = body.optJSONArray("tools")
        if (tools != null) {
            for (i in 0 until tools.length()) {
                val t = tools.optJSONObject(i) ?: continue
                val name = t.optString("name")
                if (name.isEmpty()) continue
                oaiTools.put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject()
                            .put("name", name)
                            .put("description", t.optString("description"))
                            .put("parameters", t.optJSONObject("input_schema") ?: JSONObject().put("type", "object"))
                    )
                )
            }
        }
        var toolChoice = ""
        val tc = body.optJSONObject("tool_choice")
        if (tc != null && oaiTools.length() > 0) {
            toolChoice = when (tc.optString("type")) {
                "auto" -> "auto"
                "any", "tool" -> "required"
                "none" -> "none"
                else -> ""
            }
        }

        val stops = ArrayList<String>()
        val ss = body.optJSONArray("stop_sequences")
        if (ss != null) for (i in 0 until ss.length()) {
            val s = ss.optString(i)
            if (s.isNotEmpty()) stops.add(s)
        }

        var thinking: Boolean? = null
        val th = body.optJSONObject("thinking")
        if (th != null) {
            when (th.optString("type")) {
                "enabled" -> {
                    thinking = true
                    if (th.has("budget_tokens")) ignored.add("thinking.budget_tokens")
                }
                "disabled" -> thinking = false
            }
        }

        val maxTokens = body.optIntOrNull("max_tokens")
        // 0 or less would mean "as much as the context allows" to the engine; Anthropic rejects it too.
        if (maxTokens != null && maxTokens < 1) throw ApiRequestException("max_tokens must be at least 1")

        return ParsedChat(
            messagesJson = oai.toString(),
            toolsJson = if (oaiTools.length() > 0) oaiTools.toString() else "",
            toolChoice = toolChoice,
            stop = stops,
            maxTokens = maxTokens,
            temperature = body.optDoubleOrNull("temperature"),
            topP = body.optDoubleOrNull("top_p"),
            topK = body.optIntOrNull("top_k"),
            seed = null,
            thinking = thinking,
            stream = body.optBoolean("stream", false),
            model = body.optString("model", ""),
            ignored = ignored
        )
    }

    private const val BILLING_PREFIX = "x-anthropic-billing-header:"

    /**
     * Claude Code sends a system block "x-anthropic-billing-header: ...; cch=a5145;" whose 5-character stamp changes on
     * every request. It is metadata for Anthropic's billing and means nothing to a local model, and left in it sits at
     * the very start of the prompt and defeats KV prefix reuse for everything after it. A block that is only that line
     * is dropped; the same line at the start of a longer block has its stamp blanked instead.
     */
    fun cleanSystemText(text: String): String {
        if (!text.startsWith(BILLING_PREFIX)) return text
        return if (text.indexOf('\n') < 0) "" else normalizeBillingHeader(text)
    }

    /** Blanks the per-request stamp ("fffff"), as llama-server does (llama.cpp PR 21793). */
    fun normalizeBillingHeader(text: String): String {
        val prefix = "x-anthropic-billing-header:"
        if (!text.startsWith(prefix)) return text
        val idx = text.indexOf("cch=", prefix.length)
        if (idx < 0) return text
        val start = idx + 4
        if (start + 5 < text.length && text[start + 5] == ';') {
            return text.substring(0, start) + "fffff" + text.substring(start + 5)
        }
        return text
    }

    // ------------------------------------------------------------------------------------------------ response

    /** Block list shared by the non-streaming response; [thinkingOn] false drops the reasoning. */
    fun buildResponse(fin: ChatEvent.Finished, model: String, messageId: String, thinkingOn: Boolean): JSONObject {
        val content = JSONArray()
        if (thinkingOn && fin.reasoning.isNotBlank()) {
            content.put(JSONObject().put("type", "thinking").put("thinking", fin.reasoning).put("signature", ""))
        }
        if (fin.answer.isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", fin.answer))
        val calls = withIds(fin.toolCalls)
        for (c in calls) {
            content.put(JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name).put("input", parseArguments(c.arguments)))
        }
        if (content.length() == 0) content.put(JSONObject().put("type", "text").put("text", ""))
        return JSONObject()
            .put("id", messageId)
            .put("type", "message")
            .put("role", "assistant")
            .put("model", model)
            .put("content", content)
            .put("stop_reason", fin.stopReason.ifEmpty { "end_turn" })
            .put("stop_sequence", if (fin.stopSequence.isEmpty()) JSONObject.NULL else fin.stopSequence)
            .put("usage", usage(fin))
    }

    /**
     * Anthropic counts cached tokens separately: input_tokens excludes them, and a client's context accounting is
     * input + cache_read + cache_creation. The prefix the engine reused is reported as a cache read.
     */
    fun usage(fin: ChatEvent.Finished): JSONObject {
        val total = fin.stats.nPromptTotal
        val reused = fin.stats.nReused.coerceIn(0, total)
        return JSONObject()
            .put("input_tokens", total - reused)
            .put("cache_creation_input_tokens", 0)
            .put("cache_read_input_tokens", reused)
            .put("output_tokens", fin.stats.nGenerated)
    }

    fun parseArguments(arguments: String): JSONObject = try {
        if (arguments.isBlank()) JSONObject() else JSONObject(arguments)
    } catch (e: JSONException) {
        JSONObject()
    }

    /** Tool calls from templates that emit no ids get a fresh `toolu_...` id; duplicates are made unique too. */
    fun withIds(calls: List<ChatToolCall>): List<ChatToolCall> {
        val seen = HashSet<String>()
        val out = ArrayList<ChatToolCall>(calls.size)
        for (c in calls) {
            var id = c.id
            if (id.isEmpty() || !seen.add(id)) {
                id = Ids.random("toolu_")
                seen.add(id)
            }
            out.add(ChatToolCall(id, c.name, c.arguments))
        }
        return out
    }

    fun errorBody(type: String, message: String): String =
        JSONObject().put("type", "error").put("error", JSONObject().put("type", type).put("message", message)).toString()

    const val IMAGE_PLACEHOLDER = "[image omitted: this server cannot read images]"
}

internal fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    return if (v is Number) v.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt() else null
}

internal fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    return if (v is Number) v.toDouble() else null
}

/**
 * Turns engine events into the Anthropic streaming event sequence. Pure and synchronous: the server feeds it events
 * and writes whatever frames come back, so the sequence is unit-testable without sockets.
 *
 * Order: message_start, then blocks as the model produces them (thinking -> text), then tool_use blocks (the parser
 * only reports a complete call, so each arrives whole: one content_block_start, one input_json_delta, one stop),
 * then message_delta and message_stop. Reasoning never goes into a text block, and is dropped when thinking is off.
 */
class AnthropicStreamEncoder(
    private val messageId: String,
    private val model: String,
    private val thinkingOn: Boolean,
    private val inputTokensEstimate: Int
) {
    private enum class Open { NONE, THINKING, TEXT }

    private var open = Open.NONE
    private var index = 0
    private var started = false
    private var finished = false
    private val sentAnswer = StringBuilder()
    private val sentReasoning = StringBuilder()
    private var anyBlock = false

    fun start(): List<SseFrame> {
        started = true
        val msg = JSONObject()
            .put("id", messageId).put("type", "message").put("role", "assistant").put("model", model)
            .put("content", JSONArray()).put("stop_reason", JSONObject.NULL).put("stop_sequence", JSONObject.NULL)
            .put(
                "usage",
                JSONObject().put("input_tokens", inputTokensEstimate).put("cache_creation_input_tokens", 0)
                    .put("cache_read_input_tokens", 0).put("output_tokens", 0)
            )
        return listOf(frame("message_start", JSONObject().put("type", "message_start").put("message", msg)))
    }

    fun ping(): SseFrame = SseFrame("ping", "{\"type\": \"ping\"}")

    fun onReasoning(delta: String): List<SseFrame> {
        if (!thinkingOn || delta.isEmpty() || finished) return emptyList()
        val out = ArrayList<SseFrame>()
        // Reasoning that arrives after the answer started cannot be shown in order; drop it rather than mis-file it.
        if (open == Open.TEXT) return out
        if (open != Open.THINKING) {
            out.add(blockStart(JSONObject().put("type", "thinking").put("thinking", "")))
            open = Open.THINKING
        }
        sentReasoning.append(delta)
        out.add(blockDelta(JSONObject().put("type", "thinking_delta").put("thinking", delta)))
        return out
    }

    fun onAnswer(delta: String): List<SseFrame> {
        if (delta.isEmpty() || finished) return emptyList()
        val out = ArrayList<SseFrame>()
        if (open == Open.THINKING) out.addAll(closeBlock())
        if (open != Open.TEXT) {
            out.add(blockStart(JSONObject().put("type", "text").put("text", "")))
            open = Open.TEXT
        }
        sentAnswer.append(delta)
        out.add(blockDelta(JSONObject().put("type", "text_delta").put("text", delta)))
        return out
    }

    /** Closes the stream from the engine's final, authoritative result. */
    fun finish(fin: ChatEvent.Finished): List<SseFrame> {
        if (finished) return emptyList()
        val out = ArrayList<SseFrame>()
        // Whatever the live deltas did not carry (text held back until the end, a re-parse that disagreed early).
        if (thinkingOn && fin.reasoning.length > sentReasoning.length && fin.reasoning.startsWith(sentReasoning.toString())) {
            out.addAll(onReasoning(fin.reasoning.substring(sentReasoning.length)))
        }
        if (fin.answer.length > sentAnswer.length && fin.answer.startsWith(sentAnswer.toString())) {
            out.addAll(onAnswer(fin.answer.substring(sentAnswer.length)))
        }
        out.addAll(closeBlock())
        for (c in AnthropicApi.withIds(fin.toolCalls)) {
            out.add(blockStart(JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name).put("input", JSONObject())))
            out.add(blockDelta(JSONObject().put("type", "input_json_delta").put("partial_json", AnthropicApi.parseArguments(c.arguments).toString())))
            out.addAll(closeBlockForced())
        }
        if (!anyBlock) {
            out.add(blockStart(JSONObject().put("type", "text").put("text", "")))
            out.addAll(closeBlockForced())
        }
        val delta = JSONObject()
            .put("stop_reason", fin.stopReason.ifEmpty { "end_turn" })
            .put("stop_sequence", if (fin.stopSequence.isEmpty()) JSONObject.NULL else fin.stopSequence)
        out.add(frame("message_delta", JSONObject().put("type", "message_delta").put("delta", delta).put("usage", AnthropicApi.usage(fin))))
        out.add(frame("message_stop", JSONObject().put("type", "message_stop")))
        finished = true
        return out
    }

    /** A mid-stream failure: Anthropic reports it as an `error` event instead of an HTTP status. */
    fun error(type: String, message: String): List<SseFrame> {
        if (finished) return emptyList()
        finished = true
        return listOf(SseFrame("error", AnthropicApi.errorBody(type, message)))
    }

    private fun closeBlock(): List<SseFrame> {
        if (open == Open.NONE) return emptyList()
        return closeBlockForced()
    }

    private fun closeBlockForced(): List<SseFrame> {
        val out = ArrayList<SseFrame>()
        if (open == Open.THINKING) {
            // The API signs thinking blocks; there is nothing to sign here, so the signature is empty.
            out.add(blockDelta(JSONObject().put("type", "signature_delta").put("signature", "")))
        }
        out.add(frame("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", index)))
        index += 1
        open = Open.NONE
        return out
    }

    private fun blockStart(block: JSONObject): SseFrame {
        anyBlock = true
        return frame("content_block_start", JSONObject().put("type", "content_block_start").put("index", index).put("content_block", block))
    }

    private fun blockDelta(delta: JSONObject): SseFrame =
        frame("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", index).put("delta", delta))

    private fun frame(event: String, data: JSONObject) = SseFrame(event, data.toString())
}
