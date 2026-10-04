package com.bigmoe.onedge.ui.chat

/**
 * "12.3 t/s • prefill 850 ms • cache 74%" from the engine's own RunSummary numbers; null when the
 * message carries no telemetry (user/tool messages, stopped turns).
 */
fun formatStats(tokensPerSecond: Double?, prefillMs: Long?, generatedTokens: Int?, cacheHitPct: Double?): String? {
    val parts = ArrayList<String>()
    if (tokensPerSecond != null && tokensPerSecond > 0.0) parts.add(String.format(java.util.Locale.US, "%.1f t/s", tokensPerSecond))
    if (prefillMs != null && prefillMs > 0) parts.add("prefill $prefillMs ms")
    if (generatedTokens != null && generatedTokens > 0) parts.add("$generatedTokens tok")
    if (cacheHitPct != null && cacheHitPct >= 0.0) parts.add(String.format(java.util.Locale.US, "cache %.0f%%", cacheHitPct))
    return if (parts.isEmpty()) null else parts.joinToString(" • ")
}
