package com.bigmoe.onedge.parser

import com.bigmoe.onedge.workspace.FileKinds

/**
 * One place that knows which code-fence languages exist, what they are called, which file extension they
 * get and how an artifact's language is decided. Pure Kotlin (unit-tested).
 *
 * Detection priority for an artifact ([resolve]):
 *  1. the language tag of the fence (```javascript),
 *  2. the extension of a file name given in the fence info string (```text filename=main.rs),
 *  3. existing artifact metadata (for example the language stored with an artifact row).
 *
 * A language that is not known here never becomes an artifact; the block is still shown (and copyable) as an
 * ordinary code block, so an unknown tag can never crash anything.
 */
object ArtifactLanguages {

    /** The languages that must always be detected as artifacts. */
    val REQUIRED: List<String> = listOf(
        "html", "css", "javascript", "typescript", "json", "xml", "markdown", "python", "kotlin", "java",
        "c", "cpp", "csharp", "rust", "go", "swift", "bash", "sql", "yaml"
    )

    /** Canonical language -> aliases (tags and extensions people and models actually write). */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "html" to listOf("htm", "xhtml"),
        "css" to listOf("scss", "sass", "less"),
        "javascript" to listOf("js", "jsx", "mjs", "cjs", "node", "nodejs", "ecmascript", "es6"),
        "typescript" to listOf("ts", "tsx", "mts", "cts"),
        "json" to listOf("jsonc", "json5", "jsonl", "ndjson"),
        "xml" to listOf("xsd", "xsl", "xslt", "plist"),
        "markdown" to listOf("md", "mkd", "mdx"),
        "python" to listOf("py", "py3", "python3", "pyw", "ipython"),
        "kotlin" to listOf("kt", "kts"),
        "java" to emptyList(),
        "c" to listOf("h"),
        "cpp" to listOf("c++", "cc", "cxx", "hpp", "hh", "hxx", "cplusplus"),
        "csharp" to listOf("c#", "cs", "dotnet"),
        "rust" to listOf("rs"),
        "go" to listOf("golang"),
        "swift" to emptyList(),
        "bash" to listOf("sh", "shell", "zsh", "ksh", "shellscript"),
        "sql" to listOf("mysql", "postgres", "postgresql", "psql", "pgsql", "sqlite", "tsql", "plsql", "mariadb"),
        "yaml" to listOf("yml"),
        "svg" to emptyList(),
        "mermaid" to listOf("mmd"),
        // Not in the required list, but supported before and kept.
        "dockerfile" to listOf("docker"),
        "makefile" to listOf("make", "mk"),
        "cmake" to emptyList(),
        "scala" to emptyList(),
        "groovy" to listOf("gradle"),
        "dart" to emptyList(),
        "ruby" to listOf("rb"),
        "php" to emptyList(),
        "perl" to listOf("pl"),
        "lua" to emptyList(),
        "r" to emptyList(),
        "powershell" to listOf("ps1", "pwsh"),
        "toml" to emptyList(),
        "ini" to listOf("cfg", "conf", "properties"),
        "csv" to listOf("tsv"),
        "latex" to listOf("tex"),
        "diff" to listOf("patch"),
        "protobuf" to listOf("proto"),
        "graphql" to listOf("gql"),
        "text" to listOf("txt", "plain", "plaintext")
    )

    private val LOOKUP: Map<String, String> = buildMap {
        for ((canonical, aliases) in ALIASES) {
            put(canonical, canonical)
            for (a in aliases) put(a, canonical)
        }
    }

    private val EXTENSIONS: Map<String, String> = mapOf(
        "html" to "html", "css" to "css", "javascript" to "js", "typescript" to "ts", "json" to "json", "xml" to "xml",
        "markdown" to "md", "python" to "py", "kotlin" to "kt", "java" to "java", "c" to "c", "cpp" to "cpp",
        "csharp" to "cs", "rust" to "rs", "go" to "go", "swift" to "swift", "bash" to "sh", "sql" to "sql",
        "yaml" to "yml", "svg" to "svg", "mermaid" to "mmd", "cmake" to "cmake", "makefile" to "mk",
        "scala" to "scala", "groovy" to "groovy", "dart" to "dart", "ruby" to "rb", "php" to "php", "perl" to "pl",
        "lua" to "lua", "r" to "r", "powershell" to "ps1", "toml" to "toml", "ini" to "ini", "csv" to "csv",
        "latex" to "tex", "diff" to "diff", "protobuf" to "proto", "graphql" to "graphql", "text" to "txt"
    )

    private val DISPLAY: Map<String, String> = mapOf(
        "html" to "HTML", "css" to "CSS", "javascript" to "JavaScript", "typescript" to "TypeScript", "json" to "JSON",
        "xml" to "XML", "markdown" to "Markdown", "python" to "Python", "kotlin" to "Kotlin", "java" to "Java",
        "c" to "C", "cpp" to "C++", "csharp" to "C#", "rust" to "Rust", "go" to "Go", "swift" to "Swift",
        "bash" to "Shell", "sql" to "SQL", "yaml" to "YAML", "svg" to "SVG", "mermaid" to "Mermaid",
        "dockerfile" to "Dockerfile", "makefile" to "Makefile", "cmake" to "CMake", "php" to "PHP",
        "toml" to "TOML", "ini" to "INI", "csv" to "CSV", "latex" to "LaTeX", "graphql" to "GraphQL",
        "powershell" to "PowerShell", "protobuf" to "Protobuf", "r" to "R"
    )

    /** The canonical language for a fence tag / extension / alias, or null when it is not a known one. */
    fun canonical(tag: String?): String? {
        if (tag == null) return null
        val key = tag.trim().trimStart('.').lowercase()
        if (key.isEmpty()) return null
        return LOOKUP[key]
    }

    /** True when [language] (any alias) is a language this app can show as an artifact. */
    fun isSupported(language: String?): Boolean = canonical(language) != null

    /** Language of a file name by its extension (or well-known name such as Dockerfile); null when unknown. */
    fun fromFileName(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val guessed = FileKinds.languageFor(fileName)
        if (guessed == "text") return null
        return canonical(guessed)
    }

    /**
     * Decides the artifact language by the documented priority. A generic tag such as `text` yields to a
     * more specific file name or metadata; when nothing better exists, `text` itself is returned.
     * Null means "unknown language" (no artifact).
     */
    fun resolve(tag: String?, fileName: String? = null, metadata: String? = null): String? {
        val fromTag = canonical(tag)
        if (fromTag != null && fromTag != "text") return fromTag
        fromName(fileName)?.let { return it }
        val fromMeta = canonical(metadata)
        if (fromMeta != null && fromMeta != "text") return fromMeta
        return fromTag ?: fromMeta
    }

    private fun fromName(fileName: String?): String? = fromFileName(fileName)

    /** HTML, SVG and Mermaid have a live preview; everything else is a code/document artifact (never executed). */
    fun typeFor(language: String): ArtifactType = when (canonical(language)) {
        "html" -> ArtifactType.HTML
        "svg" -> ArtifactType.SVG
        "mermaid" -> ArtifactType.MERMAID
        else -> ArtifactType.CODE
    }

    /** File extension (without dot) for saving an artifact of [language]; `txt` when nothing better is known. */
    fun extensionFor(language: String?): String = EXTENSIONS[canonical(language) ?: ""] ?: "txt"

    /** Human readable name, e.g. "JavaScript". */
    fun displayName(language: String?): String {
        val c = canonical(language) ?: return language?.trim().orEmpty().ifEmpty { "Text" }
        return DISPLAY[c] ?: c.replaceFirstChar { it.uppercase() }
    }

    /** Title for an artifact: its file name when it has one, else "<Language> artifact". */
    fun titleFor(language: String, fileName: String?): String =
        fileName?.takeIf { it.isNotBlank() }?.substringAfterLast('/') ?: "${displayName(language)} artifact"
}
