package com.bigmoe.onedge.ui.theme

import org.json.JSONArray
import org.json.JSONObject

/**
 * A colour palette as plain ARGB ints (no Compose types, so it is unit-testable on the JVM and trivially
 * serialisable). [Theme.kt] turns it into a Material colour scheme plus the chat-specific colours.
 *
 * The nine editable colours are exactly the ones the custom-palette editor offers.
 */
data class PaletteSpec(
    val id: String,
    val name: String,
    /** Dark palettes get a dark Material scheme (status-bar icons, default component colours). */
    val dark: Boolean,
    val primary: Int,
    val secondary: Int,
    val background: Int,
    val surface: Int,
    val text: Int,
    val accent: Int,
    val userMessage: Int,
    val aiMessage: Int,
    val codeBlock: Int,
    val custom: Boolean = false
)

object ColorMath {
    private fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF

    fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Mix of [a] and [b]; [t] is the share of [b] (0 = a, 1 = b). Result is opaque. */
    fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun mix(shift: Int): Int = (ch(a, shift) * (1f - k) + ch(b, shift) * k).toInt().coerceIn(0, 255)
        return argb(mix(16), mix(8), mix(0))
    }

    /** WCAG relative luminance, 0 (black) .. 1 (white). */
    fun luminance(c: Int): Double {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(ch(c, 16)) + 0.7152 * lin(ch(c, 8)) + 0.0722 * lin(ch(c, 0))
    }

    /** WCAG contrast ratio, 1 .. 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private const val NEAR_BLACK = 0xFF111111.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Black or white, whichever reads better on [bg]. */
    fun onColor(bg: Int): Int = if (contrast(bg, WHITE) >= contrast(bg, NEAR_BLACK)) WHITE else NEAR_BLACK

    fun toHex(c: Int): String = "#" + String.format(java.util.Locale.US, "%06X", c and 0xFFFFFF)

    /** Accepts "#RRGGBB", "RRGGBB", "#RGB" (case-insensitive); null when invalid. Result is opaque. */
    fun parseHex(text: String): Int? {
        var t = text.trim().removePrefix("#")
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6 || !t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return (0xFF shl 24) or t.toInt(16)
    }
}

object PalettePresets {
    const val SYSTEM_ID = "system"
    const val DEFAULT_LIGHT_ID = "paper"
    const val DEFAULT_DARK_ID = "emerald_dark"

    private fun p(
        id: String, name: String, dark: Boolean,
        primary: String, secondary: String, bg: String, surface: String, text: String,
        accent: String, user: String, ai: String, code: String
    ) = PaletteSpec(
        id, name, dark,
        primary = hex(primary), secondary = hex(secondary), background = hex(bg), surface = hex(surface),
        text = hex(text), accent = hex(accent), userMessage = hex(user), aiMessage = hex(ai), codeBlock = hex(code)
    )

    private fun hex(s: String): Int = ColorMath.parseHex(s) ?: error("bad preset colour $s")

