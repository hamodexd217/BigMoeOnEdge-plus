package com.bigmoe.onedge.server

import com.bigmoe.onedge.core.ChatEvent
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI Chat Completions <-> the engine's chat request. The wire format is already the engine's, so little changes. */
object OpenAiApi {

    fun parse(body: JSONObject): ParsedChat {
        val src = body.optJSONArray("messages") ?: throw ApiRequestException("'messages' is required and must be an array")
        val ignored = ArrayList<String>()
        val msgs = JSONArray()
        var droppedImages = false
        for (i in 0 until src.length()) {
            val m = src.optJSONObject(i) ?: throw ApiRequestException("messages[$i] is not an object")
            var role = m.optString("role")
            if (role == "developer") role = "system"
            if (role != "system" && role != "user" && role != "assistant" && role != "tool") {
                throw ApiRequestException("messages[$i].role '$role' is not supported")
            }
            val out = JSONObject().put("role", role)
            val c = m.opt("content")
            when (c) {
                is String -> out.put("content", c)
                is JSONArray -> {
                    val sb = ArrayList<String>()
                    for (k in 0 until c.length()) {
                        val part = c.optJSONObject(k) ?: continue
                        when (part.optString("type")) {
                            "text" -> sb.add(part.optString("text"))
                            "image_url", "input_image" -> {
                                droppedImages = true
                                sb.add(AnthropicApi.IMAGE_PLACEHOLDER)
                            }
                        }
                    }
                    out.put("content", sb.joinToString("\n\n"))
                }
                else -> out.put("content", "")
            }
            m.optJSONArray("tool_calls")?.let { out.put("tool_calls", it) }
            if (m.has("tool_call_id")) out.put("tool_call_id", m.optString("tool_call_id"))
            if (m.has("name") && role == "tool") out.put("name", m.optString("name"))
            val rc = m.optString("reasoning_content", "")
            if (rc.isNotEmpty()) out.put("reasoning_content", rc)
            msgs.put(out)
        }
        if (droppedImages) ignored.add("image parts (replaced by a text placeholder)")

        val tools = body.optJSONArray("tools")
        var toolChoice = ""
        if (tools != null && tools.length() > 0) {
            val tc = body.opt("tool_choice")
            toolChoice = when (tc) {
                is String -> if (tc == "auto" || tc == "none" || tc == "required") tc else ""
                is JSONObject -> "required" // {"type":"function","function":{"name":...}}: cannot force one tool, so "some tool"
                else -> ""
            }
        }

        val stops = ArrayList<String>()
        when (val st = body.opt("stop")) {
            is String -> if (st.isNotEmpty()) stops.add(st)
            is JSONArray -> for (i in 0 until st.length()) {
                val s = st.optString(i)
                if (s.isNotEmpty()) stops.add(s)
            }
        }

        var thinking: Boolean? = null
        val kw = body.optJSONObject("chat_template_kwargs")
        if (kw != null && kw.has("enable_thinking")) thinking = kw.optBoolean("enable_thinking", true)

        var jsonSchema = ""
        if (body.has("response_format") && !body.isNull("response_format")) {
            val format = body.optJSONObject("response_format")
                ?: throw ApiRequestException("response_format must be an object")
            when (format.optString("type", "text")) {
                "text" -> Unit
                "json_object" -> jsonSchema = "{\"type\":\"object\"}"
                "json_schema" -> {
                    val spec = format.optJSONObject("json_schema")
                        ?: throw ApiRequestException("response_format.json_schema must be an object")
                    val schema = spec.optJSONObject("schema")
                        ?: throw ApiRequestException("response_format.json_schema.schema must be a JSON Schema object")
                    jsonSchema = schema.toString()
                }
                else -> throw ApiRequestException("response_format.type must be text, json_object, or json_schema")
            }
        }

        for (k in listOf("n", "logit_bias", "logprobs", "frequency_penalty", "presence_penalty", "min_p", "repeat_penalty", "grammar")) {
            if (body.has(k) && !body.isNull(k)) {
                val v = body.opt(k)
                // n=1 and zero penalties are what we do anyway.
                val trivial = (k == "n" && v is Number && v.toInt() == 1) ||
                    ((k == "frequency_penalty" || k == "presence_penalty") && v is Number && v.toDouble() == 0.0)
                if (!trivial) ignored.add(k)
            }
        }

        val maxTokens = body.optIntOrNull("max_completion_tokens") ?: body.optIntOrNull("max_tokens")
        if (maxTokens != null && maxTokens < 1) throw ApiRequestException("max_tokens must be at least 1")
        val includeUsage = body.optJSONObject("stream_options")?.optBoolean("include_usage", false) ?: false

        return ParsedChat(
            messagesJson = msgs.toString(),
            toolsJson = if (tools != null && tools.length() > 0) tools.toString() else "",
            toolChoice = toolChoice,
            stop = stops,
            maxTokens = maxTokens,
            temperature = body.optDoubleOrNull("temperature"),
            topP = body.optDoubleOrNull("top_p"),
            topK = body.optIntOrNull("top_k"),
            seed = body.optIntOrNull("seed"),
            thinking = thinking,
            stream = body.optBoolean("stream", false),
            model = body.optString("model", ""),
            ignored = ignored,
            includeUsage = includeUsage,
            jsonSchema = jsonSchema
        )
    }

