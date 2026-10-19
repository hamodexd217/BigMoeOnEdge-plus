package com.bigmoe.onedge.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Image search with configurable Brave/SearXNG backends and a keyless DuckDuckGo fallback. */
class ImageSearchTool(
    var braveApiKey: String? = null,
    var searxngInstanceUrl: String? = null,
    private val timeoutMs: Int = 8000,
    private val duckDuckGoHomeEndpoint: String = "https://duckduckgo.com/",
    private val duckDuckGoImagesEndpoint: String = "https://duckduckgo.com/i.js"
) : Tool {
    override val name: String = "image_search"
    override val category: ToolCategory = ToolCategory.WEB
    override val parallelSafe: Boolean = true
    override val description: String = "Find images on the web and return image previews with source links."
    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().put("query", JSONObject().put("type", "string").put("description", "What the images should depict")))
        put("required", JSONArray().put("query"))
    }

    override fun describe(arguments: JSONObject): String = "Searching images for \"${arguments.optString("query").take(50)}\""

    override suspend fun execute(arguments: JSONObject): String = withContext(Dispatchers.IO) {
        val query = arguments.optString("query", "").trim()
        if (query.isBlank()) return@withContext "Error: Image search query cannot be empty."
        val results = try {
            val key = braveApiKey?.trim().orEmpty()
            val searx = searxngInstanceUrl?.trim().orEmpty()
            when {
                key.isNotEmpty() -> searchBrave(query, key)
                searx.isNotEmpty() -> searchSearxng(query, searx)
                else -> searchDuckDuckGo(query)
            }
        } catch (e: Exception) {
            return@withContext "Image search failed: ${e.message ?: e.javaClass.simpleName}"
        }
        if (results.isEmpty()) return@withContext "No image results found for: $query"
        formatResults(query, results)
    }

    private fun searchBrave(query: String, key: String): List<ImageResult> {
        val url = "https://api.search.brave.com/res/v1/images/search?q=${enc(query)}&count=4"
        val json = JSONObject(httpGet(url, mapOf("Accept" to "application/json", "X-Subscription-Token" to key)))
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until minOf(results.length(), MAX_RESULTS)).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            val props = item.optJSONObject("properties")
            val thumb = item.optJSONObject("thumbnail")
            result(
                title = item.optString("title"), image = props?.optString("url").orEmpty().ifBlank { item.optString("url") },
                thumbnail = thumb?.optString("src").orEmpty().ifBlank { item.optString("thumbnail") },
                page = item.optString("url").ifBlank { item.optString("source") }, source = item.optString("source")
            )
        }
    }

    private fun searchSearxng(query: String, baseUrl: String): List<ImageResult> {
        val base = baseUrl.trimEnd('/')
        if (!base.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException("The SearXNG URL must start with https://")
        }
        val json = JSONObject(httpGet("$base/search?q=${enc(query)}&categories=images&format=json", mapOf("Accept" to "application/json")))
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until minOf(results.length(), MAX_RESULTS)).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            result(
                title = item.optString("title"), image = item.optString("img_src").ifBlank { item.optString("image") },
                thumbnail = item.optString("thumbnail").ifBlank { item.optString("img_src") },
                page = item.optString("url"), source = item.optString("engine")
            )
        }
    }

    private fun searchDuckDuckGo(query: String): List<ImageResult> {
        val home = httpGet("$duckDuckGoHomeEndpoint?q=${enc(query)}&iax=images&ia=images", mapOf("Accept" to "text/html"))
        val token = listOf(
            Regex("vqd=['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE),
            Regex("\\\"vqd\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.find(home)?.groupValues?.getOrNull(1) }
            ?: throw IllegalStateException("DuckDuckGo did not provide an image-search token")
        val payload = httpGet(
            "$duckDuckGoImagesEndpoint?l=us-en&o=json&q=${enc(query)}&vqd=${enc(token)}&f=,,,,,&p=1",
            mapOf("Accept" to "application/json", "Referer" to duckDuckGoHomeEndpoint)
        )
        val items = JSONObject(payload).optJSONArray("results") ?: return emptyList()
        return (0 until minOf(items.length(), MAX_RESULTS)).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            result(
                title = item.optString("title"), image = item.optString("image"),
                thumbnail = item.optString("thumbnail").ifBlank { item.optString("image") },
                page = item.optString("url"), source = item.optString("source")
            )
        }
    }

    private fun result(title: String, image: String, thumbnail: String, page: String, source: String): ImageResult? {
        val imageUrl = secureUrl(image) ?: return null
        val pageUrl = secureUrl(page) ?: imageUrl
        val thumbUrl = secureUrl(thumbnail) ?: imageUrl
        return ImageResult(title.ifBlank { "Image result" }.take(180), imageUrl, thumbUrl, pageUrl, source.take(80))
    }

    private fun httpGet(url: String, headers: Map<String, String>): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BigMoeOnEdgePlus/0.4")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code from ${URL(url).host}")
            return connection.inputStream.bufferedReader().use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(8192)
                while (output.length < MAX_RESPONSE_CHARS) {
                    val n = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - output.length))
                    if (n < 0) break
                    output.append(buffer, 0, n)
                }
                output.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun secureUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        val normalized = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", ignoreCase = true) -> "https://${value.substring(7)}"
            value.startsWith("https://", ignoreCase = true) -> value
            else -> return null
        }
        return runCatching {
            val parsed = URL(normalized)
            normalized.takeIf { parsed.protocol == "https" && !parsed.host.isNullOrBlank() }
        }.getOrNull()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    data class ImageResult(val title: String, val imageUrl: String, val thumbnailUrl: String, val pageUrl: String, val source: String)

    companion object {
        const val START_MARKER = "<<<BMOE_IMAGE_RESULTS>>>"
        const val END_MARKER = "<<<END_BMOE_IMAGE_RESULTS>>>"
        private const val MAX_RESULTS = 3
        private const val MAX_RESULT_URL_CHARS = 700
        private const val MAX_PAYLOAD_CHARS = 2600
        private const val MAX_RESPONSE_CHARS = 700_000

        /** Machine-readable tool result, deliberately compact because tool results are added to the model context. */
        fun formatResults(query: String, results: List<ImageResult>): String {
            if (results.isEmpty()) return "No image results found for: ${query.take(100)}"
            val compactResults = ArrayList<JSONObject>()
            for (item in results.take(MAX_RESULTS)) {
                // Never truncate a URL mid-path: that would create an attractive but broken preview.
                if (item.imageUrl.length > MAX_RESULT_URL_CHARS || item.thumbnailUrl.length > MAX_RESULT_URL_CHARS || item.pageUrl.length > MAX_RESULT_URL_CHARS) continue
                val entry = JSONObject().apply {
                    put("title", item.title.take(110))
                    put("image", item.imageUrl)
                    if (item.thumbnailUrl != item.imageUrl) put("thumbnail", item.thumbnailUrl)
                    put("url", item.pageUrl)
                    if (item.source.isNotBlank()) put("source", item.source.take(40))
                }
                val candidate = JSONArray()
                (compactResults + entry).forEach { candidate.put(it) }
                val candidatePayload = JSONObject().put("query", query.take(100)).put("results", candidate).toString()
                if (candidatePayload.length <= MAX_PAYLOAD_CHARS) compactResults.add(entry)
            }
            if (compactResults.isEmpty()) return "Image results were found, but their source URLs were too long to show safely."
            val array = JSONArray()
            compactResults.forEach { array.put(it) }
            val payload = JSONObject().put("query", query.take(100)).put("results", array)
            // Keep the structured card data compact and at the start, so ToolManager's result limit cannot
            // cut it off behind a duplicated prose listing of the same URLs.
            return "Image search results for: ${query.take(100)}\n$START_MARKER\n$payload\n$END_MARKER"
        }
    }
}
