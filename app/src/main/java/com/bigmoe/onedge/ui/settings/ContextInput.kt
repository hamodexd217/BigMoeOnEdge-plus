package com.bigmoe.onedge.ui.settings

/**
 * The typed "Context (tokens)" setting. Pure Kotlin (unit-tested). There is deliberately no upper limit: the person
 * decides, and a value the device cannot hold shows up as a normal load error. Only 0 / empty is refused.
 */
object ContextInput {
    /** Digits only (pasted text may contain anything), at most nine so the number always fits an Int. */
    fun sanitize(raw: String): String = raw.filter { it in '0'..'9' }.take(9)

    /** The number to store, or null while the text is empty or zero. */
    fun parse(text: String): Int? = text.toIntOrNull()?.takeIf { it >= 1 }
}
