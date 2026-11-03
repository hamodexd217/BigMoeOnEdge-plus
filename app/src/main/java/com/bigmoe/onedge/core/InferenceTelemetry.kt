package com.bigmoe.onedge.core

/** ABI: phase, evaluated prompt tokens, prefill us, generated tokens, decode us,
 * context used, capacity, proposed drafts, accepted drafts, monotonic request id.
 * Negative occupancy/draft counters mean unavailable, NOT zero. No wall-clock timings.
 */
data class InferenceTelemetry(
    val phase: InferencePhase,
    val promptTokens: Long,
    val prefillUs: Long,
    val generatedTokens: Long,
    val generationUs: Long,
    val contextUsed: Long?,
    val contextCapacity: Long?,
    val draftProposed: Long?,
    val draftAccepted: Long?,
    val requestId: Long
) {
    val promptTokensPerSecond: Double?
        get() = throughput(promptTokens, prefillUs, 1)
    val averageTokensPerSecond: Double?
        get() = throughput(generatedTokens, generationUs, 200_000)
    val remainingContext: Long?
        get() = contextUsed?.let { used -> contextCapacity?.let { (it - used).coerceAtLeast(0) } }
    val contextPercent: Double?
        get() = contextUsed?.let { used -> contextCapacity?.takeIf { it > 0 }?.let { 100.0 * used / it } }
    val draftAcceptancePercent: Double?
        get() = draftProposed?.takeIf { it > 0 }?.let { proposed ->
            draftAccepted?.takeIf { it <= proposed }?.let { 100.0 * it / proposed }
        }

    companion object {
        fun fromWire(wire: LongArray): InferenceTelemetry? {
            if (wire.size < 10 || wire[0] !in 0L..4L || (1..4).any { wire[it] < 0 }) return null
            val capacity = wire[6].takeIf { it > 0 }
            val used = wire[5].takeIf { it >= 0 && capacity != null && it <= capacity }
            return InferenceTelemetry(
                InferencePhase.entries[wire[0].toInt()], wire[1], wire[2], wire[3], wire[4],
                used, capacity, wire[7].takeIf { it >= 0 }, wire[8].takeIf { it >= 0 }, wire[9]
            )
        }
        fun throughput(tokens: Long, elapsedUs: Long, minimumUs: Long): Double? =
            if (tokens <= 0 || elapsedUs < minimumUs || elapsedUs <= 0) null
            else (tokens.toDouble() * 1_000_000.0 / elapsedUs).takeIf { it.isFinite() }
    }
}

enum class InferencePhase(val label: String) {
    IDLE("Idle"), PREFILL("Prompt processing"), GENERATION("Generating"), COMPLETE("Complete"), STOPPED("Stopped / failed")
}

/** Difference of native token/time counters, never text length or UI callback counts. */
class RecentTokenRate {
    private var previous: InferenceTelemetry? = null
    fun reset() { previous = null }
    fun sample(next: InferenceTelemetry): Double? {
        val old = previous
        if (old == null || old.requestId != next.requestId || next.generationUs < old.generationUs ||
            next.generatedTokens < old.generatedTokens || next.phase == InferencePhase.PREFILL) {
            previous = next
            return null
        }
        val elapsed = next.generationUs - old.generationUs
        if (elapsed < 400_000) return null
        previous = next
        return InferenceTelemetry.throughput(next.generatedTokens - old.generatedTokens, elapsed, 400_000)
    }
}
