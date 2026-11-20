package com.bigmoe.onedge.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class I18nTest {

    @Test
    fun placeholdersAreFilledByNumberAndOnlyByNumber() {
        assertEquals("3 of 8 tokens", I18n.fill("{0} of {1} tokens", arrayOf(3, 8)))
        assertEquals("b من a", I18n.fill("{1} من {0}", arrayOf("a", "b")))
        assertEquals("json {city} and 5", I18n.fill("json {city} and {0}", arrayOf(5)))
        assertEquals("x {2}", I18n.fill("x {2}", arrayOf(1)))
        assertEquals("v=null", I18n.fill("v={0}", arrayOf<Any?>(null)))
        assertEquals("100% \$5 z", I18n.fill("100% \$5 {0}", arrayOf("z")))
    }

    @Test
    fun aTextWithoutATranslationIsShownAsWritten() {
        assertEquals("Hello w", tr("Hello {0}", "w"))
        assertEquals("Save", tr("Save"))
    }

    @Test
    fun theFileForAPhoneLanguageFollowsLanguageScriptAndRegion() {
        assertEquals("zh-CN", I18n.matchTag("zh", "", "CN"))
        assertEquals("zh-TW", I18n.matchTag("zh", "Hant", ""))
        assertEquals("zh-TW", I18n.matchTag("zh", "", "HK"))
        assertEquals("pt-BR", I18n.matchTag("pt", "", "PT"))
        assertEquals("id", I18n.matchTag("in", "", ""))
        assertEquals("es", I18n.matchTag("es", "", "MX"))
        assertEquals("en", I18n.matchTag("en", "", "GB"))
        assertNull(I18n.matchTag("xx", "", ""))
    }

    @Test
    fun thePickedLanguageWinsElseTheFirstSupportedLanguageOfThePhone() {
        assertEquals("fr", I18n.resolve("fr", listOf(Locale("ar"))))
        assertEquals("ar", I18n.resolve("", listOf(Locale("xx"), Locale("ar", "SA"), Locale("de"))))
        assertEquals("en", I18n.resolve("", listOf(Locale("xx"))))
        assertEquals("en", I18n.resolve("", emptyList()))
    }

    @Test
    fun aTranslationFileIsAFlatObjectAndEmptyValuesAreDropped() {
        val table = I18n.parseTable("""{"Save":"حفظ","Quote \" and \\ x":"y","uni":"\u0627\u0644","empty":""}""")
        assertEquals("حفظ", table["Save"])
        assertEquals("y", table["Quote \" and \\ x"])
        assertEquals("ال", table["uni"])
        assertFalse(table.containsKey("empty"))
    }

    @Test
    fun languageTagsAreUniqueAndOnlyArabicAndPersianAreRightToLeft() {
        assertEquals(I18n.languages.size, I18n.languages.map { it.tag }.toSet().size)
        assertEquals(listOf("ar", "fa"), I18n.languages.filter { it.rtl }.map { it.tag }.sorted())
        assertTrue(I18n.languages.first().tag == "en")
    }

    @Test
    fun latinValuesInsideARightToLeftTextKeepTheirOwnOrder() {
        assertEquals("\u206664 tokens\u2069", I18n.isolateLtr("64 tokens"))
        assertEquals("2000 رمز", I18n.isolateLtr("2000 رمز"))
        assertEquals("—", I18n.isolateLtr("—"))
    }
}
