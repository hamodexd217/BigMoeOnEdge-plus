package com.bigmoe.onedge.parser

/**
 * Pure helpers that turn the stored raw assistant text into what the UI shows. Stored content is the
 * engine's answer verbatim (including `<tool_call>` blocks), because that is what has to be replayed.
 */
object MessageContent {

    /** Visible text: tool-call blocks removed. */
    fun displayText(raw: String): String = ToolProtocol.stripToolCalls(raw)

    /** One short label per tool call in [raw], e.g. `web_search: latest news`. */
    fun toolCallLabels(raw: String, describer: ((String, String) -> String)? = null): List<String> =
        ToolProtocol.extractPayloads(raw).map { (payload, _) ->
            when (val r = ToolProtocol.parseCall(payload)) {
                is ToolCallParseResult.Ok -> if (describer != null) describer(r.call.name, r.call.arguments) else {
                    val firstArg = runCatching {
                        val o = org.json.JSONObject(r.call.arguments)
                        o.keys().asSequence().firstOrNull()?.let { k -> o.optString(k) }
                    }.getOrNull()
                    if (firstArg.isNullOrBlank()) r.call.name else "${r.call.name}: ${firstArg.take(80)}"
                }
                is ToolCallParseResult.Malformed -> "invalid tool call"
            }
        }

    /** Artifacts in a complete message. Ids are stable ("<messageId>#<n>") so UI state survives recomposition. */
    fun artifacts(messageId: String, raw: String): List<ArtifactModel> {
        var n = 0
        val parser = StreamOutputParser { "$messageId#${n++}" }
        val found = ArrayList<ArtifactModel>()
        for (c in parser.processToken(displayText(raw)) + parser.flush()) {
            if (c is ParsedChunk.ArtifactDetected) found.add(c.artifact)
        }
        return found
    }

    /** Body of a stored tool message without the `<tool_response>` wrapper, for display. */
    fun toolResultBody(raw: String): String =
        raw.replace(ToolProtocol.RESPONSE_OPEN, "").replace(ToolProtocol.RESPONSE_CLOSE, "").trim()
}
