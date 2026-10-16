package com.bigmoe.onedge.server

import com.bigmoe.onedge.core.ChatEvent
import com.bigmoe.onedge.core.ContextPlanner
import com.bigmoe.onedge.core.EngineController
import com.bigmoe.onedge.core.ModelInfo
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.InetAddress
import java.time.Instant

/**
 * The local API server: routing, auth and the glue between the HTTP layer, the protocol translators and
 * [EngineController]. Endpoints: POST /v1/messages (Anthropic, SSE streaming, tool_use), POST /v1/messages/count_tokens
 * (an estimate), POST /v1/chat/completions (OpenAI), GET /v1/models, GET /health. See docs/API_SERVER.md.
 */
class ApiServer(
    private val engine: EngineController,
    private val settings: suspend () -> EngineSettings,
    private val log: (String) -> Unit,
    /** true when a request starts being served, false when it ends (the service holds a wake lock in between). */
    private val onBusy: (Boolean) -> Unit = {}
) {
    private var http: MiniHttpServer? = null

    val boundPort: Int get() = http?.boundPort ?: -1
    val isRunning: Boolean get() = http != null

    /** Binds the socket. Throws [java.io.IOException] (e.g. port in use). */
    fun start(bindAll: Boolean, port: Int) {
        stop()
        val address = InetAddress.getByName(if (bindAll) "0.0.0.0" else "127.0.0.1")
        val s = MiniHttpServer(address, port, MAX_CONNECTIONS, log, guard = { head -> guard(head) }) { req, resp -> handle(req, resp) }
        s.start()
        http = s
        log("listening on ${address.hostAddress}:${s.boundPort}")
    }

    fun stop() {
        http?.stop()
        http = null
    }

    // ------------------------------------------------------------------------------------------------ routing

    private fun normalizedPath(raw: String): String = raw.trimEnd('/').ifEmpty { "/" }

    private fun isHealth(method: String, path: String): Boolean =
        method == "GET" && (path == "/" || path == "/health" || path == "/v1/health")

    /** The 401 body when the request must carry a key and does not, else null. */
    private fun authFailure(path: String, headers: Map<String, String>, rt: ApiRuntime): String? {
        if (!rt.requireKey || ApiKey.matches(rt.apiKey, ApiKey.fromHeaders(headers))) return null
        return if (path.endsWith("/chat/completions")) OpenAiApi.errorBody("invalid_request_error", "Incorrect API key provided.")
        else AnthropicApi.errorBody("authentication_error", "invalid x-api-key")
    }

    /**
     * Runs on the request head, before the body is read: an unauthenticated client cannot make the server buffer a
     * large body (matters when the server listens on the network).
     */
    private suspend fun guard(head: HttpHead): Rejection? {
        val path = normalizedPath(head.path)
        if (isHealth(head.method, path)) return null
        val body = authFailure(path, head.headers, ApiRuntime.from(settings())) ?: return null
        return Rejection(401, body)
    }

    suspend fun handle(req: HttpRequest, resp: HttpResponder) {
        val path = normalizedPath(req.path)
        val openAi = path.endsWith("/chat/completions")
        val rt = ApiRuntime.from(settings())

        if (isHealth(req.method, path)) {
            // Unauthenticated, so it says nothing about what is loaded.
            val o = JSONObject().put("status", "ok").put("model_loaded", engine.loadedModel() != null)
            resp.sendJson(200, o.toString())
            return
        }

        val denied = authFailure(path, req.headers, rt)
        if (denied != null) {
            resp.sendJson(401, denied)
            return
        }

        when {
            req.method == "GET" && (path == "/v1/models" || path == "/models") -> models(resp)
            req.method == "GET" && path.startsWith("/v1/models/") -> oneModel(resp)
            req.method == "POST" && path == "/v1/messages/count_tokens" -> countTokens(req, resp)
            req.method == "POST" && path == "/v1/messages" -> serveChat(req, resp, rt, openAi = false)
            req.method == "POST" && (path == "/v1/chat/completions" || path == "/chat/completions") -> serveChat(req, resp, rt, openAi = true)
            else -> {
                val known = path == "/v1/messages" || path == "/v1/chat/completions" || path == "/v1/models"
                val status = if (known) 405 else 404
                val msg = if (known) "Method ${req.method} is not allowed here" else "No such endpoint: ${req.method} ${req.path}"
                if (openAi) resp.sendJson(status, OpenAiApi.errorBody("invalid_request_error", msg))
                else resp.sendJson(status, AnthropicApi.errorBody("not_found_error", msg))
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ models

    private fun modelJson(m: ModelInfo): JSONObject = JSONObject()
        .put("id", m.name)
        .put("object", "model")
        .put("type", "model")
        .put("display_name", m.name)
        .put("created", System.currentTimeMillis() / 1000)
        .put("created_at", Instant.now().toString())
        .put("owned_by", "bigmoeonedge")
        .put("context_length", m.nCtx)

    private fun models(resp: HttpResponder) {
        val m = engine.loadedModel()
        val data = JSONArray()
        if (m != null) data.put(modelJson(m))
        val o = JSONObject().put("object", "list").put("data", data).put("has_more", false)
            .put("first_id", if (m != null) m.name else JSONObject.NULL).put("last_id", if (m != null) m.name else JSONObject.NULL)
        resp.sendJson(200, o.toString())
    }

    private fun oneModel(resp: HttpResponder) {
        val m = engine.loadedModel()
        if (m == null) resp.sendJson(404, AnthropicApi.errorBody("not_found_error", NO_MODEL))
        else resp.sendJson(200, modelJson(m).toString()) // any model name is accepted; there is only the loaded one
    }

    // ------------------------------------------------------------------------------------------------ count_tokens

    private fun countTokens(req: HttpRequest, resp: HttpResponder) {
        val json = try {
            JSONObject(String(req.body, Charsets.UTF_8))
        } catch (e: JSONException) {
            resp.sendJson(400, AnthropicApi.errorBody("invalid_request_error", "Request body is not valid JSON"))
            return
        }
        val parsed = try {
            AnthropicApi.parse(json)
        } catch (e: ApiRequestException) {
            resp.sendJson(400, AnthropicApi.errorBody("invalid_request_error", e.message ?: "invalid request"))
            return
        }
        // An estimate (about 3 bytes per token): the real count needs the tokenizer and the model's template.
        val n = estimateInput(parsed)
        resp.sendJson(200, JSONObject().put("input_tokens", n).toString(), mapOf("X-BigMoe-Estimate" to "true"))
    }

    private fun estimateInput(p: ParsedChat): Int =
        ContextPlanner.estimateTokens(p.messagesJson) + ContextPlanner.estimateTokens(p.toolsJson)

    // ------------------------------------------------------------------------------------------------ chat

    private fun err(resp: HttpResponder, openAi: Boolean, status: Int, type: String, message: String) {
        if (openAi) resp.sendJson(status, OpenAiApi.errorBody(type, message))
        else resp.sendJson(status, AnthropicApi.errorBody(type, message))
    }

    private suspend fun serveChat(req: HttpRequest, resp: HttpResponder, rt: ApiRuntime, openAi: Boolean) {
        val json = try {
            JSONObject(String(req.body, Charsets.UTF_8))
        } catch (e: JSONException) {
            err(resp, openAi, 400, "invalid_request_error", "Request body is not valid JSON")
            return
        }
        val parsed = try {
            if (openAi) OpenAiApi.parse(json) else AnthropicApi.parse(json)
        } catch (e: ApiRequestException) {
            err(resp, openAi, 400, "invalid_request_error", e.message ?: "invalid request")
            return
        }

        val loaded = engine.loadedModel()
        if (loaded == null) {
            err(resp, openAi, 503, "api_error", NO_MODEL)
            return
        }
        val resolved = RequestResolver.resolve(parsed, rt, loaded.config)
        val modelName = parsed.model.ifBlank { loaded.name }

        val lease = engine.acquireForApi(rt.queueTimeoutMs)
        if (lease == null) {
            err(resp, openAi, 529, "overloaded_error", "The model is busy with another request. Try again shortly.")
            return
        }
        onBusy(true)
        try {
            val headers = HashMap<String, String>()
            if (resolved.ignored.isNotEmpty()) headers["X-BigMoe-Ignored"] = headerSafe(resolved.ignored.joinToString("; "))
            log("${req.path}: stream=${parsed.stream} think=${resolved.request.think} max=${resolved.request.maxTokens} tools=${parsed.toolsJson.isNotEmpty()}")
            if (parsed.stream) streamChat(resp, resolved, parsed, modelName, openAi, lease, headers)
            else blockingChat(resp, resolved, modelName, openAi, lease, headers)
        } finally {
            lease.release()
            onBusy(false)
        }
    }

    private fun headerSafe(s: String): String = s.filter { it.code in 32..126 }

    private fun failureStatus(fin: ChatEvent.Finished): Triple<Int, String, String> = when {
        fin.preempted -> Triple(529, "overloaded_error", "The in-app chat took the model while this request was running. Retry shortly.")
        ContextPlanner.isContextOverflow(fin.error) -> Triple(
            400, "invalid_request_error",
            "The prompt does not fit the loaded context (${engine.loadedModel()?.nCtx ?: 0} tokens). Raise \"Context size\" in the app's settings and reload the model."
        )
        else -> Triple(500, "api_error", fin.error.ifBlank { "generation failed" })
    }

    private suspend fun blockingChat(
        resp: HttpResponder, resolved: ResolvedChat, model: String, openAi: Boolean,
        lease: com.bigmoe.onedge.core.ApiLease, headers: Map<String, String>
    ) {
        var fin: ChatEvent.Finished? = null
        engine.generateChat(lease, resolved.request).collect { ev ->
            if (ev is ChatEvent.Finished) fin = ev
        }
        val f = fin
        if (f == null) {
            err(resp, openAi, 500, "api_error", "generation ended without a result")
            return
        }
        if (!f.ok || f.cancelled) {
            val (status, type, msg) = failureStatus(f.copy(error = if (f.ok) "generation was cancelled" else f.error))
            err(resp, openAi, status, type, msg)
            return
        }
        logResult(f)
        val body = if (openAi) {
            OpenAiApi.buildResponse(f, model, Ids.random("chatcmpl-"), System.currentTimeMillis() / 1000, resolved.request.think)
        } else {
            AnthropicApi.buildResponse(f, model, Ids.random("msg_"), resolved.request.think)
        }
        resp.sendJson(200, body.toString(), headers)
    }

    private suspend fun streamChat(
        resp: HttpResponder, resolved: ResolvedChat, parsed: ParsedChat, model: String, openAi: Boolean,
        lease: com.bigmoe.onedge.core.ApiLease, headers: Map<String, String>
    ) {
        val think = resolved.request.think
        val anth = if (openAi) null else AnthropicStreamEncoder(Ids.random("msg_"), model, think, estimateInput(parsed))
        val oai = if (openAi) OpenAiStreamEncoder(Ids.random("chatcmpl-"), model, System.currentTimeMillis() / 1000, think, parsed.includeUsage) else null

        val stream = resp.beginStream(200, "text/event-stream", headers)
        fun send(frames: List<SseFrame>) {
            for (f in frames) stream.write(f.encode())
        }
        fun sendPing() {
            if (anth != null) stream.write(anth.ping().encode()) else if (oai != null) stream.write(oai.ping())
        }
        if (anth != null) send(anth.start()) else if (oai != null) send(oai.start())

        coroutineScope {
            // Keeps the client's read timeout from firing during a long prefill or a silent tool-call generation.
            // A failed write here (client gone) cancels the generation through the scope.
            val pinger = launch {
                while (true) {
                    delay(PING_CHECK_MS)
                    if (System.currentTimeMillis() - stream.lastWriteMs >= PING_AFTER_MS) sendPing()
                }
            }
            try {
                engine.generateChat(lease, resolved.request).collect { ev ->
                    when (ev) {
                        is ChatEvent.Reasoning -> send(anth?.onReasoning(ev.delta) ?: oai?.onReasoning(ev.delta) ?: emptyList())
                        is ChatEvent.Answer -> send(anth?.onAnswer(ev.delta) ?: oai?.onAnswer(ev.delta) ?: emptyList())
                        is ChatEvent.Finished -> {
                            if (ev.ok && !ev.cancelled) {
                                logResult(ev)
                                send(anth?.finish(ev) ?: oai?.finish(ev) ?: emptyList())
                            } else {
                                val (_, type, msg) = failureStatus(ev.copy(error = if (ev.ok) "generation was cancelled" else ev.error))
                                send(anth?.error(type, msg) ?: oai?.error(type, msg) ?: emptyList())
                            }
                        }
                    }
                }
            } finally {
                pinger.cancel()
            }
        }
        stream.finish()
    }

    private fun logResult(f: ChatEvent.Finished) {
        log(
            "done: stop=${f.stopReason} prompt=${f.stats.nPromptTotal} reused=${f.stats.nReused} gen=${f.stats.nGenerated} " +
                "tps=${"%.2f".format(f.stats.tokensPerSecond)} prefill=${"%.1f".format(f.stats.prefillSeconds)}s tools=${f.toolCalls.size}"
        )
    }

    companion object {
        const val MAX_CONNECTIONS = 32
        const val NO_MODEL = "No model is loaded. Open BigMoeOnEdge+ and load a model, then retry."
        private const val PING_AFTER_MS = 3_000L
        private const val PING_CHECK_MS = 1_000L
    }
}
