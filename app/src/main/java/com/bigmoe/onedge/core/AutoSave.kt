package com.bigmoe.onedge.core

import org.json.JSONObject

/** One named, switchable set of tuned engine settings. */
data class AutoSave(
    val id: String,
    val name: String,
    val tier: AutoTier,
    /** "MOE" | "DENSE" | "UNKNOWN": what kind of model it was built for. */
    val kind: String,
    val modelName: String,
    val createdAt: Long,
    val values: TunedValues,
    val estTps: Double,
    val measuredTps: Double? = null,
    val measuredAt: Long? = null,
    val lastError: String? = null
)

/** File format shared by the on-disk store and Export/Import. Plain JSON, versioned, never throws. */
object AutoSaveCodec {
    private const val FORMAT = "bigmoe-auto-save"
    private const val VERSION = 1
    const val MAX_NAME = 60

    fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)

    fun toJson(s: AutoSave): String {
        val v = s.values
        val values = JSONObject()
            .put("contextLength", v.contextLength).put("threadCount", v.threadCount)
            .put("batchThreadCount", v.batchThreadCount).put("batchWidth", v.batchWidth)
            .put("cacheMb", v.cacheMb).put("ioThreads", v.ioThreads).put("oDirect", v.oDirect)
            .put("overlap", v.overlap).put("ioTwoWave", v.ioTwoWave).put("nExpertUsed", v.nExpertUsed)
            .put("dropColdPct", v.dropColdPct).put("dropRenorm", v.dropRenorm)
            .put("substitutePct", v.substitutePct).put("prefetchLayers", v.prefetchLayers)
            .put("predictPrefetch", v.predictPrefetch).put("predictSpecMax", v.predictSpecMax)
            .put("routeAhead", v.routeAhead).put("spec", v.spec.name).put("draftMax", v.draftMax)
            .put("npuPrefill", v.npuPrefill)
        val o = JSONObject()
            .put("format", FORMAT).put("version", VERSION)
            .put("id", s.id).put("name", s.name).put("tier", s.tier.id).put("kind", s.kind)
            .put("modelName", s.modelName).put("createdAt", s.createdAt)
            .put("estTps", s.estTps)
            .put("values", values)
        s.measuredTps?.let { o.put("measuredTps", it) }
        s.measuredAt?.let { o.put("measuredAt", it) }
        s.lastError?.let { o.put("lastError", it) }
        return o.toString(2)
    }

    /** Returns null for anything that is not a valid save file. Values are clamped to what the engine accepts. */
    fun parse(text: String): AutoSave? = try {
        val o = JSONObject(text)
        if (o.optString("format") != FORMAT || o.optInt("version", 0) !in 1..VERSION) null else {
            val v = o.getJSONObject("values")
            val spec = runCatching { SpecSource.valueOf(v.optString("spec", "OFF")) }.getOrDefault(SpecSource.OFF)
            val name = cleanName(o.optString("name")).ifBlank { "Imported save" }
            AutoSave(
                id = o.optString("id").ifBlank { return null },
                name = name,
                tier = AutoTier.fromId(o.optString("tier")),
                kind = o.optString("kind", "UNKNOWN"),
                modelName = o.optString("modelName", ""),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                values = TunedValues(
                    contextLength = v.getInt("contextLength").coerceIn(256, 131072),
                    threadCount = v.getInt("threadCount").coerceIn(1, 32),
                    batchThreadCount = v.getInt("batchThreadCount").coerceIn(1, 32),
                    batchWidth = v.getInt("batchWidth").coerceIn(32, 8192),
                    cacheMb = v.getInt("cacheMb").coerceAtLeast(-1),
                    ioThreads = v.getInt("ioThreads").coerceIn(1, 8),
                    oDirect = v.getBoolean("oDirect"),
                    overlap = v.getBoolean("overlap"),
                    ioTwoWave = v.getBoolean("ioTwoWave"),
                    nExpertUsed = v.getInt("nExpertUsed").coerceAtLeast(0),
                    dropColdPct = v.getInt("dropColdPct").coerceIn(0, 100),
                    dropRenorm = v.getBoolean("dropRenorm"),
                    substitutePct = v.getInt("substitutePct").coerceIn(0, 100),
                    prefetchLayers = v.getInt("prefetchLayers").coerceIn(0, 8),
                    predictPrefetch = v.getBoolean("predictPrefetch"),
                    predictSpecMax = v.getInt("predictSpecMax").coerceIn(0, 8),
                    routeAhead = v.getInt("routeAhead").coerceIn(0, 8),
                    spec = spec,
                    draftMax = v.getInt("draftMax").coerceIn(1, 8),
                    npuPrefill = v.getBoolean("npuPrefill")
                ),
                estTps = o.optDouble("estTps", 0.0).takeIf { it.isFinite() } ?: 0.0,
                measuredTps = if (o.has("measuredTps")) o.optDouble("measuredTps").takeIf { it.isFinite() } else null,
                measuredAt = if (o.has("measuredAt")) o.optLong("measuredAt") else null,
                lastError = o.optString("lastError").ifBlank { null }
            )
        }
    } catch (_: Exception) {
        null
    }
}
