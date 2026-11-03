package com.bigmoe.onedge.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One tool a server announced in `tools/list`. [schemaJson] is its `inputSchema` as JSON text. */
data class McpToolDef(
    val name: String,
    val description: String,
    val schemaJson: String,
    /** The user can switch single tools off; only selected ones are given to the model. */
    val selected: Boolean = true
)

/**
 * Minimal MCP client for servers that speak the "Streamable HTTP" transport: every message is a JSON-RPC POST and the
 * answer comes back either as plain JSON or as a short server-sent-event stream. The older two-endpoint HTTP+SSE
 * transport and local (stdio) servers are not supported.
 *
 * Each [listTools] / [callTool] runs its own initialize handshake, so nothing has to stay connected between calls.
 */
class McpClient(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val timeoutMs: Int = 15_000
) {
    private var sessionId: String? = null
    private var nextId = 1

    suspend fun listTools(): List<McpToolDef> = withContext(Dispatchers.IO) {
        initialize()
        val out = ArrayList<McpToolDef>()
        var cursor: String? = null
        for (page in 0 until MAX_PAGES) {
            val params = JSONObject()
            if (cursor != null) params.put("cursor", cursor)
            val result = request("tools/list", params)
            val tools = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until tools.length()) {
                val t = tools.optJSONObject(i) ?: continue
                val name = t.optString("name")
                if (name.isBlank()) continue
                val schema = t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")
                out.add(McpToolDef(name, t.optString("description"), schema.toString()))
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            if (cursor == null || out.size >= MAX_TOOLS) break
        }
        out.take(MAX_TOOLS)
    }

    suspend fun callTool(name: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        initialize()
        val result = request("tools/call", JSONObject().put("name", name).put("arguments", arguments))
        formatToolResult(result)
    }

    private fun initialize() {
        request(
            "initialize",
            JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "BigMoeOnEdge").put("version", "1"))
        )
        // Required by the protocol; a server that is picky about it still answers the calls that follow.
        try {
            post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), expectId = null)
        } catch (_: IOException) {
        }
    }

    /** Sends one request and returns its `result`; a JSON-RPC error becomes an [IOException]. */
    private fun request(method: String, params: JSONObject): JSONObject {
        val id = nextId++
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val response = post(body, expectId = id) ?: throw IOException("The server sent no answer to $method.")
        response.optJSONObject("error")?.let { throw IOException(it.optString("message", "The server returned an error for $method.")) }
        return response.optJSONObject("result") ?: JSONObject()
    }

    private fun post(body: JSONObject, expectId: Int?): JSONObject? {
        if (!isHttpUrl(url)) throw IOException("The server address must start with http:// or https://")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            sessionId?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            conn.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            val contentType = conn.contentType.orEmpty()
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { readCapped(it, MAX_BODY_BYTES) }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code${if (text.isNotBlank()) ": " + text.take(200) else ""}")
            if (expectId == null) return null
            return parseRpcResponse(text, contentType, expectId)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        const val MAX_TOOLS = 50
        private const val MAX_PAGES = 10
        private const val MAX_BODY_BYTES = 1_000_000

        fun isHttpUrl(url: String): Boolean {
            val t = url.trim()
            return t.startsWith("https://", ignoreCase = true) || t.startsWith("http://", ignoreCase = true)
        }

        private fun readCapped(input: InputStream, limit: Int): String {
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (buf.size() < limit) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, minOf(n, limit - buf.size()))
            }
            return buf.toString("UTF-8")
        }

        /**
         * Finds the JSON-RPC response with [id] in a reply that is either one JSON value (object or batch array) or a
         * server-sent-event stream. Other messages in the stream (progress, server requests) are skipped.
         */
        internal fun parseRpcResponse(text: String, contentType: String, id: Int): JSONObject {
            val candidates = ArrayList<JSONObject>()
            if (contentType.contains("event-stream", ignoreCase = true) || text.trimStart().startsWith("event:") || text.trimStart().startsWith("data:")) {
                val data = StringBuilder()
                fun flush() {
                    if (data.isNotEmpty()) {
                        runCatching { JSONObject(data.toString()) }.getOrNull()?.let { candidates.add(it) }
                        data.setLength(0)
                    }
                }
                for (raw in text.lineSequence()) {
                    val line = raw.trimEnd('\r')
                    when {
                        line.isEmpty() -> flush()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").removePrefix(" "))
                        }
                    }
                }
                flush()
            } else {
                val trimmed = text.trim()
                try {
                    if (trimmed.startsWith("[")) {
                        val arr = JSONArray(trimmed)
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { candidates.add(it) }
                    } else {
                        candidates.add(JSONObject(trimmed))
                    }
                } catch (e: org.json.JSONException) {
                    throw IOException("The server did not answer with JSON-RPC.")
                }
            }
            return candidates.firstOrNull { it.opt("id") != null && it.optInt("id", -1) == id && (it.has("result") || it.has("error")) }
                ?: throw IOException("The server's reply did not contain an answer to request $id.")
        }

        /** Text blocks joined; other block types are named so the model knows something was left out. */
        internal fun formatToolResult(result: JSONObject): String {
            val parts = ArrayList<String>()
            val content = result.optJSONArray("content")
            if (content != null) {
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    when (val type = item.optString("type")) {
                        "text" -> parts.add(item.optString("text"))
                        "resource" -> parts.add(item.optJSONObject("resource")?.optString("text").orEmpty().ifBlank { "[resource]" })
                        else -> parts.add("[$type content not shown]")
                    }
                }
            }
            if (parts.isEmpty()) result.optJSONObject("structuredContent")?.let { parts.add(it.toString()) }
            val text = parts.joinToString("\n").ifBlank { "(the tool returned no output)" }
            return if (result.optBoolean("isError", false)) "Error: $text" else text
        }
    }
}
