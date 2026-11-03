package com.bigmoe.onedge.core

/**
 * Which GGUF architectures the bundled engine can build, and plain-language messages when a model is not one of them.
 * Pure Kotlin (unit-tested). The authority is always the native loader; this only improves what the user reads.
 */
object ModelFamilies {

    /** Architectures present in the vendored llama.cpp that matter for the families users ask about by name. */
    data class Family(val name: String, val architectures: Set<String>, val note: String)

    val LIQUID_LFM2 = Family(
        name = "Liquid LFM2 / LFM2.5",
        architectures = setOf("lfm2", "lfm2moe"),
        note = "Dense (lfm2) models load normally; the MoE variants (lfm2moe, e.g. 8B-A1B, 24B-A2B) use expert streaming."
    )

    val LIQUID_LFM3 = Family(
        name = "Liquid LFM3",
        architectures = setOf("lfm3"),
        note = "Dense hybrid (short-convolution + sliding/global attention). Vision models (LFM3-VL) also need the " +
            "matching mmproj file, whose projector type is lfm3vl. Audio / omni / decision variants are not supported."
    )

    val FLINT = Family(
        name = "Flint",
        architectures = setOf("flint"),
        note = "MoE with leading dense blocks and a shared expert; expert streaming is registered. CPU first, NPU unverified."
    )

    val NAMED = listOf(LIQUID_LFM2, LIQUID_LFM3, FLINT)

    fun familyOf(architecture: String): Family? {
        val a = architecture.trim().lowercase()
        return NAMED.firstOrNull { a in it.architectures }
    }

    private val UNKNOWN_ARCH = Regex("unknown model architecture:?\\s*'([^']*)'", RegexOption.IGNORE_CASE)

    /** The architecture named in a native "unknown model architecture: 'x'" error, or null for any other error. */
    fun unknownArchitectureIn(error: String): String? =
        UNKNOWN_ARCH.find(error)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Turns the loader's raw failure into something readable. Errors that are not about an unknown architecture are
     * returned unchanged.
     */
    fun explainLoadError(error: String): String {
        if (error.contains("fastrpc_mmap", ignoreCase = true) || error.contains("fastrpc", ignoreCase = true) && error.contains("mmap", ignoreCase = true)) {
            return error + "\n\nThe NPU (Hexagon) could not map enough memory for this model. Turn off NPU prefill for it in Settings, " +
                "or lower the context size (the NPU also holds the model's context state), or use a smaller quantisation."
        }
        val arch = unknownArchitectureIn(error) ?: return error
        val a = arch.lowercase()
        val lfm = a.startsWith("lfm") && NAMED.none { a in it.architectures }
        val supported = NAMED.joinToString("; ") { "${it.name} (${it.architectures.joinToString(", ")})" }
        return buildString {
            append("This engine build does not know the '").append(arch).append("' architecture, so the model cannot be loaded. ")
            if (lfm) append("Only LFM2 / LFM2.5 / LFM3 are built in; a newer Liquid generation needs its model graph added to the native engine first. ")
            else append("A newer model family needs its graph added to the native engine first. ")
            append("Built in for the families you asked about: ").append(supported).append(". ")
            append("Updating the vendored llama.cpp source (and rebuilding the APK) is what adds a new architecture.")
        }
    }
}