    val all: List<PaletteSpec> = listOf(
        p("paper", "Paper", false, "1D5D8A", "535F70", "F3EFE0", "EFEAD8", "2C2A29", "7A5932", "1D5D8A", "E5DFCC", "DDD6C0"),
        p("mint_light", "Mint Light", false, "006A4E", "4C6358", "FBFDF8", "F0F4EE", "191C1A", "3D6373", "006A4E", "E1E4DC", "D9DED5"),
        p("ocean", "Ocean", false, "0B6E99", "3E7CB1", "F2F8FC", "E3EEF6", "0F2A3A", "00A6A6", "0B6E99", "DCE9F3", "CFE0EC"),
        p("sage", "Sage", false, "4F7F5C", "7A9A80", "F1F5EE", "E4EBE0", "1F2A22", "B08D57", "4F7F5C", "DCE6D7", "D2DDCB"),
        p("sunset", "Sunset", false, "E4572E", "F29E4C", "FFF4EA", "FDE7D6", "3A2218", "C1121F", "E4572E", "F9DCC4", "F2CFB3"),
        p("lavender", "Lavender", false, "7C5CBF", "9C86D4", "F6F2FC", "EAE3F6", "2A2340", "E56B9A", "7C5CBF", "E3DAF2", "D9CEEC"),
        p("rose", "Rose", false, "C2185B", "E57399", "FFF5F8", "FBE3EB", "3B1524", "F4A261", "C2185B", "F8D7E2", "F0C9D7"),
        p("sakura", "Sakura", false, "D6577F", "F2A7BE", "FFF8FA", "FCEBF0", "3A2A30", "8FBF9F", "D6577F", "F9DFE7", "EFD3DB"),
        p("mint", "Mint", false, "12A594", "5FC8B8", "F0FBF9", "DFF3EF", "10312C", "F2A65A", "12A594", "D3EDE8", "C3E4DE"),
        p("slate", "Slate", false, "475569", "64748B", "F1F5F9", "E2E8F0", "0F172A", "0EA5E9", "334155", "E2E8F0", "D5DDE8"),
        p("sand", "Sand", false, "9C6644", "B08968", "FAF3E8", "EFE3D0", "3A2D22", "606C38", "9C6644", "EADCC4", "E0D0B4"),
        p("solarized_light", "Solarized Light", false, "268BD2", "2AA198", "FDF6E3", "EEE8D5", "073642", "CB4B16", "268BD2", "EEE8D5", "E4DCC0"),
        p("gruvbox_light", "Gruvbox Light", false, "076678", "427B58", "FBF1C7", "EBDBB2", "3C3836", "D65D0E", "076678", "EBDBB2", "E0CFA0"),
        p("latte", "Catppuccin Latte", false, "1E66F5", "8839EF", "EFF1F5", "E6E9EF", "4C4F69", "FE640B", "1E66F5", "DCE0E8", "CCD0DA"),
        p("emerald_dark", "Emerald Dark", true, "70DBAF", "B3CCBF", "101412", "1A1F1C", "E1E3DF", "A5CCE0", "1F6F55", "26302B", "0C0F0D"),
        p("amoled", "AMOLED Black", true, "70DBAF", "B3CCBF", "000000", "0A0A0A", "E6E6E6", "A5CCE0", "00513A", "141414", "0D0D0D"),
        p("midnight", "Midnight Blue", true, "6EA8FE", "8AB4F8", "0B1220", "121B2E", "E6ECF7", "4FD1C5", "2D5BB9", "1A2540", "0A0F1A"),
        p("forest", "Forest", true, "7BC47F", "A5D6A7", "0E1A12", "15261B", "E3EFE4", "D4E157", "2E7D32", "1D3324", "09120C"),
        p("ember", "Ember", true, "FF7A45", "FFB15C", "1A1210", "261A16", "F3E6DE", "FFD166", "C2461C", "33231D", "120C0A"),
        p("amethyst", "Amethyst", true, "B79CFF", "D0BCFF", "15101F", "1F182D", "EBE5F5", "FF8FB8", "5B3FA6", "2A2140", "0E0A16"),
        p("graphite", "Graphite", true, "A0AEC0", "718096", "121417", "1B1F24", "E2E8F0", "63B3ED", "3A4556", "232830", "0C0E10"),
        p("solarized_dark", "Solarized Dark", true, "268BD2", "2AA198", "002B36", "073642", "EEE8D5", "B58900", "1B6A9F", "073642", "001F27"),
        p("nord", "Nord", true, "88C0D0", "81A1C1", "2E3440", "3B4252", "ECEFF4", "EBCB8B", "5E81AC", "3B4252", "242933"),
        p("dracula", "Dracula", true, "BD93F9", "FF79C6", "282A36", "343746", "F8F8F2", "50FA7B", "6D4FB3", "343746", "1E1F29"),
        p("monokai", "Monokai", true, "A6E22E", "66D9EF", "272822", "33342D", "F8F8F2", "F92672", "5B7F1A", "3E3D32", "1D1E19"),
        p("gruvbox_dark", "Gruvbox Dark", true, "83A598", "8EC07C", "282828", "3C3836", "EBDBB2", "FE8019", "3D7A7D", "3C3836", "1D2021"),
        p("tokyo_night", "Tokyo Night", true, "7AA2F7", "BB9AF7", "1A1B26", "24283B", "C0CAF5", "FF9E64", "3D59A1", "24283B", "16161E"),
        p("mocha", "Catppuccin Mocha", true, "89B4FA", "CBA6F7", "1E1E2E", "313244", "CDD6F4", "FAB387", "4A6FC0", "313244", "181825"),
        p("coffee", "Coffee", true, "D2A679", "B08968", "1B1310", "2A1E19", "EADBC8", "E6B17E", "7F5539", "3A2A22", "120C09"),
        p("crimson", "Crimson", true, "E63946", "FF6B6B", "160B0D", "231316", "F5E6E7", "F4A261", "A4161A", "2E1A1D", "100708"),
        p("cyberpunk", "Cyberpunk", true, "FCEE0A", "00F0FF", "0D0221", "1A0B33", "F4F1FF", "FF2A6D", "6B2FBF", "2A1250", "08011A"),
        p("aurora", "Aurora", true, "4ADE80", "22D3EE", "0B1416", "12222A", "E6F4F1", "A78BFA", "1F7A5A", "16303A", "081014")
    )

