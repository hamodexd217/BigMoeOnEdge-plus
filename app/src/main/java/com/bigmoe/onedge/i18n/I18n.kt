package com.bigmoe.onedge.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import org.json.JSONObject
import java.util.Locale

/**
 * Interface language. See docs/localization.md.
 *
 * Texts in the code are written in English and shown through [tr]: `tr("Save")`, `tr("{0} tokens", n)`. The English text
 * is the key. Each language is one file, `assets/i18n/<tag>.json`, a flat JSON object `{ "English text": "translation" }`;
 * a text without a translation is shown in English, so a language can be added or completed one text at a time.
 * `{0}`, `{1}`... stand for values filled in at run time and must appear in the translation exactly as in the key.
 *
 * The language is the one chosen in Settings, or else the first language of the phone's list that the app has a file for.
 * The choice is kept in SharedPreferences (not DataStore) because it has to be known synchronously, before the first
 * screen, to give the activity the right locale and layout direction.
 */
object I18n {

    /** A language the app has a translation file for. [name] is written in that language. */
    data class Language(val tag: String, val name: String, val rtl: Boolean = false)

    /** English first, then by the language's own name in Latin letters. The tag is also the file name. */
    val languages: List<Language> = listOf(
        Language("en", "English"),
        Language("ar", "العربية", rtl = true),
        Language("de", "Deutsch"),
        Language("es", "Español"),
        Language("fa", "فارسی", rtl = true),
        Language("fr", "Français"),
        Language("hi", "हिन्दी"),
        Language("id", "Bahasa Indonesia"),
        Language("it", "Italiano"),
        Language("ja", "日本語"),
        Language("ko", "한국어"),
        Language("pt-BR", "Português (Brasil)"),
        Language("ru", "Русский"),
        Language("tr", "Türkçe"),
        Language("zh-CN", "简体中文"),
        Language("zh-TW", "繁體中文")
    )

    private const val PREFS = "bigmoe_i18n"
    private const val KEY_LANGUAGE = "language"

    @Volatile private var table: Map<String, String> = emptyMap()
    @Volatile private var active: String = "en"

    /** The language texts are shown in right now (a tag from [languages]). */
    val activeTag: String get() = active

    val isRtl: Boolean get() = languages.firstOrNull { it.tag == active }?.rtl == true

    /** What the person picked in Settings: a tag from [languages], or "" for "same as the phone". */
    fun savedLanguage(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, "").orEmpty()
            .takeIf { tag -> tag.isEmpty() || languages.any { it.tag == tag } } ?: ""

    /** Keeps the choice ("" = same as the phone) and loads that language. The caller recreates the activity. */
    fun setLanguage(context: Context, tag: String) {
        val value = if (languages.any { it.tag == tag }) tag else ""
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, value).apply()
        load(context)
    }

    /** Loads the language in force. Call at start-up (Application.onCreate) and after [setLanguage]. */
    fun load(context: Context) {
        val tag = resolve(savedLanguage(context), systemLocales(context))
        active = tag
        table = readTable(context.applicationContext, tag)
    }

    /**
     * The context an activity should use: with a language picked in Settings, one whose locale (and so layout direction)
     * is that language; with "same as the phone", the context unchanged.
     */
    fun wrap(base: Context): Context {
        val saved = savedLanguage(base)
        if (saved.isEmpty()) {
            // "same as the phone": undo a language picked earlier in this process
            systemLocales(base).firstOrNull()?.let { Locale.setDefault(it) }
            return base
        }
        val locale = Locale.forLanguageTag(saved)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** [key] in the language in force, with `{0}`, `{1}`... replaced by [args]. Without a translation, the English text. */
    fun t(key: String, vararg args: Any?): String {
        val text = table[key] ?: key
        if (args.isEmpty()) return text
        // In a right-to-left language a value such as "64 tokens" or "2000 MiB" must keep its own left-to-right order.
        return fill(text, if (isRtl) Array(args.size) { isolateLtr(args[it].toString()) } else args)
    }

    // ------------------------------------------------------------------ internals (visible to tests)

    /** [s] wrapped in a left-to-right isolate when it has Latin letters or digits and no right-to-left letters. */
    internal fun isolateLtr(s: String): String {
        val latin = s.any { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }
        val rtl = s.any {
            val d = Character.getDirectionality(it)
            d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        }
        return if (latin && !rtl) "\u2066" + s + "\u2069" else s
    }

    internal fun fill(text: String, args: Array<out Any?>): String {
        if (text.indexOf('{') < 0) return text
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '{') {
                val close = text.indexOf('}', i + 1)
                if (close > i + 1) {
                    val index = text.substring(i + 1, close).toIntOrNull()
                    if (index != null && index in args.indices) {
                        out.append(args[index].toString())
                        i = close + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /**
     * The file to use: the person's pick when there is one, else the first language in the phone's list that the app
     * has. Same language, other region (es-MX, fr-CA, en-GB) uses the language's file; Chinese goes by script/region
     * (Traditional for Hant, TW, HK, MO); Portuguese uses the Brazilian file.
     */
    internal fun resolve(saved: String, system: List<Locale>): String {
        if (saved.isNotEmpty()) return saved
        for (locale in system) matchLocale(locale)?.let { return it }
        return "en"
    }

    internal fun matchLocale(locale: Locale): String? = matchTag(locale.language, locale.script, locale.country)

    internal fun matchTag(languageRaw: String, script: String, country: String): String? {
        val language = when (languageRaw) { "in" -> "id"; "iw" -> "he"; "ji" -> "yi"; else -> languageRaw }
        return when (language) {
            "zh" -> if (script == "Hant" || country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            "pt" -> "pt-BR"
            else -> languages.firstOrNull { it.tag == language }?.tag
        }
    }

    private fun systemLocales(context: Context): List<Locale> {
        // The application's configuration: the phone's language list, or on Android 13+ the language picked for this app
        // in the system settings.
        val list = context.applicationContext.resources.configuration.locales
        return (0 until list.size()).map { list.get(it) }
    }

    private fun readTable(context: Context, tag: String): Map<String, String> {
        if (tag == "en") return emptyMap()
        return try {
            val json = context.assets.open("i18n/$tag.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseTable(json)
        } catch (_: Exception) {
            emptyMap() // no file or unreadable: English
        }
    }

    internal fun parseTable(json: String): Map<String, String> {
        val obj = JSONObject(json)
        val map = HashMap<String, String>(obj.length() * 2)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optString(k, "")
            if (v.isNotEmpty()) map[k] = v
        }
        return map
    }
}

/** The text [key] in the app's language; see [I18n]. */
fun tr(key: String, vararg args: Any?): String = I18n.t(key, *args)
