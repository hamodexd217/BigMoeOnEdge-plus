package com.bigmoe.onedge.server

import com.bigmoe.onedge.core.ChatToolCall
import com.bigmoe.onedge.core.EngineController
import com.bigmoe.onedge.core.FakeEngineBackend
import com.bigmoe.onedge.core.ScriptedChat
import com.bigmoe.onedge.core.testLoadConfig
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** End to end over a real loopback socket: HTTP parsing, auth, routing, translation, SSE, queue, with a fake engine. */
class ApiServerTest {

    private class Reply(val status: Int, val body: String, val headers: Map<String, String>)

    private val servers = ArrayList<ApiServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    private fun start(fake: FakeEngineBackend, settings: EngineSettings = EngineSettings(serverApiKey = "k", serverQueueTimeoutSec = 1), load: Boolean = true): Pair<ApiServer, EngineController> {
        val engine = EngineController({ fake }, Dispatchers.IO)
        if (load) {
            val f = File.createTempFile("model", ".gguf").apply { deleteOnExit() }
            assertNull(runBlocking { engine.loadModel(f.path, testLoadConfig()) })
        }
        val server = ApiServer(engine, { settings }, { })
        server.start(false, 0)
        servers.add(server)
        return Pair(server, engine)
    }

    private fun call(server: ApiServer, method: String, path: String, body: String? = null, headers: Map<String, String> = mapOf("x-api-key" to "k")): Reply {
        val c = URL("http://127.0.0.1:${server.boundPort}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = 20000
        for ((k, v) in headers) c.setRequestProperty(k, v)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = c.responseCode
        val stream = if (status >= 400) c.errorStream else c.inputStream
        val text = stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        val hs = HashMap<String, String>()
        for ((k, v) in c.headerFields) if (k != null && v.isNotEmpty()) hs[k.lowercase()] = v[0]
        return Reply(status, text, hs)
    }

    private val msgBody = """{"model":"claude-sonnet","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}"""

    @Test
    fun healthNeedsNoKeyButEverythingElseDoes() {
        val (server, _) = start(FakeEngineBackend())
        assertEquals(200, call(server, "GET", "/health", headers = emptyMap()).status)
        assertEquals(401, call(server, "GET", "/v1/models", headers = emptyMap()).status)
        assertEquals(401, call(server, "GET", "/v1/models", headers = mapOf("x-api-key" to "wrong")).status)
        assertEquals(200, call(server, "GET", "/v1/models", headers = mapOf("x-api-key" to "k")).status)
        assertEquals(200, call(server, "GET", "/v1/models", headers = mapOf("authorization" to "Bearer k")).status)
        val err = JSONObject(call(server, "POST", "/v1/messages", msgBody, emptyMap()).body)
        assertEquals("authentication_error", err.getJSONObject("error").getString("type"))
    }

    @Test
    fun anUnauthenticatedRequestIsRefusedBeforeItsBodyIsRead() {
        val (server, _) = start(FakeEngineBackend())
        // Announce a 50 MB body and never send it: the 401 must come back anyway, because the head alone is enough.
        java.net.Socket("127.0.0.1", server.boundPort).use { sock ->
            sock.soTimeout = 5000
            sock.getOutputStream().write(
                ("POST /v1/messages HTTP/1.1\r\nHost: x\r\nContent-Length: 50000000\r\nContent-Type: application/json\r\n\r\n").toByteArray()
            )
            sock.getOutputStream().flush()
            val first = sock.getInputStream().bufferedReader().readLine()
            assertTrue(first, first.startsWith("HTTP/1.1 401"))
        }
    }

    @Test
    fun healthDoesNotRevealTheModel() {
        val (server, _) = start(FakeEngineBackend())
        val body = call(server, "GET", "/health", headers = emptyMap()).body
        assertTrue(!body.contains(".gguf"))
        assertTrue(JSONObject(body).getBoolean("model_loaded"))
    }

    @Test
    fun maxTokensZeroIs400() {
        val (server, _) = start(FakeEngineBackend())
        assertEquals(400, call(server, "POST", "/v1/messages", """{"max_tokens":0,"messages":[{"role":"user","content":"x"}]}""").status)
    }

    @Test
    fun authCanBeSwitchedOffOnLoopback() {
        val (server, _) = start(FakeEngineBackend(), EngineSettings(serverRequireKey = false))
        assertEquals(200, call(server, "GET", "/v1/models", headers = emptyMap()).status)
    }

    @Test
    fun modelsListsTheLoadedModelWhateverTheRequestCallsIt() {
        val (server, _) = start(FakeEngineBackend())
        val list = JSONObject(call(server, "GET", "/v1/models").body)
        val id = list.getJSONArray("data").getJSONObject(0).getString("id")
        assertTrue(id.endsWith(".gguf"))
        assertEquals(200, call(server, "GET", "/v1/models/claude-opus-whatever").status)
    }

    @Test
    fun nonStreamingMessageReturnsAnAnthropicMessage() {
        val fake = FakeEngineBackend()
        fake.chatScript.add(ScriptedChat(answerTokens = listOf("Hel", "lo")))
        val (server, _) = start(fake)
        val r = call(server, "POST", "/v1/messages?beta=true", msgBody)
        assertEquals(200, r.status)
        val j = JSONObject(r.body)
        assertEquals("message", j.getString("type"))
        assertEquals("claude-sonnet", j.getString("model"))
        assertEquals("Hello", j.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("end_turn", j.getString("stop_reason"))
        // The request reached the engine in OpenAI format.
        assertEquals(100, fake.chatCalls[0].maxTokens)
        assertTrue(fake.chatCalls[0].messagesJson.contains("\"hi\""))
    }

    @Test
    fun streamingMessageIsServerSentEventsEndingInMessageStop() {
        val fake = FakeEngineBackend()
        fake.chatScript.add(ScriptedChat(answerTokens = listOf("Hel", "lo")))
        val (server, _) = start(fake)
        val r = call(server, "POST", "/v1/messages", msgBody.replace("\"max_tokens\"", "\"stream\":true,\"max_tokens\""))
        assertEquals(200, r.status)
        assertEquals("text/event-stream", r.headers["content-type"])
        val events = r.body.split("\n\n").filter { it.isNotBlank() }.map { it.lines()[0] }
        assertEquals("event: message_start", events.first())
        assertEquals("event: message_stop", events.last())
        assertTrue(r.body.contains("\"text\":\"Hel\""))
        assertTrue(r.body.contains("\"text\":\"lo\""))
    }

    @Test
    fun toolCallComesBackAsToolUse() {
        val fake = FakeEngineBackend()
        fake.chatScript.add(ScriptedChat(toolCalls = listOf(ChatToolCall("", "read_file", """{"path":"a.txt"}""")), stopReason = "tool_use"))
        val (server, _) = start(fake)
        val body = """{"max_tokens":50,"messages":[{"role":"user","content":"read it"}],"tools":[{"name":"read_file","description":"d","input_schema":{"type":"object"}}]}"""
        val j = JSONObject(call(server, "POST", "/v1/messages", body).body)
        assertEquals("tool_use", j.getString("stop_reason"))
        val tu = j.getJSONArray("content").getJSONObject(0)
        assertEquals("tool_use", tu.getString("type"))
        assertEquals("a.txt", tu.getJSONObject("input").getString("path"))
        assertTrue(fake.chatCalls[0].toolsJson.contains("read_file"))
    }

    @Test
    fun chatCompletionsWorksAndStreamsDone() {
        val fake = FakeEngineBackend()
        fake.chatScript.add(ScriptedChat(answerTokens = listOf("ok")))
        fake.chatScript.add(ScriptedChat(answerTokens = listOf("ok")))
        val (server, _) = start(fake)
        val plain = JSONObject(call(server, "POST", "/v1/chat/completions", """{"messages":[{"role":"user","content":"x"}]}""").body)
        assertEquals("ok", plain.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        val sse = call(server, "POST", "/v1/chat/completions", """{"stream":true,"messages":[{"role":"user","content":"x"}]}""")
        assertTrue(sse.body.trimEnd().endsWith("data: [DONE]"))
    }

    @Test
    fun noModelLoadedIs503() {
        val (server, _) = start(FakeEngineBackend(), load = false)
        assertEquals(503, call(server, "POST", "/v1/messages", msgBody).status)
    }

    @Test
    fun busyEngineIs529AfterTheQueueTimeout() = runBlocking {
        val (server, engine) = start(FakeEngineBackend())
        val lease = engine.acquireForApi(1000)
        assertNotNull(lease)
        val t0 = System.currentTimeMillis()
        val r = call(server, "POST", "/v1/messages", msgBody)
        assertEquals(529, r.status)
        assertEquals("overloaded_error", JSONObject(r.body).getJSONObject("error").getString("type"))
        assertTrue(System.currentTimeMillis() - t0 >= 900)
        lease!!.release()
    }

    @Test
    fun contextOverflowIs400() {
        val fake = FakeEngineBackend()
        fake.chatScript.add(ScriptedChat(ok = false, error = "prompt + n_predict exceeds the session n_ctx (4096); open the session with a larger n_ctx"))
        val (server, _) = start(fake)
        val r = call(server, "POST", "/v1/messages", msgBody)
        assertEquals(400, r.status)
        assertEquals("invalid_request_error", JSONObject(r.body).getJSONObject("error").getString("type"))
    }

    @Test
    fun ignoredParametersAreReportedInAHeader() {
        val fake = FakeEngineBackend()
        fake.chatScript.add(ScriptedChat(answerTokens = listOf("x")))
        val (server, _) = start(fake)
        val r = call(server, "POST", "/v1/messages", """{"max_tokens":5,"thinking":{"type":"enabled","budget_tokens":1000},"messages":[{"role":"user","content":"x"}]}""")
        assertEquals(200, r.status)
        assertTrue(r.headers["x-bigmoe-ignored"]!!.contains("budget_tokens"))
    }

    @Test
    fun countTokensIsAnEstimate() {
        val (server, _) = start(FakeEngineBackend())
        val r = call(server, "POST", "/v1/messages/count_tokens", msgBody)
        assertEquals(200, r.status)
        assertTrue(JSONObject(r.body).getInt("input_tokens") > 0)
        assertEquals("true", r.headers["x-bigmoe-estimate"])
    }

    @Test
    fun badJsonIs400AndUnknownPathIs404() {
        val (server, _) = start(FakeEngineBackend())
        assertEquals(400, call(server, "POST", "/v1/messages", "{nope").status)
        assertEquals(404, call(server, "GET", "/v1/nothing").status)
        assertEquals(400, call(server, "POST", "/v1/messages", """{"max_tokens":5}""").status)
    }
}