    fun byId(id: String): PaletteSpec? = all.firstOrNull { it.id == id }
}

/** Custom palettes live in the settings as one JSON array string. */
object PaletteStore {

    fun parse(json: String): List<PaletteSpec> {
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<PaletteSpec>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            fun c(key: String): Int? = ColorMath.parseHex(o.optString(key, ""))
            val id = o.optString("id", "").takeIf { it.isNotBlank() } ?: continue
            out.add(
                PaletteSpec(
                    id = id,
                    name = o.optString("name", "Custom").ifBlank { "Custom" },
                    dark = o.optBoolean("dark", false),
                    primary = c("primary") ?: continue,
                    secondary = c("secondary") ?: continue,
                    background = c("background") ?: continue,
                    surface = c("surface") ?: continue,
                    text = c("text") ?: continue,
                    accent = c("accent") ?: continue,
                    userMessage = c("userMessage") ?: continue,
                    aiMessage = c("aiMessage") ?: continue,
                    codeBlock = c("codeBlock") ?: continue,
                    custom = true
                )
            )
        }
        return out
    }

    fun serialize(palettes: List<PaletteSpec>): String {
        val arr = JSONArray()
        for (p in palettes) {
            arr.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name).put("dark", p.dark)
                    .put("primary", ColorMath.toHex(p.primary)).put("secondary", ColorMath.toHex(p.secondary))
                    .put("background", ColorMath.toHex(p.background)).put("surface", ColorMath.toHex(p.surface))
                    .put("text", ColorMath.toHex(p.text)).put("accent", ColorMath.toHex(p.accent))
                    .put("userMessage", ColorMath.toHex(p.userMessage)).put("aiMessage", ColorMath.toHex(p.aiMessage))
                    .put("codeBlock", ColorMath.toHex(p.codeBlock))
            )
        }
        return arr.toString()
    }

    /** Insert or replace by id. */
    fun upsert(existing: List<PaletteSpec>, palette: PaletteSpec): List<PaletteSpec> {
        val i = existing.indexOfFirst { it.id == palette.id }
        return if (i >= 0) existing.toMutableList().also { it[i] = palette } else existing + palette
    }

    fun newId(existing: List<PaletteSpec>): String {
        var n = existing.size + 1
        while (existing.any { it.id == "custom_$n" }) n++
        return "custom_$n"
    }

    /**
     * Palette for [themeId]: a custom one, a preset, or (unknown / "system") the default light or dark preset
     * depending on [systemDark].
     */
    fun resolve(themeId: String, custom: List<PaletteSpec>, systemDark: Boolean): PaletteSpec {
        custom.firstOrNull { it.id == themeId }?.let { return it }
        PalettePresets.byId(themeId)?.let { return it }
        val fallbackId = if (systemDark) PalettePresets.DEFAULT_DARK_ID else PalettePresets.DEFAULT_LIGHT_ID
        return PalettePresets.byId(fallbackId)!!
    }
}
