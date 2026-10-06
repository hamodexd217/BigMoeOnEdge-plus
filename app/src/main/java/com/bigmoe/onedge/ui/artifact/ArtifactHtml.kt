package com.bigmoe.onedge.ui.artifact

import com.bigmoe.onedge.parser.ArtifactModel
import com.bigmoe.onedge.parser.ArtifactType

/**
 * Builds the HTML document that the sandboxed WebView renders. Pure Kotlin (unit-tested).
 *
 * Security model (defence in depth; the WebView additionally blocks every http(s) request except the
 * Mermaid script, forbids navigation, and has no JavaScript bridge):
 *  - a Content-Security-Policy meta tag forbids network access from the page (`connect-src 'none'`,
 *    remote images/fonts/frames blocked); HTML/SVG artifacts may run inline scripts, Mermaid may load its
 *    one script from jsDelivr;
 *  - Mermaid source is HTML-escaped (it is read back as text), so it can never break out of its <pre>.
 */
object ArtifactHtml {

    const val MERMAID_SCRIPT_URL = "https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.esm.min.mjs"
    const val MERMAID_URL_PREFIX = "https://cdn.jsdelivr.net/npm/mermaid"

    private const val CSP_LOCAL =
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; " +
            "frame-src 'none'; form-action 'none'; base-uri 'none'"

    private const val CSP_MERMAID =
        "default-src 'none'; script-src 'unsafe-inline' https://cdn.jsdelivr.net; " +
            "style-src 'unsafe-inline'; img-src data:; font-src data:; " +
            "connect-src https://cdn.jsdelivr.net; frame-src 'none'; form-action 'none'; base-uri 'none'"

    /** Whether the WebView needs JavaScript for this artifact (SVG never does). */
    fun needsJavaScript(type: ArtifactType): Boolean = type == ArtifactType.HTML || type == ArtifactType.MERMAID

    fun build(artifact: ArtifactModel): String = when (artifact.type) {
        ArtifactType.HTML -> buildHtml(artifact.content)
        ArtifactType.SVG -> page(
            csp = CSP_LOCAL,
            style = "body{margin:0;display:flex;justify-content:center;align-items:center;min-height:100vh;background:#121212}svg{max-width:100%;height:auto}",
            body = artifact.content
        )
        ArtifactType.MERMAID -> """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="$CSP_MERMAID">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<script type="module">
import mermaid from '$MERMAID_SCRIPT_URL';
mermaid.initialize({ startOnLoad: true, theme: 'dark', securityLevel: 'strict' });
</script>
<style>body{margin:12px;background:#121212;color:#E0E0E0;display:flex;justify-content:center}</style>
</head><body><pre class="mermaid">${escapeHtml(artifact.content)}</pre></body></html>"""
        ArtifactType.CODE -> page(
            csp = "default-src 'none'; style-src 'unsafe-inline'",
            style = "body{margin:12px;background:#1E1E1E;color:#D4D4D4;font-family:monospace}pre{white-space:pre-wrap;word-wrap:break-word}",
            body = "<pre><code>${escapeHtml(artifact.content)}</code></pre>"
        )
    }

    private val VH_UNIT = Regex("(?<![\\w.#-])(\\d+(?:\\.\\d+)?)(?:d|s|l)?vh\\b")
    private val HEAD_OPEN = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * Makes the page independent of the WebView's own idea of its viewport height. Some devices lay a page out with
     * a zero-height viewport, so `height:100vh` + `align-items:center` put the content on the top edge (half of it
     * hidden) instead of the middle. The real height is known to the app, so every `vh` length becomes the matching
     * pixel length, and `html` gets that height so `height:100%` chains also resolve. [heightCssPx] is the visible
     * height in CSS pixels (= dp). Pure string work (unit-tested); returns [html] unchanged for a height <= 0.
     */
    fun fitViewport(html: String, heightCssPx: Int): String {
        if (heightCssPx <= 0) return html
        val replaced = VH_UNIT.replace(html) { m ->
            val v = m.groupValues[1].toDoubleOrNull() ?: return@replace m.value
            String.format(java.util.Locale.US, "%.1fpx", v * heightCssPx / 100.0)
        }
        val style = "<style>html{height:${heightCssPx}px}</style>"
        val head = HEAD_OPEN.find(replaced)
        return if (head != null) {
            replaced.substring(0, head.range.last + 1) + style + replaced.substring(head.range.last + 1)
        } else {
            // a document without <head>: build() always adds one, so this is only a fragment safety net
            style + replaced
        }
    }

    private fun buildHtml(content: String): String {
        val hasViewport = Regex("""<meta[^>]+name\s*=\s*["']?viewport""", RegexOption.IGNORE_CASE).containsMatchIn(content)
        val meta = """<meta http-equiv="Content-Security-Policy" content="$CSP_LOCAL">""" +
            if (hasViewport) "" else """<meta name="viewport" content="width=device-width, initial-scale=1.0">"""
        val looksComplete = Regex("<html[\\s>]|<!doctype", RegexOption.IGNORE_CASE).containsMatchIn(content)
        if (!looksComplete) {
            return page(
                csp = CSP_LOCAL,
                style = "body{font-family:sans-serif;margin:12px;background:#121212;color:#E0E0E0}",
                body = content
            )
        }
        // A complete document from the model: keep it, but make sure our CSP is the first thing in <head>.
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(content)
        if (head != null) return content.substring(0, head.range.last + 1) + meta + content.substring(head.range.last + 1)
        val html = Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(content)
        if (html != null) return content.substring(0, html.range.last + 1) + "<head>$meta</head>" + content.substring(html.range.last + 1)
        return "<!DOCTYPE html><html><head>$meta</head><body>$content</body></html>"
    }

    private fun page(csp: String, style: String, body: String): String =
        """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="$csp">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>$style</style></head><body>$body</body></html>"""

    fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
