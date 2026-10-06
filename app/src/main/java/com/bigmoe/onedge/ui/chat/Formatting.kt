package com.bigmoe.onedge.ui.chat

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
    contextTotal: Int? = null
): String? {
    val parts = ArrayList<String>()
    if (tokensPerSecond != null && tokensPerSecond > 0.0) parts.add(String.format(java.util.Locale.US, "%.1f t/s", tokensPerSecond))
    if (prefillMs != null && prefillMs > 0) parts.add("prefill $prefillMs ms")
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
