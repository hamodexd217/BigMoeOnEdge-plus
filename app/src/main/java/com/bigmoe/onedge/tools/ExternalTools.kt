package com.bigmoe.onedge.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** What an entry in the user's external-tools list is. */
enum class ExternalKind { HTTP, MCP }

/** One argument of a hand-made HTTP tool. Every argument is text as far as the model is concerned. */
data class ExternalParam(val name: String, val description: String, val required: Boolean = true)

/**
 * One row of the external-tools list: either a single HTTP tool the user describes, or an MCP server whose tools were
 * fetched once and cached in [mcpTools] (so registering them needs no network).
 */
data class ExternalEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: ExternalKind = ExternalKind.HTTP,
    /** HTTP: the tool name the model calls. MCP: a label for the server, used as the prefix of its tool names. */
    val name: String,
    val description: String = "",
    /** HTTP: the address, `{param}` is replaced by the (URL-encoded) argument. MCP: the server's endpoint. */
    val url: String,
    /** HTTP only: GET / POST / PUT / PATCH / DELETE. */
    val method: String = "GET",
    /** Lines of `Name: value`, sent with every request (API keys go here). */
    val headers: String = "",
    val params: List<ExternalParam> = emptyList(),
    val enabled: Boolean = true,
    /** Ask the user before every call. On by default: these tools reach out to the network. */
    val askFirst: Boolean = true,
    val mcpTools: List<McpToolDef> = emptyList()
)

object ExternalToolsJson {
    fun parse(json: String): List<ExternalEntry> {
        if (json.isBlank()) return emptyList()
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<ExternalEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = if (o.optString("kind").equals("MCP", ignoreCase = true)) ExternalKind.MCP else ExternalKind.HTTP
            val name = o.optString("name").trim()
            val url = o.optString("url").trim()
            if (name.isEmpty() || url.isEmpty()) continue
            val params = ArrayList<ExternalParam>()
            o.optJSONArray("params")?.let { p ->
                for (j in 0 until p.length()) {
                    val po = p.optJSONObject(j) ?: continue
                    val pn = po.optString("name").trim()
                    if (pn.isNotEmpty()) params.add(ExternalParam(pn, po.optString("description"), po.optBoolean("required", true)))
                }
            }
            val tools = ArrayList<McpToolDef>()
            o.optJSONArray("mcpTools")?.let { t ->
                for (j in 0 until t.length()) {
                    val to = t.optJSONObject(j) ?: continue
                    val tn = to.optString("name")
                    if (tn.isNotBlank()) tools.add(McpToolDef(tn, to.optString("description"), to.optString("schema", "{\"type\":\"object\"}"), to.optBoolean("selected", true)))
                }
            }
            out.add(
                ExternalEntry(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    kind = kind, name = name, description = o.optString("description"), url = url,
                    method = o.optString("method", "GET").uppercase().ifBlank { "GET" },
                    headers = o.optString("headers"), params = params,
                    enabled = o.optBoolean("enabled", true), askFirst = o.optBoolean("askFirst", true), mcpTools = tools
                )
            )
        }
        return out
    }

    fun toJson(entries: List<ExternalEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject()
                .put("id", e.id).put("kind", e.kind.name).put("name", e.name).put("description", e.description)
                .put("url", e.url).put("method", e.method).put("headers", e.headers)
                .put("enabled", e.enabled).put("askFirst", e.askFirst)
            o.put("params", JSONArray().also { a ->
                e.params.forEach { a.put(JSONObject().put("name", it.name).put("description", it.description).put("required", it.required)) }
            })
            o.put("mcpTools", JSONArray().also { a ->
                e.mcpTools.forEach { a.put(JSONObject().put("name", it.name).put("description", it.description).put("schema", it.schemaJson).put("selected", it.selected)) }
            })
            arr.put(o)
        }
        return arr.toString()
    }
}

