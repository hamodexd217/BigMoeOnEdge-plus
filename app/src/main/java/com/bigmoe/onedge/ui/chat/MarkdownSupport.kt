package com.bigmoe.onedge.ui.chat

/** Parsed inline text segment used by [MessageBody] to style basic Markdown without an extra dependency. */
data class InlineMarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: Boolean = false,
    val math: Boolean = false,
    val linkTarget: String? = null
)

/** Minimal inline Markdown parser. HTTP(S) destinations are retained for the message renderer's explicit tap handler. */
object InlineMarkdown {
    fun parse(input: String): List<InlineMarkdownSpan> {
        if (input.isEmpty()) return emptyList()
        val out = ArrayList<InlineMarkdownSpan>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out.add(InlineMarkdownSpan(plain.toString()))
                plain.setLength(0)
            }
        }
        fun addStyled(value: String, bold: Boolean = false, italic: Boolean = false,
                      strike: Boolean = false, code: Boolean = false, link: Boolean = false,
                      math: Boolean = false, linkTarget: String? = null) {
            flush()
            if (value.isNotEmpty()) out.add(InlineMarkdownSpan(value, bold, italic, strike, code, link, math, linkTarget))
        }

        var i = 0
        while (i < input.length) {
            // Inline code: contents are literal, so do not parse delimiters within it.
            if (input[i] == '`') {
                val end = input.indexOf('`', i + 1)
                if (end > i + 1) {
                    addStyled(input.substring(i + 1, end), code = true)
                    i = end + 1
                    continue
                }
            }
            // Markdown links: retain the destination for a real click target. Never launch non-web schemes.
            if (input[i] == '[') {
                val labelEnd = input.indexOf("](", i + 1)
                val destEnd = if (labelEnd >= 0) input.indexOf(')', labelEnd + 2) else -1
                if (labelEnd > i + 1 && destEnd > labelEnd + 2) {
                    val target = input.substring(labelEnd + 2, destEnd).trim()
                    if (target.startsWith("https://", true) || target.startsWith("http://", true)) {
                        addStyled(input.substring(i + 1, labelEnd), link = true, linkTarget = target)
                    } else {
                        plain.append(input.substring(i, destEnd + 1))
                    }
                    i = destEnd + 1
                    continue
                }
            }
            // Inline math.
            if (input[i] == '$' && (i == 0 || input[i - 1] != '\\')) {
                val end = input.indexOf('$', i + 1)
                if (end > i + 1) {
                    addStyled(MathText.toUnicode(input.substring(i + 1, end)), math = true)
                    i = end + 1
                    continue
                }
            }

            val patterns = listOf(
                Triple("**", "**", 1), Triple("__", "__", 2), Triple("~~", "~~", 3),
                Triple("*", "*", 4), Triple("_", "_", 5)
            )
            val match = patterns.firstOrNull { (open, _, _) -> input.startsWith(open, i) }
            if (match != null) {
                val (open, close, kind) = match
                val end = input.indexOf(close, i + open.length)
                if (end > i + open.length) {
                    val value = input.substring(i + open.length, end)
                    when (kind) {
                        1, 2 -> addStyled(value, bold = true)
                        3 -> addStyled(value, strike = true)
                        else -> addStyled(value, italic = true)
                    }
                    i = end + close.length
                    continue
                }
            }
            if (input[i] == '\\' && i + 1 < input.length && input[i + 1] in "\\`*_{}[]()#+-.!$") {
                plain.append(input[i + 1])
                i += 2
            } else {
                plain.append(input[i])
                i++
            }
        }
        flush()
        return out
    }
}

/** Line structures understood by the Markdown paragraph renderer. */
sealed interface MdLine {
    data class Heading(val level: Int, val text: String) : MdLine
    data class Bullet(val level: Int, val marker: String, val text: String) : MdLine
    data class Quote(val depth: Int, val text: String) : MdLine
    data object Rule : MdLine
    data class Plain(val text: String) : MdLine

    companion object {
        private val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$")
        private val bullet = Regex("^(\\s*)([-+*]|[0-9]+[.)])\\s+(.*)$")
        private val quote = Regex("^\\s*((?:>\\s*)+)(.*)$")
        private val rule = Regex("^\\s*(?:(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})$")

        fun classify(line: String): MdLine {
            if (rule.matches(line)) return Rule
            heading.matchEntire(line)?.let { return Heading(it.groupValues[1].length, it.groupValues[2].trim()) }
            bullet.matchEntire(line)?.let { m ->
                val indent = m.groupValues[1].replace('\t', ' ').length
                return Bullet(indent / 2, m.groupValues[2], m.groupValues[3])
            }
            quote.matchEntire(line)?.let { m ->
                val depth = m.groupValues[1].count { it == '>' }.coerceAtLeast(1)
                return Quote(depth, m.groupValues[2])
            }
            return Plain(line)
        }
    }
}

/** Finds a Markdown pipe table beginning at [start]; returns rows and the first line after the table. */
object MarkdownTable {
    fun collect(lines: List<String>, start: Int): Pair<List<List<String>>, Int>? {
        if (start !in lines.indices || start + 1 >= lines.size) return null
        if (!looksLikeRow(lines[start]) || !isSeparator(lines[start + 1])) return null
        val rows = ArrayList<List<String>>()
        rows.add(splitRow(lines[start]))
        var i = start + 2 // Header separator is presentation syntax, not a data row.
        while (i < lines.size && lines[i].isNotBlank() && looksLikeRow(lines[i])) {
            rows.add(splitRow(lines[i]))
            i++
        }
        val width = rows.maxOfOrNull { it.size } ?: return null
        if (width == 0) return null
        return rows.map { row -> row + List((width - row.size).coerceAtLeast(0)) { "" } } to i
    }

