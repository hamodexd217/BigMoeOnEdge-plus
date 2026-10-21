package com.bigmoe.onedge.ui

import com.bigmoe.onedge.ui.chat.extractWebSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSourcesTest {
    @Test
    fun extractsMarkdownAndBareWebLinksWithoutDuplicates() {
        val links = extractWebSources(
            "Read [the docs](https://example.org/docs) or https://example.org/docs. Another: https://openai.com/research"
        )
        assertEquals(2, links.size)
        assertEquals("https://example.org/docs", links[0].first)
        assertEquals("the docs", links[0].second)
        assertEquals("https://openai.com/research", links[1].first)
    }

    @Test
    fun rejectsNonWebSchemesAndMalformedHosts() {
        val links = extractWebSources("[bad](javascript:alert(1)) file:///private and https://")
        assertTrue(links.isEmpty())
    }

    @Test
    fun sourceLabelsUseHostForPlainLinks() {
        val labels = extractWebSources("Found https://www.example.org/path").map { it.second }
        assertEquals(listOf("example.org"), labels)
        assertFalse(labels.any { it.contains("https://") })
    }
}