/** Text <-> structures used by the editor. */
object ExternalText {
    /** `Name: value` per line; blank lines and lines without a colon are ignored, control characters removed. */
    fun parseHeaders(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val i = raw.indexOf(':')
            if (i <= 0) continue
            val k = raw.substring(0, i).filter { !it.isISOControl() }.trim()
            val v = raw.substring(i + 1).filter { !it.isISOControl() }.trim()
            if (k.isNotEmpty() && k.none { it.isWhitespace() }) out[k] = v
        }
        return out
    }

    /** `city: City to look up` is required, `units?: metric or imperial` is optional. */
    fun parseParams(text: String): List<ExternalParam> {
        val out = ArrayList<ExternalParam>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val i = line.indexOf(':')
            val head = (if (i >= 0) line.substring(0, i) else line).trim()
            val desc = if (i >= 0) line.substring(i + 1).trim() else ""
            val optional = head.endsWith("?")
            val name = head.removeSuffix("?").trim().replace(Regex("[^A-Za-z0-9_]"), "_")
            if (name.isNotEmpty() && out.none { it.name == name }) out.add(ExternalParam(name, desc, !optional))
        }
        return out
    }

    fun paramsToText(params: List<ExternalParam>): String =
        params.joinToString("\n") { (if (it.required) it.name else it.name + "?") + (if (it.description.isNotBlank()) ": " + it.description else "") }

    /** A name the model can call: lowercase letters, digits and underscores only, never empty, at most 40 characters. */
    fun toolName(raw: String): String {
        val s = raw.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).trim('_')
        return if (s.isEmpty()) "external_tool" else if (s[0].isDigit()) "t_$s" else s
    }

    fun hostOf(url: String): String = runCatching { URL(url.substringBefore('{')).host }.getOrNull().orEmpty().ifBlank { url.take(40) }
}

/** A tool the user described by hand: one HTTP request per call. */
class HttpExternalTool(
    private val entry: ExternalEntry,
    override val name: String,
    private val timeoutMs: Int = 15_000
) : Tool {
    override val category: ToolCategory = ToolCategory.EXTERNAL
    override val description: String = entry.description.ifBlank { "Calls ${ExternalText.hostOf(entry.url)}." }.take(240)
    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().also { props ->
            entry.params.forEach { props.put(it.name, JSONObject().put("type", "string").put("description", it.description)) }
        })
        put("required", JSONArray(entry.params.filter { it.required }.map { it.name }))
    }

    override fun describe(arguments: JSONObject): String = "Calling $name"

    override fun confirmation(arguments: JSONObject): ConfirmationRequest? {
        if (!entry.askFirst) return null
        val target = runCatching { buildUrl(arguments).first }.getOrDefault(entry.url)
        return ConfirmationRequest(
            title = "Call external tool \"$name\"?",
            message = "${entry.method} $target\n\nSends: ${arguments.toString().take(400)}"
        )
    }

    /** The final URL and the names of the arguments the address template already used. */
    internal fun buildUrl(args: JSONObject): Pair<String, Set<String>> {
        val used = HashSet<String>()
        var url = entry.url
        for (p in entry.params) {
            val marker = "{" + p.name + "}"
            if (url.contains(marker)) {
                val value = args.opt(p.name)?.takeIf { it != JSONObject.NULL }?.toString()
                if (value == null) {
                    if (p.required) throw ToolInputException("missing required argument '${p.name}'")
                    url = url.replace(marker, "")
                } else {
                    url = url.replace(marker, URLEncoder.encode(value, "UTF-8").replace("+", "%20"))
                }
                used.add(p.name)
            }
        }
        for (p in entry.params) {
            if (p.required && p.name !in used && (!args.has(p.name) || args.isNull(p.name))) {
                throw ToolInputException("missing required argument '${p.name}'")
            }
        }
        return url to used
    }

    override suspend fun execute(arguments: JSONObject): String = withContext(Dispatchers.IO) {
        val (baseUrl, used) = buildUrl(arguments)
        val method = entry.method.uppercase()
        val hasBody = method == "POST" || method == "PUT" || method == "PATCH"
        var url = baseUrl
        if (!hasBody) {
            val extra = ArrayList<String>()
            for (k in arguments.keys()) {
                if (k in used || arguments.isNull(k)) continue
                extra.add(URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(arguments.get(k).toString(), "UTF-8"))
            }
            if (extra.isNotEmpty()) url += (if (url.contains('?')) "&" else "?") + extra.joinToString("&")
        }
        if (!McpClient.isHttpUrl(url)) throw ToolInputException("the tool's address must start with http:// or https://")

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            for ((k, v) in ExternalText.parseHeaders(entry.headers)) conn.setRequestProperty(k, v)
            if (hasBody) {
                conn.doOutput = true
                if (conn.getRequestProperty("Content-Type") == null) conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(arguments.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = stream?.use { s ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (buf.size() < MAX_BODY_BYTES) {
                    val n = s.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, minOf(n, MAX_BODY_BYTES - buf.size()))
                }
                buf.toString("UTF-8")
            }.orEmpty()
            if (code !in 200..299) "Error: HTTP $code${if (body.isNotBlank()) ": " + body.take(500) else ""}"
            else body.ifBlank { "(HTTP $code, empty response)" }
        } catch (e: IOException) {
            "Error: the request failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 256_000
    }
}

