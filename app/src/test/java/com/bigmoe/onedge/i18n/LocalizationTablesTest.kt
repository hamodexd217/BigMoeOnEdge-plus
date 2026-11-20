package com.bigmoe.onedge.i18n

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The translation files are data, so they are checked like code: every language has a file and the other way round,
 * the Android 13+ language list agrees, and each translation keeps the placeholders and format specifiers of its English
 * text and belongs to a text that still exists in the source. See docs/localization.md.
 */
class LocalizationTablesTest {

    private fun moduleDir(): File = if (File("src/main").isDirectory) File(".") else File("app")

    private val assets get() = File(moduleDir(), "src/main/assets/i18n")

    private fun tables(): Map<String, JSONObject> =
        assets.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .associate { it.name.removeSuffix(".json") to JSONObject(it.readText(Charsets.UTF_8)) }

    @Test
    fun everyLanguageHasAFileAndEveryFileALanguage() {
        val files = tables().keys
        val languages = I18n.languages.map { it.tag }.filter { it != "en" }.toSet()
        assertEquals("language list vs assets/i18n files", languages, files)
    }

    @Test
    fun theAndroidLanguageListMatches() {
        val xml = File(moduleDir(), "src/main/res/xml/locales_config.xml").readText()
        val tags = Regex("android:name=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toSet()
        assertEquals(I18n.languages.map { it.tag }.toSet(), tags)
    }

    private fun placeholders(s: String) = Regex("\\{\\d+\\}").findAll(s).map { it.value }.sorted().toList()
    private fun specifiers(s: String) =
        Regex("%(?:[-+ 0#]*\\d*(?:\\.\\d+)?[sdfxXeEgG]|%)").findAll(s).map { it.value }.sorted().toList()

    @Test
    fun translationsKeepPlaceholdersSpecifiersAndOuterSpaces() {
        val problems = ArrayList<String>()
        for ((lang, table) in tables()) {
            for (key in table.keys()) {
                val text = table.getString(key)
                if (text.isBlank()) problems += "$lang: empty translation for \"$key\""
                if (placeholders(key) != placeholders(text)) problems += "$lang: placeholders differ for \"$key\""
                if (specifiers(key) != specifiers(text)) problems += "$lang: format specifiers differ for \"$key\""
                if (key.length - key.trimStart().length != text.length - text.trimStart().length) problems += "$lang: leading spaces differ for \"$key\""
                if (key.length - key.trimEnd().length != text.length - text.trimEnd().length) problems += "$lang: trailing spaces differ for \"$key\""
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun everyTranslatedTextStillExistsInTheSource() {
        // Source text with \uXXXX escapes decoded, so a key written with a real bullet matches a literal written \u2022.
        val source = File(moduleDir(), "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText(Charsets.UTF_8) }
            .let { Regex("\\\\u([0-9a-fA-F]{4})").replace(it) { m -> m.groupValues[1].toInt(16).toChar().toString() } }
        fun literalForm(key: String) = key.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
            .replace("\n", "\\n").replace("\t", "\\t")
        val stale = ArrayList<String>()
        for ((lang, table) in tables()) {
            for (key in table.keys()) {
                if (!source.contains("\"" + literalForm(key) + "\"")) stale += "$lang: \"$key\""
            }
        }
        assertTrue("Translated texts that no longer appear in the code (the English text was edited?):\n" + stale.joinToString("\n"), stale.isEmpty())
    }
}
