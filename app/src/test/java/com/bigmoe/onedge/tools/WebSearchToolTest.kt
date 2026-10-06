package com.bigmoe.onedge.tools

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/** Talks to a real local HTTP server (JDK's built-in), no mocks of HttpURLConnection. */
class WebSearchToolTest {

    private lateinit var server: HttpServer
    private var lastQuery: String? = null
    private var lastToken: String? = null
    private var status = 200
    private var body = ""

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            lastQuery = ex.requestURI.rawQuery
            lastToken = ex.requestHeaders.getFirst("X-Subscription-Token")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun args(q: String) = JSONObject().put("query", q)

    @Test
    fun braveResultsAreFormatted() = runBlocking {
        body = """{"web":{"results":[{"title":"T1","description":"D1","url":"https://a"},{"title":"T2","description":"D2","url":"https://b"}]}}"""
        val tool = WebSearchTool(braveApiKey = "KEY", braveEndpoint = "$base/res/v1/web/search")
        val r = tool.execute(args("hello world & more"))
        assertTrue(r.contains("[1] T1"))
        assertTrue(r.contains("URL: https://b"))
        assertEquals("KEY", lastToken)
        assertTrue("query is URL-encoded", lastQuery!!.contains("q=hello+world+%26+more"))
    }

    @Test
    fun braveHttpErrorIsReportedAsText() = runBlocking {
        status = 429
        body = "{}"
        val tool = WebSearchTool(braveApiKey = "KEY", braveEndpoint = "$base/x")
        assertTrue(tool.execute(args("q")).contains("429"))
    }

    @Test
    fun braveNoResults() = runBlocking {
        body = """{"web":{}}"""
        val tool = WebSearchTool(braveApiKey = "KEY", braveEndpoint = "$base/x")
        assertEquals("No results found.", tool.execute(args("q")))
    }

    @Test
    fun connectionFailureIsReportedNotThrown() = runBlocking {
        val tool = WebSearchTool(braveApiKey = "KEY", braveEndpoint = "http://127.0.0.1:1/x", timeoutMs = 500)
        assertTrue(tool.execute(args("q")).contains("failed"))
    }

    @Test
    fun emptyQueryAndMissingConfig() = runBlocking {
        val tool = WebSearchTool(keylessFallback = false)
        assertTrue(tool.execute(args(" ")).contains("empty"))
        assertTrue(tool.execute(args("q")).contains("not configured"))
    }

    @Test
    fun searxngRequiresHttps() = runBlocking {
        val tool = WebSearchTool(searxngInstanceUrl = base) // http://127.0.0.1
        assertTrue(tool.execute(args("q")).contains("https://"))
    }

    private val ddgPage = """
        <html><body>
        <div class="result results_links results_links_deep web-result">
          <h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1%26y%3D2&amp;rut=abc">Example &amp; <b>Title</b></a></h2>
          <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa">A <b>snippet</b> with &quot;quotes&quot;.</a>
        </div>
        <div class="result"><h2><a class="result__a" rel="nofollow" href="https://direct.example.org/page">Second</a></h2>
          <a class="result__snippet" href="x">Second snippet</a></div>
        <a class="result__url" href="x">ignored</a>
        </body></html>
    """.trimIndent()

    @Test
    fun duckDuckGoHtmlIsParsedAndLinksUnwrapped() {
        val r = WebSearchTool.parseDuckDuckGoHtml(ddgPage)
        assertEquals(2, r.size)
        assertEquals("Example & Title", r[0].title)
        assertEquals("https://example.com/a?x=1&y=2", r[0].url)
        assertEquals("A snippet with \"quotes\".", r[0].snippet)
        assertEquals("https://direct.example.org/page", r[1].url)
        assertEquals("Second snippet", r[1].snippet)
        assertTrue(WebSearchTool.parseDuckDuckGoHtml("<html>blocked</html>").isEmpty())
    }

    @Test
    fun wikipediaJsonIsParsed() {
        val json = """{"query":{"search":[{"title":"Cat","pageid":6678,"snippet":"The <span class=\"searchmatch\">cat</span> is a mammal"}]}}"""
        val r = WebSearchTool.parseWikipediaJson(json, "en")
        assertEquals("Cat", r[0].title)
        assertEquals("The cat is a mammal", r[0].snippet)
        assertEquals("https://en.wikipedia.org/?curid=6678", r[0].url)
        assertTrue(WebSearchTool.parseWikipediaJson("{}", "en").isEmpty())
    }

    @Test
    fun keylessSearchFallsBackFromDuckDuckGoToWikipedia() = runBlocking {
        // DuckDuckGo answers with a page that has no results; Wikipedia answers with JSON. Both are the local server.
        server.createContext("/ddg") { ex ->
            val b = "<html>no results</html>".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.createContext("/wiki") { ex ->
            val b = """{"query":{"search":[{"title":"Kotlin","pageid":1,"snippet":"A language"}]}}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        val tool = WebSearchTool(duckDuckGoEndpoint = "$base/ddg", wikipediaEndpoint = "$base/wiki/%s")
        val out = tool.execute(args("kotlin"))
        assertTrue(out, out.contains("[1] Kotlin"))
    }

    @Test
    fun keylessSearchReportsWhenEverythingFails() = runBlocking {
        val tool = WebSearchTool(
            duckDuckGoEndpoint = "http://127.0.0.1:1/ddg",
            wikipediaEndpoint = "http://127.0.0.1:1/%s",
            timeoutMs = 300
        )
        val out = tool.execute(args("anything"))
        assertTrue(out, out.startsWith("No results found"))
    }

    @Test
    fun searxngResponseParsing() {
        val tool = WebSearchTool()
        val out = tool.parseSearxngResponse("""{"results":[{"title":"A","content":"C","url":"https://u"}]}""")
        assertTrue(out.contains("[1] A"))
        assertTrue(out.contains("C"))
        assertEquals("No results found.", tool.parseSearxngResponse("{}"))
    }
}