/** One tool of a cached MCP server; every call goes to the server live. */
class McpExternalTool(
    private val entry: ExternalEntry,
    private val def: McpToolDef,
    override val name: String
) : Tool {
    override val category: ToolCategory = ToolCategory.EXTERNAL
    override val description: String = def.description.ifBlank { "${def.name} on ${entry.name}." }.replace('\n', ' ').take(240)
    override val parametersSchema: JSONObject = run {
        val schema = runCatching { JSONObject(def.schemaJson) }.getOrNull() ?: JSONObject()
        if (schema.optJSONObject("properties") == null) schema.put("properties", JSONObject())
        schema.put("type", "object")
        schema
    }

    override fun describe(arguments: JSONObject): String = "Calling ${def.name} on ${entry.name}"

    override fun confirmation(arguments: JSONObject): ConfirmationRequest? {
        if (!entry.askFirst) return null
        return ConfirmationRequest(
            title = "Call \"${def.name}\" on ${entry.name}?",
            message = "Server: ${ExternalText.hostOf(entry.url)}\n\nSends: ${arguments.toString().take(400)}"
        )
    }

    override suspend fun execute(arguments: JSONObject): String =
        try {
            McpClient(entry.url, ExternalText.parseHeaders(entry.headers)).callTool(def.name, arguments)
        } catch (e: IOException) {
            "Error: the MCP server call failed: ${e.message ?: e.javaClass.simpleName}"
        }
}

/**
 * Turns the saved entries into tools. Names are made unique and never collide with [reserved] (the built-in tools):
 * a clash gets a numeric suffix. MCP tools are prefixed with their server's label and capped per server so the
 * prompt stays small.
 */
fun buildExternalTools(entries: List<ExternalEntry>, reserved: Set<String>): List<Tool> {
    val taken = HashSet(reserved)
    fun unique(base: String): String {
        var candidate = base
        var n = 2
        while (candidate in taken) candidate = "${base.take(36)}_$n".also { n++ }
        taken.add(candidate)
        return candidate
    }
    val tools = ArrayList<Tool>()
    for (e in entries) {
        if (!e.enabled) continue
        when (e.kind) {
            ExternalKind.HTTP -> tools.add(HttpExternalTool(e, unique(ExternalText.toolName(e.name))))
            ExternalKind.MCP -> {
                val prefix = ExternalText.toolName(e.name)
                e.mcpTools.filter { it.selected }.take(MAX_TOOLS_PER_SERVER).forEach { def ->
                    tools.add(McpExternalTool(e, def, unique(ExternalText.toolName(prefix + "_" + def.name))))
                }
            }
        }
    }
    return tools
}

const val MAX_TOOLS_PER_SERVER = 12
