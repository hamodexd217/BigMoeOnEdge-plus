package com.bigmoe.onedge.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Credentials are `var` (not constructor-only `val`) so AgentController can
// sync them from EngineSettings before each agent run, since they're user-
// configurable at runtime via SettingsScreen rather than fixed at app start.
class WebSearchTool(
    var braveApiKey: String? = null,
    var searxngInstanceUrl: String? = null,
    // Overridable so tests can point the tool at a local fake server.
    private val braveEndpoint: String = "https://api.search.brave.com/res/v1/web/search",
    private val timeoutMs: Int = 8000,
    // With no Brave key and no SearXNG URL the tool still works: DuckDuckGo's HTML endpoint first, then
    // Wikipedia's search API. Tests switch it off / point it at a local server.
    private val keylessFallback: Boolean = true,
    private val duckDuckGoEndpoint: String = "https://html.duckduckgo.com/html/",
    private val wikipediaEndpoint: String = "https://%s.wikipedia.org/w/api.php"
) : Tool {

    override val name: String = "web_search"

    override val category: ToolCategory = ToolCategory.WEB

    override val parallelSafe: Boolean = true

    override fun describe(arguments: JSONObject): String =
        "Searching the web for \"${arguments.optString("query").take(50)}\""

    override val description: String =
        "Search the web for current information and recent events."
    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("query", JSONObject().apply {
                put("type", "string")
                put("description", "The search query string")
            })
        })
        put("required", JSONArray().apply { put("query") })
    }

    override suspend fun execute(arguments: JSONObject): String = withContext(Dispatchers.IO) {
        val query = arguments.optString("query", "")
        if (query.isBlank()) {
            return@withContext "Error: Search query cannot be empty."
        }

        val apiKey = braveApiKey?.trim()
        val searxUrl = searxngInstanceUrl?.trim()

        if (!apiKey.isNullOrEmpty()) {
            return@withContext executeBraveSearch(query, apiKey)
        } else if (!searxUrl.isNullOrEmpty()) {
            return@withContext executeSearxngSearch(query, searxUrl)
        } else if (keylessFallback) {
            return@withContext executeKeylessSearch(query)
        } else {
            return@withContext "Error: Web search failed because neither Brave API Key nor SearXNG URL is configured."
        }
    }

    /** No account needed: DuckDuckGo first (general web), Wikipedia if that returns nothing or is blocked. */
    private fun executeKeylessSearch(query: String): String {
        val problems = ArrayList<String>()
        val started = System.nanoTime()
        try {
            val results = parseDuckDuckGoHtml(
                httpGet("$duckDuckGoEndpoint?q=${URLEncoder.encode(query, "UTF-8")}", accept = "text/html", timeout = minOf(timeoutMs, 6000))
            )
            if (results.isNotEmpty()) return formatResults(results)
            problems.add("DuckDuckGo returned no results")
        } catch (e: Exception) {
            problems.add("DuckDuckGo failed: ${e.message}")
        }
        // One budget for both attempts (a blocked read cannot be cancelled by the tool timeout): the fallback only
        // gets what is left of 12 s.
        val leftMs = 12_000 - ((System.nanoTime() - started) / 1_000_000L).toInt()
        if (leftMs < 1500) return "No results found (${problems.joinToString("; ")}). The web may be slow or unreachable."
        try {
            val lang = if (query.any { it in '\u0600'..'\u06FF' }) "ar" else "en"
            val url = String.format(wikipediaEndpoint, lang) +
                "?action=query&list=search&format=json&utf8=1&srlimit=5&srsearch=${URLEncoder.encode(query, "UTF-8")}"
            val results = parseWikipediaJson(httpGet(url, accept = "application/json", timeout = minOf(timeoutMs, leftMs)), lang)
            if (results.isNotEmpty()) return formatResults(results)
            problems.add("Wikipedia returned no results")
        } catch (e: Exception) {
            problems.add("Wikipedia failed: ${e.message}")
        }
        return "No results found (${problems.joinToString("; ")}). The web may be unreachable."
    }

    private fun httpGet(url: String, accept: String, timeout: Int = timeoutMs): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
            // Some endpoints refuse the default Java agent.
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BigMoeOnEdgePlus/1.0")
            connectTimeout = timeout
            readTimeout = timeout
        }
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { readBounded(it, MAX_RESPONSE_CHARS) }
        } finally {
            connection.disconnect()
        }
    }

    private fun executeBraveSearch(query: String, apiKey: String): String {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("$braveEndpoint?q=$encodedQuery&count=5")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Subscription-Token", apiKey)
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                parseBraveResponse(responseText)
            } else {
                "Brave API returned HTTP status code ${connection.responseCode}"
            }
        } catch (e: Exception) {
            "Brave search failed: ${e.message}"
        }
    }

    internal fun parseBraveResponse(jsonText: String): String {
        val json = JSONObject(jsonText)
        val webResults = json.optJSONObject("web")?.optJSONArray("results") ?: return "No results found."
        val out = ArrayList<SearchResult>()
        for (i in 0 until minOf(webResults.length(), 5)) {
            val item = webResults.getJSONObject(i)
            out.add(SearchResult(item.optString("title"), item.optString("description"), item.optString("url")))
        }
        return formatResults(out)
    }

    private fun executeSearxngSearch(query: String, baseUrl: String): String {
        return try {
            val cleanUrl = baseUrl.trimEnd('/')
            if (!cleanUrl.startsWith("https://")) {
                // Cleartext HTTP is disabled app-wide (Android default for targetSdk 28+).
                return "Error: the SearXNG URL must start with https://"
            }
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("$cleanUrl/search?q=$encodedQuery&format=json")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                parseSearxngResponse(responseText)
            } else {
                "SearXNG instance returned HTTP status code ${connection.responseCode}"
            }
        } catch (e: Exception) {
            "SearXNG search failed: ${e.message}"
        }
    }

    internal fun parseSearxngResponse(jsonText: String): String {
        val json = JSONObject(jsonText)
        val results = json.optJSONArray("results") ?: return "No results found."
        val out = ArrayList<SearchResult>()
        for (i in 0 until minOf(results.length(), 5)) {
            val item = results.getJSONObject(i)
            out.add(SearchResult(item.optString("title"), item.optString("content"), item.optString("url")))
        }
        return formatResults(out)
    }

    data class SearchResult(val title: String, val snippet: String, val url: String)

    companion object {
        fun formatResults(results: List<SearchResult>): String {
            if (results.isEmpty()) return "No results found."
            val b = StringBuilder()
            results.take(5).forEachIndexed { i, r ->
                b.append("[${i + 1}] ${r.title}\n${r.snippet}\nURL: ${r.url}\n\n")
            }
            return b.toString().trim()
        }

        private val TAGS = Regex("<[^>]*>")
        private val A_TAG = Regex("<a\\s[^>]*>", RegexOption.IGNORE_CASE)
        private val HREF = Regex("href\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

        /** A search page is a few hundred KB at most; never buffer more than this however much a server sends. */
        private const val MAX_RESPONSE_CHARS = 600_000
        private val WHITESPACE = Regex("\\s+")

        private fun readBounded(reader: java.io.Reader, maxChars: Int): String {
            val sb = StringBuilder()
            val buf = CharArray(8192)
            while (sb.length < maxChars) {
                val n = reader.read(buf, 0, minOf(buf.size, maxChars - sb.length))
                if (n < 0) break
                sb.append(buf, 0, n)
            }
            return sb.toString()
        }

        fun decodeEntities(text: String): String = text
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")

        fun stripHtml(html: String): String = decodeEntities(TAGS.replace(html, "")).replace(WHITESPACE, " ").trim()

        /** DuckDuckGo wraps result links as //duckduckgo.com/l/?uddg=<encoded target>&rut=...; unwrap them. */
        fun unwrapDuckDuckGoUrl(href: String): String {
            val h = decodeEntities(href)
            val i = h.indexOf("uddg=")
            if (i < 0) return if (h.startsWith("//")) "https:$h" else h
            val enc = h.substring(i + 5).substringBefore('&')
            return try { java.net.URLDecoder.decode(enc, "UTF-8") } catch (_: Exception) { enc }
        }

        /**
         * Parses html.duckduckgo.com/html/ output. Attribute order inside the <a> tags is not relied on:
         * a tag whose attributes contain `result__a` is a result title link, `result__snippet` its snippet.
         */
        fun parseDuckDuckGoHtml(html: String): List<SearchResult> {
            val out = ArrayList<SearchResult>()
            for (m in A_TAG.findAll(html)) {
                val tag = m.value
                val bodyStart = m.range.last + 1
                val bodyEnd = html.indexOf("</a>", bodyStart)
                if (bodyEnd < 0) break
                val body = stripHtml(html.substring(bodyStart, bodyEnd))
                if (tag.contains("result__a")) {
                    // A sixth title ends the scan. (Breaking right after the fifth one dropped the fifth result's snippet.)
                    if (out.size >= 5) break
                    val href = HREF.find(tag)?.groupValues?.get(1) ?: continue
                    val url = unwrapDuckDuckGoUrl(href)
                    if (body.isNotEmpty() && url.startsWith("http")) out.add(SearchResult(body, "", url))
                } else if (tag.contains("result__snippet") && out.isNotEmpty() && out.last().snippet.isEmpty()) {
                    out[out.size - 1] = out.last().copy(snippet = body)
                }
            }
            return out
        }

        fun parseWikipediaJson(jsonText: String, lang: String): List<SearchResult> {
            val arr = JSONObject(jsonText).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
            val out = ArrayList<SearchResult>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val item = arr.getJSONObject(i)
                out.add(
                    SearchResult(
                        title = item.optString("title"),
                        snippet = stripHtml(item.optString("snippet")),
                        url = "https://$lang.wikipedia.org/?curid=${item.optLong("pageid")}"
                    )
                )
            }
            return out
        }
    }
}
