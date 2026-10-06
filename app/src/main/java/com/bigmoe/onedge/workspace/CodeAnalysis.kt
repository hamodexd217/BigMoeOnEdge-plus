package com.bigmoe.onedge.workspace

/** Heuristic static summary of a source file (regexes, not a parser; says so in its output). */
object CodeAnalysis {

    private val DEFS: Map<String, List<Pair<String, Regex>>> = mapOf(
        "kotlin" to listOf(
            "class" to Regex("^\\s*(?:(?:public|private|internal|protected|data|sealed|enum|abstract|open|inner|annotation|value)\\s+)*(?:class|interface|object)\\s+([A-Za-z_]\\w*)"),
            "function" to Regex("^\\s*(?:(?:public|private|internal|protected|override|suspend|inline|open|abstract|operator|infix)\\s+)*fun\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?]+\\.)?([A-Za-z_]\\w*)")
        ),
        "java" to listOf(
            "class" to Regex("^\\s*(?:(?:public|private|protected|static|final|abstract)\\s+)*(?:class|interface|enum|record)\\s+([A-Za-z_]\\w*)"),
            "function" to Regex("^\\s*(?:(?:public|private|protected|static|final|abstract|synchronized)\\s+)+[\\w<>\\[\\],.? ]+\\s+([a-z_]\\w*)\\s*\\([^;]*$")
        ),
        "python" to listOf(
            "class" to Regex("^\\s*class\\s+([A-Za-z_]\\w*)"),
            "function" to Regex("^\\s*(?:async\\s+)?def\\s+([A-Za-z_]\\w*)")
        ),
        "javascript" to listOf(
            "class" to Regex("^\\s*(?:export\\s+)?(?:default\\s+)?class\\s+([A-Za-z_$][\\w$]*)"),
            "function" to Regex("^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s*\\*?\\s*([A-Za-z_$][\\w$]*)|^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>")
        ),
        "go" to listOf(
            "type" to Regex("^\\s*type\\s+([A-Za-z_]\\w*)\\s+(?:struct|interface)"),
            "function" to Regex("^\\s*func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_]\\w*)")
        ),
        "rust" to listOf(
            "type" to Regex("^\\s*(?:pub\\s+)?(?:struct|enum|trait|impl)\\s+([A-Za-z_]\\w*)"),
            "function" to Regex("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?fn\\s+([A-Za-z_]\\w*)")
        ),
        "cpp" to listOf(
            "class" to Regex("^\\s*(?:class|struct|enum(?:\\s+class)?)\\s+([A-Za-z_]\\w*)"),
            "function" to Regex("^[A-Za-z_][\\w:<>*&,\\s]*[\\s*&]([A-Za-z_~][\\w:]*)\\s*\\([^;]*\\)\\s*(?:const\\s*)?(?:noexcept\\s*)?\\{?\\s*$")
        )
    )

    private val ALIAS = mapOf("typescript" to "javascript", "c" to "cpp", "csharp" to "java", "scala" to "kotlin", "dart" to "java", "swift" to "kotlin")

    private val LINE_COMMENT = mapOf(
        "python" to "#", "bash" to "#", "ruby" to "#", "yaml" to "#", "toml" to "#", "ini" to "#", "makefile" to "#", "cmake" to "#",
        "sql" to "--", "lua" to "--", "html" to "<!--"
    )

    fun analyze(name: String, text: String): String {
        val lang = FileKinds.languageFor(name)
        val lines = text.split("\n")
        val lineComment = LINE_COMMENT[lang] ?: "//"
        var blank = 0
        var comment = 0
        var inBlock = false
        var longest = 0
        var longestAt = 0
        var tabs = 0
        var spaces = 0
        val todo = ArrayList<String>()
        for ((i, raw) in lines.withIndex()) {
            val l = raw.trim()
            if (raw.length > longest) { longest = raw.length; longestAt = i + 1 }
            if (raw.startsWith("\t")) tabs++ else if (raw.startsWith("  ")) spaces++
            if (Regex("\\b(TODO|FIXME|HACK|XXX)\\b").containsMatchIn(raw)) todo.add("${i + 1}: ${l.take(100)}")
            when {
                l.isEmpty() -> blank++
                inBlock -> { comment++; if (l.contains("*/")) inBlock = false }
                l.startsWith("/*") && lang !in LINE_COMMENT -> { comment++; if (!l.contains("*/")) inBlock = true }
                l.startsWith(lineComment) -> comment++
            }
        }
        val code = lines.size - blank - comment

        val sb = StringBuilder()
        sb.append("Analysis of $name ($lang) — heuristic, based on pattern matching, not a full parser\n")
        sb.append("Lines: ${lines.size} (code ~$code, comments ~$comment, blank $blank); longest line $longest chars at line $longestAt\n")
        sb.append("Indentation: ${if (tabs > spaces) "tabs" else "spaces"}${if (tabs > 0 && spaces > 0) " (mixed!)" else ""}\n")

        val defs = DEFS[ALIAS[lang] ?: lang]
        if (defs != null) {
            for ((kind, re) in defs) {
                val found = ArrayList<String>()
                for ((i, l) in lines.withIndex()) {
                    val m = re.find(l) ?: continue
                    val n = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
                    found.add("$n@${i + 1}")
                }
                if (found.isNotEmpty()) {
                    val label = when (kind) {
                        "class" -> "Classes"
                        "function" -> "Functions"
                        else -> kind.replaceFirstChar { it.uppercase() } + "s"
                    }
                    sb.append("$label (${found.size}): ${found.take(40).joinToString(", ")}")
                    if (found.size > 40) sb.append(", …")
                    sb.append('\n')
                }
            }
        }
        val imports = lines.count { Regex("^\\s*(import|from\\s+\\S+\\s+import|#include|using|require\\(|use\\s)").containsMatchIn(it) }
        if (imports > 0) sb.append("Imports/includes: $imports\n")
        if (todo.isNotEmpty()) sb.append("TODO/FIXME (${todo.size}): ${todo.take(8).joinToString(" | ")}\n")

        if (lang in setOf("kotlin", "java", "javascript", "typescript", "cpp", "c", "csharp", "go", "rust", "json", "css", "swift", "dart", "scala")) {
            val bal = braceBalance(text)
            if (bal != null) sb.append("Warning: $bal\n")
        }
        return sb.toString().trimEnd()
    }

    /** Null when (), [] and {} are balanced (strings and comments ignored); else a description. */
    fun braceBalance(text: String): String? {
        val stack = ArrayDeque<Pair<Char, Int>>()
        var line = 1
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '\n' -> line++
                c == '/' && i + 1 < n && text[i + 1] == '/' -> { while (i < n && text[i] != '\n') i++; continue }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) { if (text[i] == '\n') line++; i++ }
                    i += 2; continue
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val q = c
                    i++
                    while (i < n && text[i] != q) { if (text[i] == '\\') i++; if (i < n && text[i] == '\n') { line++; if (q != '`') break }; i++ }
                }
                c == '(' || c == '[' || c == '{' -> stack.add(c to line)
                c == ')' || c == ']' || c == '}' -> {
                    val want = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
                    if (stack.isEmpty() || stack.last().first != want) return "unmatched '$c' at line $line"
                    stack.removeLast()
                }
            }
            i++
        }
        return if (stack.isEmpty()) null else "unclosed '${stack.last().first}' opened at line ${stack.last().second}"
    }
}
