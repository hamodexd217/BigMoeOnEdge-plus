package com.bigmoe.onedge.workspace

/** Extension → language / mime, text-vs-binary sniffing and decoding. Pure Kotlin. */
object FileKinds {
    /** `language` value of an artifact row that stands for a folder the AI made (it has no content of its own). */
    const val FOLDER = "folder"


    private val LANGUAGES: Map<String, String> = mapOf(
        "kt" to "kotlin", "kts" to "kotlin", "java" to "java", "scala" to "scala", "groovy" to "groovy", "gradle" to "groovy",
        "c" to "c", "h" to "c", "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp", "hh" to "cpp", "hxx" to "cpp",
        "cs" to "csharp", "go" to "go", "rs" to "rust", "swift" to "swift", "dart" to "dart",
        "py" to "python", "pyw" to "python", "rb" to "ruby", "php" to "php", "pl" to "perl", "lua" to "lua", "r" to "r",
        "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "jsx" to "javascript",
        "ts" to "typescript", "tsx" to "typescript",
        "html" to "html", "htm" to "html", "xhtml" to "html", "css" to "css", "scss" to "css", "sass" to "css", "less" to "css",
        "json" to "json", "jsonl" to "json", "xml" to "xml", "svg" to "svg", "yaml" to "yaml", "yml" to "yaml", "toml" to "toml",
        "ini" to "ini", "cfg" to "ini", "conf" to "ini", "properties" to "ini", "env" to "ini",
        "sh" to "bash", "bash" to "bash", "zsh" to "bash", "bat" to "bat", "ps1" to "powershell",
        "sql" to "sql", "md" to "markdown", "markdown" to "markdown", "txt" to "text", "log" to "text", "csv" to "csv", "tsv" to "csv",
        "mermaid" to "mermaid", "mmd" to "mermaid", "tex" to "latex", "rst" to "text", "diff" to "diff", "patch" to "diff",
        "cmake" to "cmake", "mk" to "makefile", "proto" to "protobuf", "graphql" to "graphql", "vue" to "html", "svelte" to "html"
    )

    private val NAMES: Map<String, String> = mapOf(
        "dockerfile" to "dockerfile", "makefile" to "makefile", "cmakelists.txt" to "cmake", "gradlew" to "bash",
        ".gitignore" to "text", ".env" to "ini", "readme" to "text", "license" to "text"
    )

    private val IMAGES = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
    private val VIDEOS = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v")

    fun extension(name: String): String = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    fun languageFor(name: String): String {
        val base = name.substringAfterLast('/').lowercase()
        NAMES[base]?.let { return it }
        return LANGUAGES[extension(name)] ?: "text"
    }

    /** True for names we know to be text/code (unknown extensions are decided by content sniffing). */
    fun isKnownTextName(name: String): Boolean {
        val base = name.substringAfterLast('/').lowercase()
        return NAMES.containsKey(base) || LANGUAGES.containsKey(extension(name))
    }

    fun isImageName(name: String) = extension(name) in IMAGES
    fun isVideoName(name: String) = extension(name) in VIDEOS

    fun mimeFor(name: String): String = when (extension(name)) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "text/javascript"
        "json" -> "application/json"
        "xml", "svg" -> if (extension(name) == "svg") "image/svg+xml" else "application/xml"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        else -> if (isKnownTextName(name)) "text/plain" else "application/octet-stream"
    }

    /** NUL bytes, or more than ~30 % control characters in the first 8 KiB, mean binary. UTF-16 BOMs are text. */
    fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (hasUtf16Bom(bytes)) return false
        val n = minOf(bytes.size, 8192)
        var control = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 32 && b != 9 && b != 10 && b != 13 && b != 12 && b != 27) control++
        }
        return control * 100 / n > 30
    }

    private fun hasUtf16Bom(b: ByteArray) =
        b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))

    /** UTF-8 (BOM stripped) or UTF-16 with BOM; invalid bytes become U+FFFD. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** A safe file name from arbitrary input (no separators, no leading dots). */
    fun safeName(name: String, fallback: String = "file"): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^\\p{L}\\p{N}._\\- ()]"), "_").trim().trimStart('.')
        return cleaned.ifEmpty { fallback }
    }
}
