package com.bigmoe.onedge.ui.chat

import com.bigmoe.onedge.parser.ArtifactLanguages

/** Tokenizer/detector for chat code blocks. It intentionally stays small and pure for JVM tests. */
object SyntaxHighlighter {
    enum class Kind { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, TAG, FUNCTION }
    data class Token(val text: String, val kind: Kind)

    private val common = setOf(
        "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return", "class", "interface",
        "object", "fun", "val", "var", "public", "private", "protected", "internal", "static", "final", "const",
        "new", "this", "super", "extends", "implements", "import", "package", "from", "as", "in", "is", "when",
        "try", "catch", "finally", "throw", "throws", "async", "await", "function", "def", "lambda", "yield", "with",
        "using", "namespace", "struct", "enum", "template", "typename", "include", "nullptr", "true", "false", "null",
        "select", "from", "where", "join", "group", "order", "by", "insert", "update", "delete", "create", "alter", "table"
    )
    private val byLanguage = mapOf(
        "kotlin" to common + setOf("fun", "data", "sealed", "companion", "suspend", "CoroutineScope", "override"),
        "java" to common + setOf("void", "boolean", "extends", "implements", "throws", "override"),
        "python" to common + setOf("def", "elif", "lambda", "None", "True", "False", "async", "await"),
        "javascript" to common + setOf("let", "const", "var", "function", "export", "default", "undefined"),
        "typescript" to common + setOf("type", "interface", "public", "private", "readonly", "implements"),
        "c" to common + setOf("int", "char", "void", "long", "short", "unsigned", "sizeof", "typedef"),
        "cpp" to common + setOf("int", "char", "void", "auto", "constexpr", "std", "template", "typename", "nullptr"),
        "csharp" to common + setOf("using", "namespace", "public", "private", "async", "await", "var"),
        "sql" to common + setOf("select", "from", "where", "and", "or", "join", "left", "right", "inner", "outer", "as"),
        "rust" to common + setOf("fn", "let", "mut", "pub", "impl", "trait", "match", "use", "mod", "crate", "self", "Self", "struct", "enum", "async", "await", "move"),
        "go" to common + setOf("package", "func", "defer", "go", "chan", "range", "interface", "map", "type", "select", "fallthrough"),
        "swift" to common + setOf("func", "let", "var", "guard", "protocol", "extension", "struct", "enum", "import", "throws", "try", "self", "Self"),
        "bash" to setOf("if", "then", "else", "fi", "for", "in", "do", "done", "case", "esac", "function", "export", "local", "while", "until", "echo"),
        "json" to emptySet(),
        "yaml" to emptySet(),
        "html" to emptySet(),
        "xml" to emptySet(),
        "markdown" to setOf("heading", "blockquote", "link", "image", "code", "table"),
        "css" to setOf("display", "position", "relative", "absolute", "fixed", "flex", "grid", "color", "background", "margin", "padding", "width", "height", "font", "border", "transform", "transition", "animation", "media", "important")
    )

    /** Canonical language name for any alias ("js", "C#", "golang" ...); unknown tags are returned lower-cased. */
    fun canonical(language: String): String =
        ArtifactLanguages.canonical(language) ?: language.trim().lowercase().ifEmpty { "text" }

    /** Lower-cased keyword sets, built once instead of once per word. */
    private val lowerKeywords: Map<String, Set<String>> =
        byLanguage.mapValues { (_, words) -> words.map { it.lowercase() }.toSet() }
    private val lowerCommon: Set<String> = common.map { it.lowercase() }.toSet()

    /** Languages that are prose or data rather than code: shown without keyword/string/comment colouring. */
    private val unstyled = setOf("text", "markdown", "csv", "diff", "latex")