    fun buildResponse(fin: ChatEvent.Finished, model: String, id: String, created: Long, thinkingOn: Boolean): JSONObject {
        val message = JSONObject().put("role", "assistant").put("content", if (fin.answer.isEmpty() && fin.toolCalls.isNotEmpty()) JSONObject.NULL else fin.answer)
        if (thinkingOn && fin.reasoning.isNotBlank()) message.put("reasoning_content", fin.reasoning)
        if (fin.toolCalls.isNotEmpty()) message.put("tool_calls", OpenAiApiAccess.toolCalls(fin))
        return JSONObject()
            .put("id", id).put("object", "chat.completion").put("created", created).put("model", model)
            .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", message).put("finish_reason", OpenAiApiAccess.reason(fin.stopReason))))
            .put("usage", OpenAiApiAccess.usage(fin))
    }

    fun errorBody(type: String, message: String): String =
        JSONObject().put("error", JSONObject().put("message", message).put("type", type).put("code", JSONObject.NULL)).toString()
}

/** OpenAI streaming chunks. Same contract as [AnthropicStreamEncoder]: pure, feed events, write frames. */
class OpenAiStreamEncoder(
    private val id: String,
    private val model: String,
    private val created: Long,
    private val thinkingOn: Boolean,
    private val includeUsage: Boolean
) {
    private var roleSent = false
    private var finished = false
    private val sentAnswer = StringBuilder()
    private val sentReasoning = StringBuilder()

    private fun chunk(delta: JSONObject, finishReason: String?): SseFrame {
        val choice = JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finishReason ?: JSONObject.NULL)
        val o = JSONObject().put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", model)
            .put("choices", JSONArray().put(choice))
        return SseFrame(null, o.toString())
    }

    private fun withRole(delta: JSONObject): JSONObject {
        if (!roleSent) {
            roleSent = true
            delta.put("role", "assistant")
        }
        return delta
    }

    fun start(): List<SseFrame> = listOf(chunk(withRole(JSONObject().put("content", "")), null))

    fun ping(): String = ": ping\n\n"

    fun onReasoning(delta: String): List<SseFrame> {
        if (!thinkingOn || delta.isEmpty() || finished) return emptyList()
        sentReasoning.append(delta)
        return listOf(chunk(withRole(JSONObject().put("reasoning_content", delta)), null))
    }

    fun onAnswer(delta: String): List<SseFrame> {
        if (delta.isEmpty() || finished) return emptyList()
        sentAnswer.append(delta)
        return listOf(chunk(withRole(JSONObject().put("content", delta)), null))
    }

    fun finish(fin: ChatEvent.Finished): List<SseFrame> {
        if (finished) return emptyList()
        val out = ArrayList<SseFrame>()
        if (thinkingOn && fin.reasoning.length > sentReasoning.length && fin.reasoning.startsWith(sentReasoning.toString())) {
            out.addAll(onReasoning(fin.reasoning.substring(sentReasoning.length)))
        }
        if (fin.answer.length > sentAnswer.length && fin.answer.startsWith(sentAnswer.toString())) {
            out.addAll(onAnswer(fin.answer.substring(sentAnswer.length)))
        }
        if (fin.toolCalls.isNotEmpty()) {
            val delta = JSONObject().put("tool_calls", OpenAiApiAccess.toolCalls(fin))
            out.add(chunk(withRole(delta), null))
        }
        out.add(chunk(JSONObject(), OpenAiApiAccess.reason(fin.stopReason)))
        if (includeUsage) {
            val u = JSONObject().put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", model)
                .put("choices", JSONArray()).put("usage", OpenAiApiAccess.usage(fin))
            out.add(SseFrame(null, u.toString()))
        }
        out.add(SseFrame(null, "[DONE]"))
        finished = true
        return out
    }

    fun error(type: String, message: String): List<SseFrame> {
        if (finished) return emptyList()
        finished = true
        return listOf(SseFrame(null, OpenAiApi.errorBody(type, message)), SseFrame(null, "[DONE]"))
    }
}

/** Small bridge so the encoder can reuse the response builders' helpers without widening their visibility. */
internal object OpenAiApiAccess {
    fun toolCalls(fin: ChatEvent.Finished): JSONArray {
        val arr = JSONArray()
        var i = 0
        for (c in AnthropicApi.withIds(fin.toolCalls)) {
            arr.put(
                JSONObject().put("index", i).put("id", c.id).put("type", "function")
                    .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments.ifBlank { "{}" }))
            )
            i++
        }
        return arr
    }

    fun reason(stopReason: String): String = when (stopReason) {
        "max_tokens" -> "length"
        "tool_use" -> "tool_calls"
        else -> "stop"
    }

    fun usage(fin: ChatEvent.Finished): JSONObject {
        val total = fin.stats.nPromptTotal
        return JSONObject().put("prompt_tokens", total).put("completion_tokens", fin.stats.nGenerated)
            .put("total_tokens", total + fin.stats.nGenerated)
            .put("prompt_tokens_details", JSONObject().put("cached_tokens", fin.stats.nReused.coerceIn(0, total)))
    }
}
