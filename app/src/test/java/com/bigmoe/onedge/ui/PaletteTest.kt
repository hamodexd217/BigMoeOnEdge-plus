package com.bigmoe.onedge.ui

import com.bigmoe.onedge.ui.theme.ColorMath
import com.bigmoe.onedge.ui.theme.PalettePresets
import com.bigmoe.onedge.ui.theme.PaletteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {

    @Test
    fun thereAreManyPresetsWithUniqueIdsAndBothModes() {
        val all = PalettePresets.all
        assertTrue(all.size >= 30)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.any { it.dark } && all.any { !it.dark })
        assertNotNull(PalettePresets.byId(PalettePresets.DEFAULT_LIGHT_ID))
        assertNotNull(PalettePresets.byId(PalettePresets.DEFAULT_DARK_ID))
    }

    @Test
    fun darkFlagMatchesTheBackgroundAndTextIsReadable() {
        for (p in PalettePresets.all) {
            assertEquals("${p.id} dark flag", ColorMath.luminance(p.background) < 0.2, p.dark)
            assertTrue("${p.id} text/background contrast", ColorMath.contrast(p.text, p.background) >= 7.0)
            assertTrue("${p.id} text/surface contrast", ColorMath.contrast(p.text, p.surface) >= 4.5)
            for (c in listOf(p.primary, p.userMessage, p.aiMessage, p.codeBlock)) {
                val on = ColorMath.onColor(c)
                assertTrue("${p.id} onColor contrast", ColorMath.contrast(c, on) >= 4.5)
            }
        }
    }

    @Test
    fun hexParsingAndFormatting() {
        assertEquals(0xFF112233.toInt(), ColorMath.parseHex("#112233"))
        assertEquals(0xFFAABBCC.toInt(), ColorMath.parseHex("abc"))
        assertNull(ColorMath.parseHex("#12345"))
        assertNull(ColorMath.parseHex("zzzzzz"))
        assertEquals("#112233", ColorMath.toHex(0xFF112233.toInt()))
    }

    @Test
    fun onColorPicksReadableForeground() {
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.onColor(0xFF000000.toInt()))
        assertEquals(0xFF111111.toInt(), ColorMath.onColor(0xFFFFFFFF.toInt()))
    }

    @Test
    fun blendEndpoints() {
        assertEquals(0xFF000000.toInt(), ColorMath.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f))
    }

    @Test
    fun customPalettesRoundTripThroughJson() {
        val base = PalettePresets.byId("nord")!!
        val mine = base.copy(id = "custom_1", name = "Mine", custom = true)
        val json = PaletteStore.serialize(listOf(mine))
        val back = PaletteStore.parse(json)
        assertEquals(listOf(mine), back)
        assertTrue(PaletteStore.parse("not json").isEmpty())
        assertTrue(PaletteStore.parse("[{\"id\":\"x\"}]").isEmpty()) // missing colours -> skipped, no crash
    }

    @Test
    fun upsertReplacesById() {
        val a = PalettePresets.byId("nord")!!.copy(id = "custom_1", name = "A", custom = true)
        val list = PaletteStore.upsert(emptyList(), a)
        val renamed = PaletteStore.upsert(list, a.copy(name = "B"))
        assertEquals(1, renamed.size)
        assertEquals("B", renamed[0].name)
        assertEquals("custom_2", PaletteStore.newId(list))
    }

    @Test
    fun resolveFallsBackToADefaultForUnknownIds() {
        val custom = listOf(PalettePresets.byId("nord")!!.copy(id = "custom_1", custom = true))
        assertEquals("custom_1", PaletteStore.resolve("custom_1", custom, false).id)
        assertEquals("ocean", PaletteStore.resolve("ocean", custom, false).id)
        assertEquals(PalettePresets.DEFAULT_LIGHT_ID, PaletteStore.resolve("gone", custom, false).id)
        assertEquals(PalettePresets.DEFAULT_DARK_ID, PaletteStore.resolve("gone", custom, true).id)
    }
}
