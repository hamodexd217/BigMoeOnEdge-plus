package com.bigmoe.onedge.ui

import com.bigmoe.onedge.tools.ImageSearchTool
import com.bigmoe.onedge.ui.chat.ImageSearchResultParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSearchResultParserTest {
    @Test
    fun formattedSearchResultsCanBeRenderedByTheUi() {
        val result = ImageSearchTool.ImageResult(
            title = "Northern lights",
            imageUrl = "https://images.example.org/aurora.jpg",
            thumbnailUrl = "https://images.example.org/aurora-small.jpg",
            pageUrl = "https://example.org/aurora",
            source = "Example"
        )
        val body = ImageSearchTool.formatResults("aurora", listOf(result))
        val parsed = ImageSearchResultParser.parse("<tool_response>\n$body\n</tool_response>")
        assertEquals(1, parsed?.size)
        assertEquals("Northern lights", parsed?.single()?.title)
        assertEquals("https://example.org/aurora", parsed?.single()?.pageUrl)
    }

    @Test
    fun ignoresMalformedAndNonHttpsImagePayloads() {
        assertNull(ImageSearchResultParser.parse("ordinary tool response"))
        val invalid = """${ImageSearchTool.START_MARKER}{"results":[{"title":"x","image":"http://insecure.test/a.png","url":"https://example.org"}]}${ImageSearchTool.END_MARKER}"""
        assertNull(ImageSearchResultParser.parse(invalid))
        assertTrue(ImageSearchTool.formatResults("q", emptyList()).contains("No image results"))
    }
}
