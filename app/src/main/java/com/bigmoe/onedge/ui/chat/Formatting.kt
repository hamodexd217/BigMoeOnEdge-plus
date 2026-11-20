package com.bigmoe.onedge.ui.chat

import com.bigmoe.onedge.core.GenerationPhase
import com.bigmoe.onedge.i18n.tr

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
        parts.add(if (speed != null) String.format(java.util.Locale.US, tr("prefill %.0f tok/s (%d tok, %s)"), speed, prefillTokens, formatDuration(prefillMs)) else tr("prefill {0} ms", prefillMs))
    }
    if (thinkingMs != null && thinkingMs > 0) parts.add(tr("thought {0}", formatDuration(thinkingMs)))
    if (generatedTokens != null && generatedTokens > 0) parts.add(tr("{0} tok", generatedTokens))
    if (cacheHitPct != null && cacheHitPct >= 0.0) parts.add(String.format(java.util.Locale.US, tr("cache %.0f%%"), cacheHitPct))
    if (elapsedMs != null && elapsedMs >= 0L) parts.add(formatElapsed(elapsedMs))
    if (contextUsed != null && contextTotal != null && contextTotal > 0) {
        parts.add(tr("{0} / {1} ctx", contextUsed.formatWithCommas(), contextTotal.formatWithCommas()))
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
    if (ms < 1000L) return tr("{0} ms", ms)
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
        GenerationPhase.PROMPT -> parts.add(tr("Reading prompt {0}", formatDuration(inPhase)))
        GenerationPhase.THINKING -> parts.add(tr("Thinking {0}", formatDuration(inPhase)))
        GenerationPhase.ANSWERING -> parts.add(tr("Answering"))
        GenerationPhase.TOOL -> parts.add(tr("Using {0} {1}", t.toolNames.take(3).joinToString(", ").ifBlank { "a tool" }, formatDuration(inPhase)))
        GenerationPhase.COMPACTING -> parts.add(tr("Summarizing earlier messages {0}", formatDuration(inPhase)))
    }
    if (t.phase != GenerationPhase.THINKING && t.thinkingMs != null && t.thinkingMs > 0L) parts.add(tr("Thought for {0}", formatDuration(t.thinkingMs)))
    if (t.phase != GenerationPhase.TOOL && t.toolMs != null) parts.add(tr("Tools took {0}", formatDuration(t.toolMs)))
    prefillSpeed(t.prefillTokens, t.prefillMs)?.let {
        parts.add(String.format(java.util.Locale.US, tr("Prefill %.0f tok/s (%d tok, %s)"), it, t.prefillTokens, formatDuration(t.prefillMs)))
    }
    if (t.decodeTokensPerSecond > 0.0) parts.add(String.format(java.util.Locale.US, tr("Generation %.1f tok/s"), t.decodeTokensPerSecond))
    return parts.joinToString(" • ")
}
