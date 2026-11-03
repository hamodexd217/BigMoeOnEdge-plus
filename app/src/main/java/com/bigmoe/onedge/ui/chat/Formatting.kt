package com.bigmoe.onedge.ui.chat

import com.bigmoe.onedge.core.GenerationPhase

/**
 * "12.3 t/s • prefill 850 ms • cache 74%" from the engine's own RunSummary numbers; null when the
 * message carries no telemetry (user/tool messages, stopped turns).
 */
fun formatStats(
    tokensPerSecond: Double?,
    prefillMs: Long?,
    generatedTokens: Int?,
    cacheHitPct: Double?,
    elapsedMs: Long? = null,
    contextUsed: Int? = null,
    contextTotal: Int? = null,
    prefillTokens: Int? = null,
    thinkingMs: Long? = null
): String? {
    val parts = ArrayList<String>()
    if (tokensPerSecond != null && tokensPerSecond > 0.0) parts.add(String.format(java.util.Locale.US, "%.1f t/s", tokensPerSecond))
    if (prefillMs != null && prefillMs > 0) {
        val speed = prefillSpeed(prefillTokens, prefillMs)
        // With the prompt size known: "prefill 42 tok/s (412 tok, 9.8 s)"; older messages keep "prefill 850 ms".
        parts.add(if (speed != null) String.format(java.util.Locale.US, "prefill %.0f tok/s (%d tok, %s)", speed, prefillTokens, formatDuration(prefillMs)) else "prefill $prefillMs ms")
    }
    if (thinkingMs != null && thinkingMs > 0) parts.add("thought ${formatDuration(thinkingMs)}")
    if (generatedTokens != null && generatedTokens > 0) parts.add("$generatedTokens tok")
    if (cacheHitPct != null && cacheHitPct >= 0.0) parts.add(String.format(java.util.Locale.US, "cache %.0f%%", cacheHitPct))
    if (elapsedMs != null && elapsedMs >= 0L) parts.add(formatElapsed(elapsedMs))
    if (contextUsed != null && contextTotal != null && contextTotal > 0) {
        parts.add("${contextUsed.formatWithCommas()} / ${contextTotal.formatWithCommas()} ctx")
    }
    return if (parts.isEmpty()) null else parts.joinToString(" • ")
}

private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1000L
    return when {
        totalSeconds >= 3600 -> "${totalSeconds / 3600}h %02dm %02ds".format((totalSeconds / 60) % 60, totalSeconds % 60)
        totalSeconds >= 60 -> "${totalSeconds / 60}m %02ds".format(totalSeconds % 60)
        else -> "%.1f s".format(java.util.Locale.US, elapsedMs / 1000.0)
    }
}

private fun Int.formatWithCommas(): String = java.text.NumberFormat.getIntegerInstance().format(this)

/** "850 ms", "3.2 s", "1m 05s", "1h 02m 03s". */
fun formatDuration(ms: Long): String {
    if (ms < 1000L) return "$ms ms"
    val totalSeconds = ms / 1000L
    return when {
        totalSeconds >= 3600 -> "${totalSeconds / 3600}h %02dm %02ds".format((totalSeconds / 60) % 60, totalSeconds % 60)
        totalSeconds >= 60 -> "${totalSeconds / 60}m %02ds".format(totalSeconds % 60)
        else -> String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
    }
}

/** Prompt tokens read per second, or null when either number is missing. */
fun prefillSpeed(tokens: Int?, ms: Long?): Double? {
    if (tokens == null || ms == null || tokens <= 0 || ms <= 0L) return null
    return tokens / (ms / 1000.0)
}

/**
 * The line shown under the answer while it is being written, e.g.
 * "Thinking 8.1 s • Prefill 42 tok/s (412 tok, 9.8 s) • Generation 6.7 tok/s". [nowMs] is passed in so the caller
 * can tick it and tests can fix it.
 */
fun formatLiveStatus(t: LiveTiming, nowMs: Long): String {
    val parts = ArrayList<String>()
    val inPhase = if (t.phaseStartedAt > 0L) maxOf(0L, nowMs - t.phaseStartedAt) else 0L
    when (t.phase) {
        GenerationPhase.PROMPT -> parts.add("Reading prompt ${formatDuration(inPhase)}")
        GenerationPhase.THINKING -> parts.add("Thinking ${formatDuration(inPhase)}")
        GenerationPhase.ANSWERING -> parts.add("Answering")
        GenerationPhase.TOOL -> parts.add("Using ${t.toolNames.take(3).joinToString(", ").ifBlank { "a tool" }} ${formatDuration(inPhase)}")
    }
    if (t.phase != GenerationPhase.THINKING && t.thinkingMs != null && t.thinkingMs > 0L) parts.add("Thought for ${formatDuration(t.thinkingMs)}")
    if (t.phase != GenerationPhase.TOOL && t.toolMs != null) parts.add("Tools took ${formatDuration(t.toolMs)}")
    prefillSpeed(t.prefillTokens, t.prefillMs)?.let {
        parts.add(String.format(java.util.Locale.US, "Prefill %.0f tok/s (%d tok, %s)", it, t.prefillTokens, formatDuration(t.prefillMs)))
    }
    if (t.decodeTokensPerSecond > 0.0) parts.add(String.format(java.util.Locale.US, "Generation %.1f tok/s", t.decodeTokensPerSecond))
    return parts.joinToString(" • ")
}
