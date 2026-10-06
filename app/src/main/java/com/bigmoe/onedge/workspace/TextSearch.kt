package com.bigmoe.onedge.workspace

import java.util.regex.PatternSyntaxException

/** Grep over workspace text files. Pure Kotlin + java.io. */
object TextSearch {

    data class Match(val path: String, val line: Int, val text: String, val before: List<String>, val after: List<String>)

    data class Result(val matches: List<Match>, val filesSearched: Int, val filesWithMatches: Int, val truncated: Boolean)

    private const val MAX_FILE_BYTES = 1_000_000

    /** Languages that are code (used by search_code to skip prose, data and logs). */
    fun isCodeFile(name: String): Boolean {
        if (!FileKinds.isKnownTextName(name)) return false
        return FileKinds.languageFor(name) !in setOf("text", "markdown", "csv", "latex")
    }

    fun search(
        workspace: Workspace,
        query: String,
        path: String = "",
        regex: Boolean = false,
        caseSensitive: Boolean = false,
        codeOnly: Boolean = false,
        extensions: Set<String> = emptySet(),
        contextLines: Int = 0,
        maxResults: Int = 40
    ): Result {
        if (query.isEmpty()) throw IllegalArgumentException("query is empty")
        val pattern = try {
            val flags = if (caseSensitive) 0 else java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE
            if (regex) java.util.regex.Pattern.compile(query, flags)
            else java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(query), flags)
        } catch (e: PatternSyntaxException) {
            throw IllegalArgumentException("invalid regular expression: ${e.description}")
        }
        val files = workspace.walkFiles(path)
        val matches = ArrayList<Match>()
        var searched = 0
        var withMatches = 0
        var truncated = false
        for (f in files) {
            val rel = workspace.relative(f)
            if (codeOnly && !isCodeFile(f.name)) continue
            if (extensions.isNotEmpty() && FileKinds.extension(f.name) !in extensions) continue
            if (f.length() > MAX_FILE_BYTES) continue
            val bytes = try { f.readBytes() } catch (_: Exception) { continue }
            if (FileKinds.looksBinary(bytes)) continue
            searched++
            val lines = FileKinds.decode(bytes).split("\n")
            var fileHit = false
            for (i in lines.indices) {
                if (!pattern.matcher(lines[i]).find()) continue
                if (matches.size >= maxResults) { truncated = true; break }
                fileHit = true
                val before = if (contextLines > 0) lines.subList(maxOf(0, i - contextLines), i) else emptyList()
                val after = if (contextLines > 0) lines.subList(i + 1, minOf(lines.size, i + 1 + contextLines)) else emptyList()
                matches.add(Match(rel, i + 1, lines[i], before, after))
            }
            if (fileHit) withMatches++
            if (truncated) break
        }
        return Result(matches, searched, withMatches, truncated)
    }

    fun format(r: Result, query: String): String {
        if (r.matches.isEmpty()) return "No matches for \"$query\" (searched ${r.filesSearched} files)."
        val b = StringBuilder()
        b.append("${r.matches.size}${if (r.truncated) "+" else ""} matches in ${r.filesWithMatches} files:\n")
        for (m in r.matches) {
            for ((k, l) in m.before.withIndex()) b.append("${m.path}-${m.line - m.before.size + k}- ${l.take(200)}\n")
            b.append("${m.path}:${m.line}: ${m.text.trim().take(200)}\n")
            for ((k, l) in m.after.withIndex()) b.append("${m.path}-${m.line + 1 + k}- ${l.take(200)}\n")
        }
        if (r.truncated) b.append("[more matches not shown; narrow the query or the path]\n")
        return b.toString().trimEnd()
    }
}
