package com.bigmoe.onedge.tools

import com.bigmoe.onedge.core.AgentController
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/** Real local HTTP server (JDK's built-in), like WebSearchToolTest. */
class ExternalToolsTest {
    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"

    private var lastMethod = ""
    private var lastUri = ""
    private var lastBody = ""
    private var lastAuth: String? = null
    private var status = 200
    private var responseBody = "ok"
    private val rpcMethods = ArrayList<String>()
    private var lastSessionHeader: String? = null

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api") { ex ->
            lastMethod = ex.requestMethod
            lastUri = ex.requestURI.toString()
            lastBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            lastAuth = ex.requestHeaders.getFirst("Authorization")
            val bytes = responseBody.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/mcp") { ex ->
            val req = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val method = req.getString("method")
            rpcMethods.add(method)
            lastSessionHeader = ex.requestHeaders.getFirst("Mcp-Session-Id")
            val id = req.opt("id")
            fun reply(result: JSONObject, sse: Boolean = false) {
                val msg = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()
                val text = if (sse) "event: message\ndata: $msg\n\n" else msg
                ex.responseHeaders.add("Content-Type", if (sse) "text/event-stream" else "application/json")
                ex.responseHeaders.add("Mcp-Session-Id", "sess-1")
                val bytes = text.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            when (method) {
                "initialize" -> reply(JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", JSONObject()))
                "notifications/initialized" -> { ex.sendResponseHeaders(202, -1); ex.close() }
                "tools/list" -> reply(JSONObject().put("tools", JSONArray().put(
                    JSONObject().put("name", "get-time").put("description", "Current time")
                        .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject().put("zone", JSONObject().put("type", "string"))))
                ).put(JSONObject().put("name", "echo").put("description", "Echo"))))
                "tools/call" -> {
                    val args = req.getJSONObject("params").getJSONObject("arguments")
                    reply(
                        JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "pong ${args.optString("zone")}"))),
                        sse = true
                    )
                }
                else -> { ex.sendResponseHeaders(404, -1); ex.close() }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun httpEntry(url: String, method: String = "GET", params: List<ExternalParam> = emptyList(), headers: String = "", ask: Boolean = true) =
        ExternalEntry(kind = ExternalKind.HTTP, name = "Get Weather", description = "Weather", url = url, method = method, params = params, headers = headers, askFirst = ask)

    @Test
    fun getFillsTheAddressEncodesValuesAndSendsHeadersAndExtraArgsAsQuery() = runBlocking {
        val entry = httpEntry(
            "$base/api/{city}", params = listOf(ExternalParam("city", "City"), ExternalParam("units", "Units", required = false)),
            headers = "Authorization: Bearer KEY\nnot a header"
        )
        val tool = HttpExternalTool(entry, "get_weather")
        responseBody = "sunny"
        val r = tool.execute(JSONObject().put("city", "São Paulo").put("units", "metric"))
        assertEquals("sunny", r)
        assertEquals("GET", lastMethod)
        assertEquals("/api/S%C3%A3o%20Paulo?units=metric", lastUri)
        assertEquals("Bearer KEY", lastAuth)
    }

    @Test
    fun postSendsAllArgumentsAsJson() = runBlocking {
        val tool = HttpExternalTool(httpEntry("$base/api", method = "POST", params = listOf(ExternalParam("q", "Query"))), "search_x")
        tool.execute(JSONObject().put("q", "hello"))
        assertEquals("POST", lastMethod)
        assertEquals("hello", JSONObject(lastBody).getString("q"))
    }

    @Test
    fun missingRequiredArgumentAndErrorStatusesAreReportedToTheModel() = runBlocking {
        val tool = HttpExternalTool(httpEntry("$base/api/{city}", params = listOf(ExternalParam("city", "City"))), "w")
        try { tool.execute(JSONObject()); fail("expected ToolInputException") } catch (e: ToolInputException) { assertTrue(e.message!!.contains("city")) }
        status = 404
        responseBody = "nope"
        assertTrue(tool.execute(JSONObject().put("city", "x")).startsWith("Error: HTTP 404"))
    }

    @Test
    fun theApprovalDialogShowsTheCallAndCanBeSwitchedOff() {
        val params = listOf(ExternalParam("city", "City"))
        val ask = HttpExternalTool(httpEntry("$base/api/{city}", params = params), "w")
        val req = ask.confirmation(JSONObject().put("city", "Oslo"))
        assertNotNull(req)
        assertTrue(req!!.message.contains("/api/Oslo"))
        assertNull(HttpExternalTool(httpEntry("$base/api", ask = false), "w").confirmation(JSONObject()))
    }

    @Test
    fun mcpListsToolsAndCallsThemOverJsonAndEventStreams() = runBlocking {
        val client = McpClient("$base/mcp")
        val tools = client.listTools()
        assertEquals(listOf("get-time", "echo"), tools.map { it.name })
        assertTrue(rpcMethods.containsAll(listOf("initialize", "notifications/initialized", "tools/list")))
        val out = McpClient("$base/mcp").callTool("get-time", JSONObject().put("zone", "UTC"))
        assertEquals("pong UTC", out)
        assertEquals("sess-1", lastSessionHeader) // the session id from initialize is sent on the later calls
    }

    @Test
    fun mcpFailuresBecomeIoExceptions() = runBlocking {
        try {
            McpClient("$base/nothing-here").listTools()
            fail("expected an exception")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.startsWith("HTTP 404"))
        }
        Unit
    }

    @Test
    fun rpcParsingPicksTheMatchingMessage() {
        val sse = "data: {\"jsonrpc\":\"2.0\",\"method\":\"progress\"}\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"x\":1}}\n\n"
        assertEquals(1, McpClient.parseRpcResponse(sse, "text/event-stream", 7).getJSONObject("result").getInt("x"))
        val batch = "[{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"y\":2}}]"
        assertEquals(2, McpClient.parseRpcResponse(batch, "application/json", 3).getJSONObject("result").getInt("y"))
        try { McpClient.parseRpcResponse("{\"id\":1,\"result\":{}}", "application/json", 9); fail() } catch (_: java.io.IOException) {}
    }

    @Test
    fun toolResultFormattingNamesNonTextAndMarksErrors() {
        val r = JSONObject("{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"bad\"},{\"type\":\"image\"}]}")
        assertEquals("Error: bad\n[image content not shown]", McpClient.formatToolResult(r))
    }

    @Test
    fun entriesSurviveAJsonRoundTripAndBrokenJsonGivesAnEmptyList() {
        val e = ExternalEntry(
            kind = ExternalKind.MCP, name = "Time Server", url = "https://x/mcp", askFirst = false,
            mcpTools = listOf(McpToolDef("a", "A", "{\"type\":\"object\"}", selected = false))
        )
        val back = ExternalToolsJson.parse(ExternalToolsJson.toJson(listOf(e, httpEntry("https://h/{q}", params = listOf(ExternalParam("q", "Q", false))))))
        assertEquals(2, back.size)
        assertEquals(ExternalKind.MCP, back[0].kind)
        assertFalse(back[0].askFirst)
        assertFalse(back[0].mcpTools.single().selected)
        assertFalse(back[1].params.single().required)
        assertTrue(ExternalToolsJson.parse("not json").isEmpty())
        assertTrue(ExternalToolsJson.parse("").isEmpty())
    }

    @Test
    fun editorTextFormatsParse() {
        assertEquals(
            listOf(ExternalParam("city", "City to look up", true), ExternalParam("units", "metric or imperial", false)),
            ExternalText.parseParams("city: City to look up\nunits?: metric or imperial\n\ncity: duplicate")
        )
        assertEquals(mapOf("Authorization" to "Bearer K", "X-Id" to "7"), ExternalText.parseHeaders("Authorization: Bearer K\n\nbroken\nX-Id:7"))
        assertEquals("get_weather_v2", ExternalText.toolName("Get Weather (v2)"))
        assertEquals("t_3d", ExternalText.toolName("3d"))
        assertEquals("external_tool", ExternalText.toolName("???"))
    }

    @Test
    fun toolNamesAreUniqueAndNeverShadowBuiltIns() {
        val a = httpEntry("https://a").copy(name = "calculate")
        val b = httpEntry("https://b").copy(name = "calculate")
        val mcp = ExternalEntry(
            kind = ExternalKind.MCP, name = "Time", url = "https://t", mcpTools = listOf(McpToolDef("get-time", "", "{}"), McpToolDef("off", "", "{}", selected = false))
        )
        val names = buildExternalTools(listOf(a, b, mcp, a.copy(enabled = false)), reserved = setOf("calculate")).map { it.name }
        assertEquals(listOf("calculate_2", "calculate_3", "time_get_time"), names)
    }

    @Test
    fun managerSyncRegistersRunsAndReplacesExternalToolsOnly() = runBlocking {
        val manager = ToolManager()
        manager.registerTool(object : Tool {
            override val name = "calculate"
            override val description = "calc"
            override val parametersSchema = JSONObject().put("type", "object")
            override suspend fun execute(arguments: JSONObject) = "4"
        })
        val entry = httpEntry("$base/api/{city}", params = listOf(ExternalParam("city", "City")), ask = false)
        manager.syncExternalTools(ExternalToolsJson.toJson(listOf(entry)))
        assertEquals(setOf("get_weather"), manager.externalToolNames())
        responseBody = "rain"
        assertEquals("rain", manager.executeToolCall("get_weather", "{\"city\":\"Oslo\"}"))
        assertTrue(manager.getToolsDescriptionPrompt(setOf("get_weather")).contains("untrusted"))
        assertFalse(manager.getToolsDescriptionPrompt(setOf("calculate")).contains("untrusted"))

        manager.syncExternalTools("[]")
        assertTrue(manager.externalToolNames().isEmpty())
        assertEquals("4", manager.executeToolCall("calculate", "{}"))
    }

    @Test
    fun externalToolsNeedTheirOwnSwitch() {
        val registered = setOf("get_weather", "calculate")
        assertTrue(AgentController.enabledToolNames(EngineSettings(), registered, setOf("get_weather")).isEmpty())
        assertEquals(
            setOf("get_weather"),
            AgentController.enabledToolNames(EngineSettings(toolsExternalEnabled = true), registered, setOf("get_weather"))
        )
    }
}
