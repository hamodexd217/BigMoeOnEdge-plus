package com.bigmoe.onedge.ui

import com.bigmoe.onedge.ui.chat.NativeUiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeUiParserTest {
    @Test
    fun parsesRichNativeComponentsTogether() {
        val spec = NativeUiParser.parse(
            """{
              "type":"rich","title":"Device overview","description":"Compact summary",
              "badges":[{"label":"Verified","tone":"success"}],
              "metrics":[{"label":"RAM","value":"12 GB","detail":"Installed"}],
              "comparison":{"title":"Compare","leftTitle":"A","rightTitle":"B","rows":[{"label":"Memory","left":"12 GB","right":"16 GB","highlight":true}]},
              "slides":[{"title":"Slide one","subtitle":"Intro","body":"Details","metricLabel":"Score","metricValue":"92"}],
              "lists":[{"title":"Steps","style":"ordered","items":[{"title":"First","detail":"Start"},"Second"]}],
              "cards":[{"title":"Option A","subtitle":"Fast","body":"Details"}],
              "bars":[{"label":"Progress","value":70,"valueLabel":"70%"}],
              "timeline":[{"time":"Today","title":"Done","detail":"Completed","status":"done"}],
              "sections":[{"title":"More","body":"Expanded details"}],
              "actions":[{"label":"Explain","prompt":"Explain this"}]
            }""".trimIndent()
        )

        assertNotNull(spec)
        assertEquals("Device overview", spec!!.title)
        assertEquals(1, spec.badges.size)
        assertEquals("12 GB", spec.metrics.single().value)
        assertTrue(spec.comparison!!.rows.single().highlight)
        assertEquals("Slide one", spec.slides.single().title)
        assertEquals(2, spec.lists.single().items.size)
        assertEquals("Second", spec.lists.single().items.last().title)
        assertEquals(1, spec.cards.size)
        assertEquals(70.0, spec.bars.single().value, 0.0)
        assertEquals("done", spec.timeline.single().status)
        assertEquals("Expanded details", spec.sections.single().body)
        assertEquals("Explain this", spec.actions.single().prompt)
    }

    @Test
    fun clampsProgressAndBoundsComponentCollections() {
        val metrics = (0..10).joinToString(",") { "{\"label\":\"M$it\",\"value\":\"$it\"}" }
        val spec = NativeUiParser.parse(
            """{"type":"dashboard","title":"Limits","metrics":[$metrics],"bars":[{"label":"Over","value":150}]}"""
        )
        assertNotNull(spec)
        assertEquals(8, spec!!.metrics.size)
        assertEquals(100.0, spec.bars.single().value, 0.0)
        assertEquals("100%", spec.bars.single().valueLabel)
    }

    @Test
    fun rejectsUnknownTypesMalformedJsonAndEmptyLayouts() {
        assertNull(NativeUiParser.parse("not json"))
        assertNull(NativeUiParser.parse("{\"type\":\"unknown\",\"title\":\"X\"}"))
        assertNull(NativeUiParser.parse("{\"type\":\"dashboard\",\"title\":\"Empty\"}"))
    }

    @Test
    fun acceptsAMissingTitleAndMissingType() {
        val spec = NativeUiParser.parse("""{"comparison":{"leftTitle":"Coffee","rightTitle":"Tea","rows":[{"label":"Caffeine","left":"high","right":"medium"}]}}""")
        assertNotNull(spec)
        assertEquals("Comparison", spec!!.title)
        assertEquals("Coffee", spec.comparison!!.leftTitle)
    }

    @Test
    fun acceptsRowsOnTheRootAndAlternativeKeyNames() {
        val spec = NativeUiParser.parse("""{"title":"A vs B","rows":[{"name":"Speed","a":"fast","b":"slow"}]}""")
        assertNotNull(spec)
        assertEquals("fast", spec!!.comparison!!.rows.single().left)
        assertEquals("slow", spec.comparison!!.rows.single().right)
    }

    @Test
    fun repairsTruncatedAndTrailingCommaJson() {
        val cut = NativeUiParser.parse("""{"type":"rich","title":"Cut","metrics":[{"label":"RAM","value":"12 GB"},""")
        assertNotNull(cut)
        assertEquals("12 GB", cut!!.metrics.single().value)
        val comma = NativeUiParser.parse("""Here you go: {"type":"rich","title":"T","badges":["x",],}  thanks""")
        assertNotNull(comma)
        assertEquals("x", comma!!.badges.single().label)
    }

    @Test
    fun plainJsonNeedsAKnownTypeButTheDedicatedFenceDoesNot() {
        val noType = """{"title":"T","metrics":[{"label":"a","value":"1"}]}"""
        assertTrue(!NativeUiParser.looksLikeSpec(noType))
        assertTrue(NativeUiParser.looksLikeSpec("""{"type":"dashboard","title":"T","metrics":[{"label":"a","value":"1"}]}"""))
        assertNotNull(NativeUiParser.parse(noType))
        assertTrue(NativeUiParser.isUiLanguage("BMOE-UI"))
        assertTrue(NativeUiParser.isUiLanguage("native_ui"))
        assertTrue(!NativeUiParser.isUiLanguage("json"))
    }
}
