package com.bigmoe.onedge.ui

import com.bigmoe.onedge.parser.ArtifactModel
import com.bigmoe.onedge.parser.ArtifactType
import com.bigmoe.onedge.ui.artifact.ArtifactHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactHtmlTest {

    private fun art(type: ArtifactType, content: String) = ArtifactModel("id", "t", type, content)

    @Test
    fun mermaidSourceCannotBreakOutOfItsPre() {
        val html = ArtifactHtml.build(art(ArtifactType.MERMAID, "graph TD; A-->B </pre><script>alert(1)</script>"))
        assertFalse(html.contains("</pre><script>alert(1)"))
        assertTrue(html.contains("&lt;/pre&gt;&lt;script&gt;"))
    }

    @Test
    fun everyPageCarriesAContentSecurityPolicyThatBlocksNetworkRequests() {
        for (t in ArtifactType.values()) {
            val html = ArtifactHtml.build(art(t, "<p>x</p>"))
            assertTrue("$t has CSP", html.contains("Content-Security-Policy"))
        }
        val local = ArtifactHtml.build(art(ArtifactType.HTML, "<p>x</p>"))
        assertTrue(local.contains("connect-src 'none'"))
        assertFalse(local.contains("cdn.jsdelivr.net"))
    }

    @Test
    fun onlyMermaidMayReachTheNetwork() {
        assertTrue(ArtifactHtml.build(art(ArtifactType.MERMAID, "graph TD; A-->B")).contains("cdn.jsdelivr.net"))
        assertFalse(ArtifactHtml.build(art(ArtifactType.SVG, "<svg/>")).contains("cdn.jsdelivr.net"))
    }

    @Test
    fun completeDocumentGetsCspInjectedFirstInHead() {
        val doc = "<!DOCTYPE html><html><head><title>x</title></head><body>b</body></html>"
        val html = ArtifactHtml.build(art(ArtifactType.HTML, doc))
        val head = html.indexOf("<head>")
        assertTrue(head >= 0)
        assertTrue(html.indexOf("Content-Security-Policy") in head..(head + 200))
        assertTrue(html.contains("<title>x</title>"))
    }

    @Test
    fun htmlWithoutHeadStillGetsCsp() {
        val html = ArtifactHtml.build(art(ArtifactType.HTML, "<html><body>b</body></html>"))
        assertTrue(html.contains("Content-Security-Policy"))
    }

    @Test
    fun codeIsEscaped() {
        val html = ArtifactHtml.build(art(ArtifactType.CODE, "<div class=\"a\">&</div>"))
        assertTrue(html.contains("&lt;div class=&quot;a&quot;&gt;&amp;&lt;/div&gt;"))
    }

    @Test
    fun javaScriptOnlyWhereNeeded() {
        assertTrue(ArtifactHtml.needsJavaScript(ArtifactType.HTML))
        assertTrue(ArtifactHtml.needsJavaScript(ArtifactType.MERMAID))
        assertFalse(ArtifactHtml.needsJavaScript(ArtifactType.SVG))
        assertFalse(ArtifactHtml.needsJavaScript(ArtifactType.CODE))
    }

    @Test
    fun escapeHtmlBasics() {
        assertEquals("&lt;&amp;&gt;&quot;&#39;", ArtifactHtml.escapeHtml("<&>\"'"))
    }

    @Test fun completeDocumentGetsViewportOnlyWhenMissing() {
        val bare = ArtifactHtml.build(art(ArtifactType.HTML, "<!DOCTYPE html><html><head><title>x</title></head><body>hi</body></html>"))
        assertTrue(bare.contains("name=\"viewport\""))
        val own = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=500\"></head><body>hi</body></html>"
        val kept = ArtifactHtml.build(art(ArtifactType.HTML, own))
        assertEquals(1, Regex("name=\"viewport\"").findAll(kept).count())
        assertTrue(kept.contains("width=500"))
    }

    @Test fun fitViewportTurnsViewportUnitsIntoPixels() {
        val page = "<!DOCTYPE html><html><head><style>body{height:100vh;min-height:50vh;margin:0}.a{height:calc(100dvh - 20px)}" +
            ".b{width:100vw;line-height:1.5}</style></head><body>x</body></html>"
        val out = ArtifactHtml.fitViewport(page, 800)
        assertTrue(out.contains("height:800.0px"))
        assertTrue(out.contains("min-height:400.0px"))
        assertTrue(out.contains("calc(800.0px - 20px)"))
        assertTrue(out.contains("100vw")) // width units are untouched
        assertTrue(out.contains("<head><style>html{height:800px}</style>"))
        assertFalse(Regex("\\dvh").containsMatchIn(out))
    }

    @Test fun fitViewportLeavesWordsAndZeroHeightAlone() {
        assertEquals("<head></head>x", ArtifactHtml.fitViewport("<head></head>x", 0))
        val out = ArtifactHtml.fitViewport("<head></head><p class=\"vh\">5vhx 10vh</p>", 1000)
        assertTrue(out.contains("5vhx")) // not a unit: followed by a letter
        assertTrue(out.contains("100.0px"))
    }
}
