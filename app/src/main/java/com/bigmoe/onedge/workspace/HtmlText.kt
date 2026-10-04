package com.bigmoe.onedge.workspace

/** Readable text out of an HTML page (no DOM library; regex based, good enough for articles). Pure Kotlin. */
object HtmlText {

    data class Page(val title: String, val text: String)

    private val OPT = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val COMMENT = Regex("<!--.*?-->", OPT)
    private val DROP_BLOCKS = listOf("script", "style", "noscript", "svg", "template", "iframe", "form", "nav", "footer", "aside", "header")
        .map { Regex("<$it\\b[^>]*>.*?</$it\\s*>", OPT) }
    private val TITLE = Regex("<title[^>]*>(.*?)</title\\s*>", OPT)
    private val MAIN = Regex("<(main|article)\\b[^>]*>(.*?)</\\1\\s*>", OPT)
    private val BODY = Regex("<body\\b[^>]*>(.*?)</body\\s*>", OPT)
    private val HEADING = Regex("<h([1-6])\\b[^>]*>(.*?)</h\\1\\s*>", OPT)
    private val LI = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val BREAKS = Regex("</?(p|div|section|br|tr|table|ul|ol|blockquote|pre|figure|figcaption|dd|dt|dl)\\b[^>]*>|</li\\s*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val NUMERIC = Regex("&#(x?)([0-9a-fA-F]+);")

    fun decodeEntities(s: String): String {
        var r = NUMERIC.replace(s) { m ->
            val v = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (v != null && v in 1..0x10FFFF) String(Character.toChars(v)) else m.value
        }
        r = r.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&hellip;", "…").replace("&mdash;", "—").replace("&ndash;", "–")
            .replace("&laquo;", "«").replace("&raquo;", "»").replace("&copy;", "©")
        return r.replace("&amp;", "&")
    }

    fun extract(html: String): Page {
        val title = TITLE.find(html)?.groupValues?.get(1)?.let { decodeEntities(TAG.replace(it, "")).trim() }.orEmpty()
        var s = COMMENT.replace(html, "")
        // choose the main content first, then drop boilerplate blocks inside it
        val region = MAIN.find(s)?.groupValues?.get(2) ?: BODY.find(s)?.groupValues?.get(1) ?: s
        s = region
        for (r in DROP_BLOCKS) s = r.replace(s, "")
        s = HEADING.replace(s) { m -> "\n\n" + "#".repeat(m.groupValues[1].toInt()) + " " + TAG.replace(m.groupValues[2], "").trim() + "\n" }
        s = LI.replace(s, "\n- ")
        s = BREAKS.replace(s, "\n")
        s = TAG.replace(s, "")
        s = decodeEntities(s)
        val lines = s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
        val sb = StringBuilder()
        var blank = 0
        for (l in lines) {
            if (l.isEmpty()) { blank++; if (blank == 1 && sb.isNotEmpty()) sb.append('\n') } else { blank = 0; sb.append(l).append('\n') }
        }
        return Page(title, sb.toString().trim())
    }

    /** Splits page text into paragraph-ish passages of about [maxChars] for relevance ranking. */
    fun passages(text: String, maxChars: Int = 900): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (para in text.split("\n\n")) {
            val p = para.trim()
            if (p.isEmpty()) continue
            if (cur.isNotEmpty() && cur.length + p.length + 2 > maxChars) { out.add(cur.toString()); cur.setLength(0) }
            if (p.length > maxChars) {
                var i = 0
                while (i < p.length) { out.add(p.substring(i, minOf(p.length, i + maxChars))); i += maxChars }
            } else {
                if (cur.isNotEmpty()) cur.append("\n\n")
                cur.append(p)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}
