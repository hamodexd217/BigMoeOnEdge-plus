package com.bigmoe.onedge.workspace

import kotlin.math.ln

/**
 * Turns an uploaded text file into prompt text that fits the context. Small files go in whole; large ones
 * are split into line-based chunks, ranked against the user's message with BM25, and only the best chunks
 * (in file order, plus the start of the file) are included, each labelled with its line range, with a note
 * telling the model it can open the rest with the file tools. Pure Kotlin.
 */
object FileContext {

    data class Chunk(val startLine: Int, val endLine: Int, val text: String)

    data class Rendered(
        val text: String,
        val totalLines: Int,
        val shownLines: Int,
        val truncated: Boolean
    )

    /** ~3 UTF-8 bytes per token; same estimate as ContextPlanner (kept local so this file has no app deps). */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return bytes / 3 + 4
    }

    /** Line-based chunks of about [maxChars]; consecutive chunks overlap by [overlapLines]. Lines are 1-based. */
    fun chunk(text: String, maxChars: Int = 1400, overlapLines: Int = 2): List<Chunk> {
        val lines = text.split("\n")
        val out = ArrayList<Chunk>()
        var start = 0
        while (start < lines.size) {
            var end = start
            var size = 0
            while (end < lines.size && (size == 0 || size + lines[end].length + 1 <= maxChars)) {
                size += lines[end].length + 1
                end++
            }
            // a single very long line is cut so one chunk can never blow the budget
            val body = lines.subList(start, end).joinToString("\n").let { if (it.length > maxChars) it.take(maxChars) + " …" else it }
            out.add(Chunk(start + 1, end, body))
            if (end >= lines.size) break
            start = maxOf(end - overlapLines, start + 1)
        }
        return out
    }

    private val STOP = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "is", "are", "was", "be", "it", "this", "that", "for", "with",
        "what", "how", "why", "can", "you", "please", "file", "me", "my", "about", "from", "as", "at", "by", "do", "does",
        "في", "من", "على", "الى", "إلى", "عن", "هذا", "هذه", "ما", "هل", "ملف", "الملف"
    )

    fun tokenize(s: String): List<String> =
        Regex("[\\p{L}\\p{N}_]{2,}").findAll(s.lowercase()).map { it.value }.toList()

    /** BM25 score of every chunk for [query] (all zeros when the query has no usable terms). */
    fun scores(chunks: List<Chunk>, query: String): DoubleArray {
        val terms = tokenize(query).filter { it !in STOP }.distinct()
        val result = DoubleArray(chunks.size)
        if (terms.isEmpty() || chunks.isEmpty()) return result
        val docs = chunks.map { tokenize(it.text) }
        val avg = docs.map { it.size }.average().coerceAtLeast(1.0)
        val n = chunks.size.toDouble()
        for (term in terms) {
            val df = docs.count { it.contains(term) }.toDouble()
            if (df == 0.0) continue
            val idf = ln(1.0 + (n - df + 0.5) / (df + 0.5))
            for (i in docs.indices) {
                val tf = docs[i].count { it == term }.toDouble()
                if (tf == 0.0) continue
                val k1 = 1.4
                val b = 0.75
                result[i] += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * docs[i].size / avg))
            }
        }
        return result
    }

    /** Picks chunks for [query] within [budgetTokens]: the first chunk, then best-scoring ones; returns file order. */
    fun select(chunks: List<Chunk>, query: String, budgetTokens: Int): List<Chunk> {
        if (chunks.isEmpty()) return emptyList()
        val sc = scores(chunks, query)
        val order = chunks.indices.sortedWith(compareByDescending<Int> { sc[it] }.thenBy { it })
        val picked = LinkedHashSet<Int>()
        var used = 0
        fun tryAdd(i: Int): Boolean {
            val cost = estimateTokens(chunks[i].text) + 12
            if (used + cost > budgetTokens && picked.isNotEmpty()) return false
            picked.add(i)
            used += cost
            return true
        }
        tryAdd(0) // the beginning of a file (imports, title, headers) almost always helps
        val ranked = if (sc.any { it > 0.0 }) order.filter { sc[it] > 0.0 } else chunks.indices.toList()
        for (i in ranked) {
            if (i in picked) continue
            if (!tryAdd(i)) break
        }
        return picked.sorted().map { chunks[it] }
    }

    /**
     * The text to put in the prompt for one attached file. [budgetTokens] is what this file may take.
     * Output is wrapped in `<attached_file ...>` so the model always sees the file name.
     */
    fun render(name: String, content: String, query: String, budgetTokens: Int): Rendered {
        val totalLines = content.split("\n").size
        val language = FileKinds.languageFor(name)
        val header = "<attached_file name=\"$name\" lines=\"$totalLines\" language=\"$language\">"
        val footer = "</attached_file>"

        if (estimateTokens(content) + 40 <= budgetTokens) {
            return Rendered("$header\n$content\n$footer", totalLines, totalLines, false)
        }
        // Keep the initial chunk comfortably below the whole budget so a small context still has room for
        // at least one query-relevant chunk. The model can fetch the rest with the file tools.
        val chunkChars = minOf(900, ((budgetTokens * 2) / 3).coerceIn(180, 900))
        val chunks = chunk(content, maxChars = chunkChars)
        val chosen = select(chunks, query, (budgetTokens - 120).coerceAtLeast(200))
        val sb = StringBuilder()
        sb.append(header).append('\n')
        sb.append("[This file is large ($totalLines lines), so only the parts most relevant to the question are shown. ")
        sb.append("Use read_file with start_line/end_line or search_files to see other parts.]\n")
        var prevEnd = 0
        var shown = 0
        for (c in chosen) {
            if (c.startLine > prevEnd + 1) sb.append("… (lines ${prevEnd + 1}-${c.startLine - 1} not shown) …\n")
            sb.append("--- lines ${c.startLine}-${c.endLine} ---\n").append(c.text).append('\n')
            shown += c.endLine - c.startLine + 1
            prevEnd = c.endLine
        }
        if (prevEnd < totalLines) sb.append("… (lines ${prevEnd + 1}-$totalLines not shown) …\n")
        sb.append(footer)
        return Rendered(sb.toString(), totalLines, minOf(shown, totalLines), true)
    }
}
