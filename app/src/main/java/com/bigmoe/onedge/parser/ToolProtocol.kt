package com.bigmoe.onedge.parser

import org.json.JSONException
import org.json.JSONObject

/** A successfully parsed tool call. [arguments] is a JSON object string ("{}" when absent). */
data class ToolCall(val name: String, val arguments: String)

sealed interface ToolCallParseResult {
    data class Ok(val call: ToolCall) : ToolCallParseResult
    data class Malformed(val reason: String) : ToolCallParseResult
}

/**
 * The prompt-only tool protocol (the engine has no grammar support, see docs/tool-calling.md):
 *   model  -> <tool_call>{"name": "...", "arguments": {...}}</tool_call>
 *   app    -> a user turn: <tool_response>...</tool_response>
 * Small models get the JSON slightly wrong all the time, so parsing is deliberately tolerant.
 */
object ToolProtocol {
    const val CALL_OPEN = "<tool_call>"
    const val CALL_CLOSE = "</tool_call>"
    const val RESPONSE_OPEN = "<tool_response>"
    const val RESPONSE_CLOSE = "</tool_response>"

    fun wrapResult(toolName: String, result: String): String =
        "$RESPONSE_OPEN\n[$toolName]\n${result.trim()}\n$RESPONSE_CLOSE"

    fun wrapResults(results: List<Pair<String, String>>): String =
        results.joinToString("\n") { (name, res) -> wrapResult(name, res) }

    /** The corrective user turn sent after an unparseable call. */
    fun malformedFeedback(reason: String): String =
        "$RESPONSE_OPEN\nError: your tool call could not be parsed ($reason). " +
            "Reply with exactly one valid call in the form " +
            "$CALL_OPEN{\"name\": \"tool_name\", \"arguments\": {...}}$CALL_CLOSE, " +
            "or answer the user directly without a tool.\n$RESPONSE_CLOSE"

    /** Text with every complete or unterminated tool_call block removed (for display). */
    fun stripToolCalls(text: String): String {
        if (!text.contains(CALL_OPEN)) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val s = text.indexOf(CALL_OPEN, i)
            if (s < 0) {
                out.append(text, i, text.length)
                break
            }
            out.append(text, i, s)
            val e = text.indexOf(CALL_CLOSE, s + CALL_OPEN.length)
            if (e < 0) break
            i = e + CALL_CLOSE.length
        }
        return out.toString().trim()
    }

    /** Payloads of all tool_call blocks; an unterminated last block is included (flag = terminated). */
    fun extractPayloads(text: String): List<Pair<String, Boolean>> {
        val result = ArrayList<Pair<String, Boolean>>()
        var i = 0
        while (true) {
            val s = text.indexOf(CALL_OPEN, i)
            if (s < 0) break
            val from = s + CALL_OPEN.length
            val e = text.indexOf(CALL_CLOSE, from)
            if (e < 0) {
                result.add(text.substring(from) to false)
                break
            }
            result.add(text.substring(from, e) to true)
            i = e + CALL_CLOSE.length
        }
        return result
    }

    fun parseCall(payload: String): ToolCallParseResult {
        val candidate = extractJsonObject(payload)
            ?: return ToolCallParseResult.Malformed("no JSON object found")
        val obj = parseLenient(candidate)
            ?: return ToolCallParseResult.Malformed("invalid JSON")
        val name = (obj.optString("name", "").ifBlank { obj.optString("tool", "") }).trim()
        if (name.isEmpty()) return ToolCallParseResult.Malformed("missing \"name\"")
        val rawArgs: Any? = when {
            obj.has("arguments") -> obj.opt("arguments")
            obj.has("parameters") -> obj.opt("parameters")
            obj.has("args") -> obj.opt("args")
            else -> null
        }
        val args: JSONObject = when (rawArgs) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> rawArgs
            is String -> if (rawArgs.isBlank()) JSONObject()
            else parseLenient(extractJsonObject(rawArgs) ?: rawArgs)
                ?: return ToolCallParseResult.Malformed("\"arguments\" is not a JSON object")
            else -> return ToolCallParseResult.Malformed("\"arguments\" is not a JSON object")
        }
        return ToolCallParseResult.Ok(ToolCall(name, args.toString()))
    }

    /** First balanced {...} in [s] (string-literal aware); code fences around it are ignored. */
    internal fun extractJsonObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return s.substring(start, i + 1)
                    }
                }
            }
        }
        // Unbalanced (generation cut off): close what is open so the repair pass can try.
        return if (depth > 0) s.substring(start) + "}".repeat(depth) else null
    }

    /**
     * Escapes raw line breaks / tabs / control characters that sit INSIDE string literals. Models write file
     * contents into "content" with real newlines; strict JSON parsers (and org.json on the JVM) reject that.
     */
    internal fun escapeControlCharsInStrings(text: String): String {
        val sb = StringBuilder(text.length + 16)
        var inString = false
        var escape = false
        for (c in text) {
            if (inString) {
                when {
                    escape -> { sb.append(c); escape = false }
                    c == '\\' -> { sb.append(c); escape = true }
                    c == '"' -> { sb.append(c); inString = false }
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c.code < 0x20 -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            } else {
                if (c == '"') inString = true
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun parseLenient(text: String): JSONObject? {
        try {
            return JSONObject(text)
        } catch (_: JSONException) {
        }
        val smart = text
            .replace('\u201C', '"').replace('\u201D', '"')
            .replace('\u2018', '\'').replace('\u2019', '\'')
        val candidates = ArrayList<String>()
        candidates.add(escapeControlCharsInStrings(text))
        val noTrailingCommas = smart.replace(Regex(",\\s*([}\\]])"), "$1")
        candidates.add(noTrailingCommas)
        candidates.add(escapeControlCharsInStrings(noTrailingCommas))
        if (!noTrailingCommas.contains('"')) { // Python-style single quotes
            candidates.add(noTrailingCommas.replace('\'', '"'))
        }
        for (c in candidates) {
            try {
                return JSONObject(c)
            } catch (_: JSONException) {
            }
        }
        return null
    }
}
