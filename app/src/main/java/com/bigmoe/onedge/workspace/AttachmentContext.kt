package com.bigmoe.onedge.workspace

/** Builds the hidden context text for a message's attachments. Pure Kotlin. */
object AttachmentContext {

    data class TextFile(val name: String, val content: String)
    data class Media(val name: String, val kind: String, val detail: String)

    /** Share of the context window all attached files together may take, and its absolute cap. */
    private const val CONTEXT_SHARE = 0.4
    private const val MAX_TOKENS = 7000
    private const val MIN_PER_FILE = 350

    fun budgetTokens(nCtx: Int): Int = (nCtx * CONTEXT_SHARE).toInt().coerceIn(MIN_PER_FILE, MAX_TOKENS)

    /**
     * Text for [files] (rendered whole or as relevant chunks, see FileContext) and a one-line note per picture /
     * video, so the conversation history still says what was attached when the pixels are gone.
     */
    fun build(files: List<TextFile>, media: List<Media>, query: String, nCtx: Int): String {
        val sb = StringBuilder()
        if (files.isNotEmpty()) {
            val total = budgetTokens(nCtx)
            // Small files keep what they need; the rest is shared among the big ones.
            val needs = files.map { FileContext.estimateTokens(it.content) + 40 }
            val budgets = IntArray(files.size)
            var remaining = total
            var open = files.indices.toMutableList()
            while (open.isNotEmpty()) {
                val fair = (remaining / open.size).coerceAtLeast(MIN_PER_FILE)
                val small = open.filter { needs[it] <= fair }
                if (small.isEmpty()) { open.forEach { budgets[it] = fair }; break }
                for (i in small) { budgets[i] = needs[i]; remaining -= needs[i] }
                open = open.filter { it !in small }.toMutableList()
                if (remaining <= 0) { open.forEach { budgets[it] = MIN_PER_FILE }; break }
            }
            for ((i, f) in files.withIndex()) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(FileContext.render(f.name, f.content, query, budgets[i]).text)
            }
        }
        for (m in media) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("<attached_${m.kind} name=\"${m.name}\"${if (m.detail.isNotEmpty()) " " + m.detail else ""}/>")
        }
        return sb.toString()
    }
}