    fun detectLanguage(code: String): String {
        val s = code.trim()
        if (Regex("<(!DOCTYPE|html|body|div|svg)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) return "html"
        if (s.contains("->") && (s.contains("package ") || s.contains("fun "))) return "kotlin"
        if (Regex("(^|\\n)\\s*(def|class)\\s+\\w+.*:").containsMatchIn(s)) return "python"
        if (Regex("\\b(console\\.log|const|let|function)\\b").containsMatchIn(s)) return "javascript"
        if (Regex("\\b(SELECT|INSERT|UPDATE|DELETE|CREATE TABLE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) return "sql"
        if (s.contains("{") && s.contains("\"") && Regex("\"[^\"]+\"\\s*:").containsMatchIn(s)) return "json"
        if (Regex("^\\s*[A-Za-z_][\\w-]*:\\s*[^/]*$", RegexOption.MULTILINE).containsMatchIn(s)) return "yaml"
        if (Regex("#include\\s*[<\"]").containsMatchIn(s)) return "cpp"
        if (Regex("\\b(public|private|protected)\\s+(class|interface)\\b").containsMatchIn(s)) return "java"
        return "text"
    }

    /**
     * Splits [code] into coloured tokens. The tokens always concatenate back to exactly [code]. Never throws:
     * half-received code while streaming (an unterminated string, a trailing backslash, ...) is normal input,
     * and a failure here would otherwise take the whole chat screen down.
     */
    fun tokenize(language: String, code: String): List<Token> {
        if (code.isEmpty()) return emptyList()
        return try {
            tokenizeUnsafe(canonical(language), code)
        } catch (e: Exception) {
            listOf(Token(code, Kind.PLAIN))
        }
    }

    private fun tokenizeUnsafe(lang: String, code: String): List<Token> {
        if (lang in unstyled) return listOf(Token(code, Kind.PLAIN))
        val keywords = lowerKeywords[lang] ?: lowerCommon
        val htmlLike = lang == "html" || lang == "xml" || lang == "svg"
        val hashComments = lang in hashCommentLanguages
        val out = ArrayList<Token>()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            if (htmlLike && c == '<') {
                val end = code.indexOf('>', i + 1)
                if (end >= 0) {
                    out.add(Token(code.substring(i, end + 1), Kind.TAG))
                    i = end + 1
                    continue
                }
            }
            if (code.startsWith("/*", i)) {
                val close = code.indexOf("*/", i + 2)
                val end = if (close < 0) code.length else close + 2
                out.add(Token(code.substring(i, end), Kind.COMMENT))
                i = end
                continue
            }
            if (code.startsWith("//", i) || (c == '#' && (hashComments || lang == "cpp" || lang == "c")) ||
                (lang == "sql" && code.startsWith("--", i))
            ) {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                out.add(Token(code.substring(i, end), Kind.COMMENT))
                i = end
                continue
            }
            if (c == '\'' || c == '"' || c == '`') {
                var j = i + 1
                while (j < code.length) {
                    if (code[j] == '\\') { j += 2; continue }
                    if (code[j] == c) { j++; break }
                    j++
                }
                // A backslash as the very last character (half-received escape) pushes j past the end.
                j = minOf(j, code.length)
                out.add(Token(code.substring(i, j), Kind.STRING))
                i = j
                continue
            }
            if (c.isDigit()) {
                var j = i + 1
                while (j < code.length && (code[j].isDigit() || code[j] == '.' || code[j] == '_' || isHexLetter(code[j]) ||
                        ((code[j] == 'x' || code[j] == 'X') && j == i + 1))
                ) j++
                out.add(Token(code.substring(i, j), Kind.NUMBER))
                i = j
                continue
            }
            if (c.isLetter() || c == '_') {
                var j = i + 1
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                val kind = when {
                    word.lowercase() in keywords -> Kind.KEYWORD
                    code.substring(j).dropWhile { it.isWhitespace() }.startsWith("(") -> Kind.FUNCTION
                    else -> Kind.PLAIN
                }
                out.add(Token(word, kind))
                i = j
                continue
            }
            var j = i + 1
            while (j < code.length && !isSpecialStart(code, j)) j++
            out.add(Token(code.substring(i, j), Kind.PLAIN))
            i = j
        }
        return out
    }

    private val hashCommentLanguages = setOf(
        "python", "bash", "yaml", "ruby", "perl", "toml", "ini", "powershell", "makefile", "dockerfile", "cmake", "r"
    )

    private fun isHexLetter(c: Char) = c in 'a'..'f' || c in 'A'..'F'

    private fun isSpecialStart(code: String, index: Int): Boolean {
        val c = code[index]
        return c.isLetterOrDigit() || c == '_' || c == '\'' || c == '"' || c == '`' || c == '<' || c == '#' ||
            code.startsWith("//", index) || code.startsWith("/*", index) || code.startsWith("--", index)
    }
}
