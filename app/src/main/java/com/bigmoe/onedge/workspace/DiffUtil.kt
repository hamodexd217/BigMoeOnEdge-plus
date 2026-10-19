package com.bigmoe.onedge.workspace

/** Line diff (LCS) and unified-diff text for the edit-confirmation dialog. Pure Kotlin. */
object DiffUtil {

    enum class Op { KEEP, ADD, DEL }

    data class Line(val op: Op, val text: String)

    /** Above this many (old x new) line pairs the LCS table is skipped and the whole block is replaced. */
    private const val MAX_CELLS = 4_000_000

    fun splitLines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split("\n")

    fun diffLines(old: List<String>, new: List<String>): List<Line> {
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix &&
            old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) suffix++
        val a = old.subList(prefix, old.size - suffix)
        val b = new.subList(prefix, new.size - suffix)

        val out = ArrayList<Line>()
        for (i in 0 until prefix) out.add(Line(Op.KEEP, old[i]))
        if (a.isEmpty()) {
            b.forEach { out.add(Line(Op.ADD, it)) }
        } else if (b.isEmpty()) {
            a.forEach { out.add(Line(Op.DEL, it)) }
        } else if (a.size.toLong() * b.size.toLong() > MAX_CELLS) {
            a.forEach { out.add(Line(Op.DEL, it)) }
            b.forEach { out.add(Line(Op.ADD, it)) }
        } else {
            val n = a.size
            val m = b.size
            val w = m + 1
            val t = IntArray((n + 1) * w) // t[i*w + j] = LCS length of a[i..] and b[j..]
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    t[i * w + j] = if (a[i] == b[j]) t[(i + 1) * w + j + 1] + 1
                    else maxOf(t[(i + 1) * w + j], t[i * w + j + 1])
                }
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    a[i] == b[j] -> { out.add(Line(Op.KEEP, a[i])); i++; j++ }
                    t[(i + 1) * w + j] >= t[i * w + j + 1] -> { out.add(Line(Op.DEL, a[i])); i++ }
                    else -> { out.add(Line(Op.ADD, b[j])); j++ }
                }
            }
            while (i < n) { out.add(Line(Op.DEL, a[i])); i++ }
            while (j < m) { out.add(Line(Op.ADD, b[j])); j++ }
        }
        for (k in old.size - suffix until old.size) out.add(Line(Op.KEEP, old[k]))
        return out
    }

    data class Stats(val added: Int, val removed: Int)

    fun stats(lines: List<Line>) = Stats(lines.count { it.op == Op.ADD }, lines.count { it.op == Op.DEL })

    /**
     * Unified diff with [context] lines around each change; hunks that touch are merged. Returns "" when the
     * texts are equal. Each output line starts with ' ', '+' or '-' (hunk headers start with "@@").
     */
    fun unified(oldText: String, newText: String, context: Int = 3): String {
        val lines = diffLines(splitLines(oldText), splitLines(newText))
        val changes = lines.indices.filter { lines[it].op != Op.KEEP }
        if (changes.isEmpty()) return ""

        // group change indices into hunks
        val ranges = ArrayList<IntRange>()
        var start = maxOf(0, changes.first() - context)
        var end = minOf(lines.size - 1, changes.first() + context)
        for (idx in changes.drop(1)) {
            val s = maxOf(0, idx - context)
            val e = minOf(lines.size - 1, idx + context)
            if (s <= end + 1) end = maxOf(end, e) else { ranges.add(start..end); start = s; end = e }
        }
        ranges.add(start..end)

        // running old/new line numbers at each index
        val oldNo = IntArray(lines.size + 1)
        val newNo = IntArray(lines.size + 1)
        var o = 1
        var n = 1
        for (k in lines.indices) {
            oldNo[k] = o
            newNo[k] = n
            when (lines[k].op) {
                Op.KEEP -> { o++; n++ }
                Op.DEL -> o++
                Op.ADD -> n++
            }
        }
        val sb = StringBuilder()
        for (r in ranges) {
            val slice = lines.subList(r.first, r.last + 1)
            val oc = slice.count { it.op != Op.ADD }
            val nc = slice.count { it.op != Op.DEL }
            sb.append("@@ -${oldNo[r.first]},$oc +${newNo[r.first]},$nc @@\n")
            for (l in slice) {
                sb.append(when (l.op) { Op.KEEP -> ' '; Op.ADD -> '+'; Op.DEL -> '-' }).append(l.text).append('\n')
            }
        }
        return sb.toString().trimEnd('\n')
    }
}
