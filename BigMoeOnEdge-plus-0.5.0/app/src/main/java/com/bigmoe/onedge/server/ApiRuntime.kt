package com.bigmoe.onedge.server

import com.bigmoe.onedge.core.ChatGenRequest
import com.bigmoe.onedge.core.LoadConfig
import com.bigmoe.onedge.core.SamplingOverride
import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.data.local.datastore.EngineSettings

/**
 * The settings a request is handled under, read fresh for every request so changes apply immediately. Apart from the
 * server's own switches (key, force, queue wait) everything comes from the app's ordinary settings: [thinking] is the
 * app's Thinking switch, [maxTokens] the app's Max tokens (<= 0 = no cap), and sampling / context / MTP are whatever the
 * model was loaded with.
 */
data class ApiRuntime(
    val apiKey: String,
    val requireKey: Boolean,
    val force: Boolean,
    val thinking: Boolean,
    val maxTokens: Int,
    val queueTimeoutMs: Long
) {
    companion object {
        fun from(s: EngineSettings) = ApiRuntime(
            apiKey = s.serverApiKey,
            // Without the key anyone who can reach the port could use the model: off only for loopback.
            requireKey = s.serverRequireKey || s.serverBindAll,
            force = s.serverForceSettings,
            // The server has no settings of its own for these: it follows the app's (Thinking, Max tokens).
            thinking = s.thinkingEnabled,
            maxTokens = s.maxTokens,
            queueTimeoutMs = s.serverQueueTimeoutSec.coerceAtLeast(1) * 1000L
        )
    }
}

/** What a client asked for, protocol-neutral. Nullable = the client did not say. */
data class ParsedChat(
    val messagesJson: String,
    val toolsJson: String,
    val toolChoice: String,
    val stop: List<String>,
    val maxTokens: Int?,
    val temperature: Double?,
    val topP: Double?,
    val topK: Int?,
    val seed: Int?,
    /** true/false when the client asked for thinking on/off explicitly. */
    val thinking: Boolean?,
    val stream: Boolean,
    val model: String,
    /** Request fields this server does not act on; reported in the X-BigMoe-Ignored response header. */
    val ignored: List<String>,
    val includeUsage: Boolean = false
)

class ResolvedChat(val request: ChatGenRequest, val ignored: List<String>)

/** Applies the app's settings (and "Force server settings") to a parsed request. */
object RequestResolver {
    const val DEFAULT_MAX_TOKENS = 4096

    fun resolve(p: ParsedChat, rt: ApiRuntime, loaded: LoadConfig?): ResolvedChat {
        val ignored = ArrayList<String>(p.ignored)

        // ---- thinking ----
        val think: Boolean
        if (rt.force) {
            think = rt.thinking
            if (p.thinking != null && p.thinking != rt.thinking) ignored.add("thinking (forced by app settings)")
        } else {
            think = p.thinking ?: rt.thinking
        }

        // ---- output tokens: the server value is a cap; forced, it replaces the request's value ----
        val requested = p.maxTokens ?: DEFAULT_MAX_TOKENS
        val maxTokens: Int = when {
            rt.maxTokens <= 0 -> requested
            rt.force -> rt.maxTokens
            else -> minOf(requested, rt.maxTokens)
        }
        if (rt.force && rt.maxTokens > 0 && p.maxTokens != null && p.maxTokens != rt.maxTokens) ignored.add("max_tokens (forced by app settings)")

        // ---- sampling ----
        var sampling: SamplingOverride? = null
        val askedSampling = p.temperature != null || p.topP != null || p.topK != null || p.seed != null
        if (askedSampling) {
            when {
                rt.force -> {
                    if (p.temperature != null) ignored.add("temperature (forced by app settings)")
                    if (p.topP != null) ignored.add("top_p (forced by app settings)")
                    if (p.topK != null) ignored.add("top_k (forced by app settings)")
                    if (p.seed != null) ignored.add("seed (forced by app settings)")
                }
                loaded != null && loaded.spec != SpecSource.OFF -> {
                    // Guess-ahead verification is only exact under greedy decoding.
                    ignored.add("temperature/top_p/top_k/seed (guess-ahead is on: the model decodes greedily)")
                }
                else -> {
                    sampling = SamplingOverride(
                        temperature = (p.temperature ?: (loaded?.temperature ?: 0.7f).toDouble()).toFloat().coerceIn(0f, 2f),
                        topP = (p.topP ?: (loaded?.topP ?: 0.95f).toDouble()).toFloat().coerceIn(0.01f, 1f),
                        topK = (p.topK ?: loaded?.topK ?: 40).coerceIn(0, 200),
                        seed = p.seed ?: -1
                    )
                }
            }
        }

        val req = ChatGenRequest(
            messagesJson = p.messagesJson,
            toolsJson = p.toolsJson,
            toolChoice = p.toolChoice,
            maxTokens = maxTokens,
            think = think,
            stop = p.stop,
            sampling = sampling
        )
        return ResolvedChat(req, ignored)
    }
}