    private fun looksLikeRow(line: String): Boolean = line.contains('|') && line.trim().trim('|').isNotBlank()
    private fun isSeparator(line: String): Boolean {
        if (!line.contains('|')) return false
        val cells = splitRow(line)
        return cells.isNotEmpty() && cells.all { it.trim().matches(Regex(":?-{3,}:?")) }
    }

    private fun splitRow(line: String): List<String> {
        val value = line.trim().removePrefix("|").removeSuffix("|")
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var escaped = false
        for (ch in value) {
            if (escaped) {
                cell.append(ch)
                escaped = false
            } else when (ch) {
                '\\' -> escaped = true
                '|' -> { cells.add(cell.toString().trim()); cell.setLength(0) }
                else -> cell.append(ch)
            }
        }
        if (escaped) cell.append('\\')
        cells.add(cell.toString().trim())
        return cells
    }
}

/** Dependency-free best-effort LaTeX-to-Unicode formatter. It is a readable fallback, not a TeX typesetter. */
object MathText {
    private val commands = linkedMapOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\varepsilon" to "ϵ", "\\zeta" to "ζ", "\\eta" to "η",
        "\\theta" to "θ", "\\vartheta" to "ϑ", "\\iota" to "ι", "\\kappa" to "κ",
        "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν", "\\xi" to "ξ",
        "\\pi" to "π", "\\varpi" to "ϖ", "\\rho" to "ρ", "\\sigma" to "σ",
        "\\tau" to "τ", "\\upsilon" to "υ", "\\phi" to "φ", "\\varphi" to "ϕ",
        "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω", "\\Gamma" to "Γ",
        "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ", "\\Xi" to "Ξ",
        "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
        "\\times" to "×", "\\cdot" to "·", "\\pm" to "±", "\\mp" to "∓",
        "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥",
        "\\neq" to "≠", "\\approx" to "≈", "\\equiv" to "≡", "\\infty" to "∞",
        "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫", "\\sqrt" to "√",
        "\\partial" to "∂", "\\nabla" to "∇", "\\therefore" to "∴", "\\because" to "∵",
        "\\rightarrow" to "→", "\\to" to "→", "\\leftarrow" to "←", "\\leftrightarrow" to "↔",
        "\\forall" to "∀", "\\exists" to "∃", "\\in" to "∈", "\\notin" to "∉",
        "\\cup" to "∪", "\\cap" to "∩", "\\subseteq" to "⊆", "\\supseteq" to "⊇",
        "\\ldots" to "…", "\\cdots" to "⋯", "\\degree" to "°", "\\%" to "%"
    )
    private val superscripts = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', '=' to '⁼', 'n' to 'ⁿ', 'i' to 'ⁱ')
    private val subscripts = mapOf('0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋', '=' to '₌', 'a' to 'ₐ', 'e' to 'ₑ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ', 'n' to 'ₙ', 'x' to 'ₓ')

    fun toUnicode(tex: String, multiline: Boolean = false): String {
        var s = tex.replace("\\displaystyle", "").replace("\\textstyle", "")
            .replace("\\left", "").replace("\\right", "").replace("\\,", " ")
            .replace("\\;", " ").replace("\\quad", "   ").replace("\\qquad", "    ")
        // Flatten common brace-based constructs before removing grouping braces.
        val frac = Regex("\\\\(?:d?frac)\\s*\\{([^{}]*)}\\s*\\{([^{}]*)}")
        repeat(3) { s = frac.replace(s) { "(${it.groupValues[1]})/(${it.groupValues[2]})" } }
        val sqrt = Regex("\\\\sqrt\\s*\\{([^{}]*)}")
        s = sqrt.replace(s) { "√(${it.groupValues[1]})" }
        val accent = Regex("\\\\(?:overline|bar|vec|hat|tilde)\\s*\\{([^{}]*)}")
        s = accent.replace(s) { it.groupValues[1] }
        val script = Regex("([_^])(?:\\{([^{}]*)}|([A-Za-z0-9+=-]))")
        s = script.replace(s) { m ->
            val kind = m.groupValues[1]
            val body = if (m.groupValues[2].isNotEmpty()) m.groupValues[2] else m.groupValues[3]
            val map = if (kind == "^") superscripts else subscripts
            if (body.all { map.containsKey(it) }) body.map { map[it] }.joinToString("") else "$kind($body)"
        }
        // Longest commands first so e.g. \rightarrow is not partially replaced.
        for ((command, symbol) in commands.entries.sortedByDescending { it.key.length }) s = s.replace(command, symbol)
        s = s.replace("\\\\", "\n").replace("\\{", "{").replace("\\}", "}")
            .replace("\\,", " ").replace("\\!", "").replace("\\;", " ")
            .replace("\\mathrm", "").replace("\\mathbf", "").replace("\\mathit", "")
            .replace("\\operatorname", "")
        s = s.replace(Regex("\\\\[A-Za-z]+"), "")
            .replace("{", "(").replace("}", ")")
        s = if (multiline) {
            s.replace(Regex("[ \t]+"), " ").replace(Regex(" *\\n *"), "\n").trim()
        } else {
            s.replace(Regex("\\s+"), " ").trim()
        }
        return s
    }
}
