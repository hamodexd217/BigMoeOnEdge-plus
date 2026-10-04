package com.bigmoe.onedge.ui.chat

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
        "bash" to setOf("if", "then", "else", "fi", "for", "in", "do", "done", "case", "esac", "function", "export", "local"),
        "sql" to common + setOf("select", "from", "where", "and", "or", "join", "left", "right", "inner", "outer", "as"),
        "json" to emptySet(),
        "yaml" to emptySet(),
        "html" to emptySet(),
        "xml" to emptySet(),
        "css" to emptySet()
    )

    fun canonical(language: String): String = when (language.trim().lowercase()) {
        "kt", "kts" -> "kotlin"
        "js", "jsx", "mjs", "cjs" -> "javascript"
        "ts", "tsx" -> "typescript"
        "py", "pyw" -> "python"
        "c++", "cc", "cxx", "hpp", "hh", "hxx" -> "cpp"
        "cs" -> "csharp"
        "sh", "shell", "zsh" -> "bash"
        "yml" -> "yaml"
        "md", "markdown" -> "markdown"
        else -> language.trim().lowercase().ifEmpty { "text" }
    }

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

    fun tokenize(language: String, code: String): List<Token> {
        if (code.isEmpty()) return emptyList()
        val lang = canonical(language)
        val keywords = byLanguage[lang] ?: common
        val htmlLike = lang == "html" || lang == "xml" || lang == "svg"
        val out = ArrayList<Token>()
        var i = 0
        while (i < code.length) {
            if (htmlLike && code[i] == '<') {
                val end = code.indexOf('>', i + 1)
                if (end >= 0) {
                    out.add(Token(code.substring(i, end + 1), Kind.TAG))
                    i = end + 1
                    continue
                }
            }
            if (code.startsWith("//", i) || code.startsWith("/*", i) || (code[i] == '#' && (lang == "python" || lang == "bash" || lang == "yaml" || lang == "text"))) {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                out.add(Token(code.substring(i, end), Kind.COMMENT))
                i = end
                continue
            }
            if (code[i] == '#' && lang == "cpp") {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                out.add(Token(code.substring(i, end), Kind.COMMENT))
                i = end
                continue
            }
            if (code[i] == '\'' || code[i] == '"' || code[i] == '`') {
                val quote = code[i]
                var j = i + 1
                while (j < code.length) {
                    if (code[j] == '\\') { j += 2; continue }
                    if (code[j] == quote) { j++; break }
                    j++
                }
                out.add(Token(code.substring(i, j), Kind.STRING))
                i = j
                continue
            }
            if (code[i].isDigit()) {
                var j = i + 1
                while (j < code.length && (code[j].isDigit() || code[j] == '.' || code[j].lowercaseChar() in 'x'..'f')) j++
                out.add(Token(code.substring(i, j), Kind.NUMBER))
                i = j
                continue
            }
            if (code[i].isLetter() || code[i] == '_') {
                var j = i + 1
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                val kind = when {
                    word.lowercase() in keywords.map { it.lowercase() }.toSet() -> Kind.KEYWORD
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

    private fun isSpecialStart(code: String, index: Int): Boolean {
        val c = code[index]
        return c.isLetterOrDigit() || c == '_' || c == '\'' || c == '"' || c == '`' || c == '<' || c == '#' ||
            code.startsWith("//", index) || code.startsWith("/*", index)
    }
}
