package com.bigmoe.onedge.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {
    @Test
    fun extractsTitleAndMainTextAndDropsBoilerplate() {
        val page = HtmlText.extract("<html><title>A &amp; B</title><body><nav>skip</nav><article><h2>Hello</h2><p>World</p></article></body></html>")
        assertEquals("A & B", page.title)
        assertTrue(page.text.contains("## Hello"))
        assertTrue(page.text.contains("World"))
        assertTrue(!page.text.contains("skip"))
    }

    @Test
    fun decodesNumericEntities() {
        assertEquals("A © 🙂", HtmlText.decodeEntities("A &#169; &#x1F642;"))
    }
}
