package com.bigmoe.onedge.workspace

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ArchiveException(message: String) : IOException(message)

/**
 * Turns an attached archive or office document into prompt text: ZIP (and JAR), TAR, GZIP / TAR.GZ, and the
 * ZIP based document formats DOCX, XLSX, PPTX, ODT/ODS/ODP and EPUB. Nothing is ever written to disk, so a
 * hostile archive cannot escape a folder; entry names are only displayed, and names that try to (`../x`) are
 * listed as skipped. Every read is bounded (per entry, in total, and in entry count), counting the bytes that are
 * really decompressed instead of trusting the sizes an archive declares, so a zip bomb cannot exhaust memory.
 *
 * Pure JVM (java.util.zip), unit-tested.
 */
object ArchiveText {

    data class Result(val text: String, val format: String, val entryCount: Int, val textFileCount: Int)

    const val MAX_ENTRIES = 5000
    const val MAX_ENTRY_BYTES = 1_000_000
    const val MAX_TOTAL_BYTES = 24L * 1024 * 1024
    const val MAX_STREAM_BYTES = 96L * 1024 * 1024
    const val MAX_OUTPUT_CHARS = 1_500_000
    private const val MAX_TEXT_FILES = 400
    private const val LISTING_LINES = 300

    private val ARCHIVE_EXTENSIONS = setOf(
        "zip", "jar", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "gz", "tgz", "tar"
    )

    /** Entries that are never worth reading as text. */
    private val BINARY_EXTENSIONS = setOf(
        "zip", "gz", "tgz", "tar", "xz", "bz2", "7z", "rar", "jar", "war", "apk", "aab", "dex", "class", "so", "dll", "exe",
        "bin", "o", "a", "pyc", "pdf", "mp3", "wav", "ogg", "flac", "aac", "m4a", "ttf", "otf", "woff", "woff2", "ico",
        "db", "sqlite", "sqlite3", "iso", "dmg", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "psd", "gguf", "onnx"
    )

    /** True when the file name says this is an archive / zipped document we can open. */
    fun isArchiveName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".tar.gz") || FileKinds.extension(lower) in ARCHIVE_EXTENSIONS
    }

    fun isZipMagic(h: ByteArray): Boolean =
        h.size >= 4 && h[0] == 0x50.toByte() && h[1] == 0x4B.toByte() &&
            ((h[2] == 3.toByte() && h[3] == 4.toByte()) || (h[2] == 5.toByte() && h[3] == 6.toByte()))

    fun isGzipMagic(h: ByteArray): Boolean = h.size >= 2 && h[0] == 0x1F.toByte() && h[1] == 0x8B.toByte()

    fun isTarMagic(h: ByteArray): Boolean =
        h.size >= 262 && h[257] == 'u'.code.toByte() && h[258] == 's'.code.toByte() && h[259] == 't'.code.toByte() &&
            h[260] == 'a'.code.toByte() && h[261] == 'r'.code.toByte()

    /** True when the first bytes look like a ZIP, GZIP or TAR container. */
    fun looksLikeArchive(head: ByteArray): Boolean = isZipMagic(head) || isGzipMagic(head) || isTarMagic(head)

    /** Readable text of the archive or document in [file]. Throws [ArchiveException] when it cannot be opened. */
    fun extract(file: File, name: String): Result {
        val head = file.inputStream().use { readHead(it, 512) }
        return try {
            when {
                isZipMagic(head) -> fromZip(file, name)
                isGzipMagic(head) -> fromGzip(file, name)
                isTarMagic(head) || name.lowercase().endsWith(".tar") -> fromTarFile(file, name)
                else -> throw ArchiveException("$name is not a valid ZIP, TAR or GZIP file")
            }
        } catch (e: ArchiveException) {
            throw e
        } catch (e: Exception) {
            throw ArchiveException("Could not read $name: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // shared collection of entries
    // ---------------------------------------------------------------------------------------------------------

    private class Collector {
        val listing = ArrayList<String>()
        val sections = ArrayList<Pair<String, String>>()
        var entryCount = 0
        var textFiles = 0
        var totalRead = 0L
        var note: String? = null
    }

    /** `null` when the name is unusable or tries to leave its folder; display-only, nothing is written anywhere. */
    private fun cleanName(raw: String): String? {
        val n = raw.replace('\\', '/').trimStart('/').filter { it.code >= 32 }
        if (n.isEmpty()) return null
        if (n.split('/').any { it == ".." }) return null
        return n
    }

    private fun isNoise(name: String): Boolean =
        name.startsWith("__MACOSX/") || name.endsWith(".DS_Store") || name.startsWith(".git/") || name.contains("/.git/") ||
            name.startsWith("node_modules/") || name.contains("/node_modules/")

    private fun isBinaryName(name: String): Boolean {
        val ext = FileKinds.extension(name)
        return FileKinds.isImageName(name) || FileKinds.isVideoName(name) || ext in BINARY_EXTENSIONS
    }

    /**
     * Records one file entry. [read] returns up to the given number of bytes of the entry and is called at most
     * once, and only when the entry is worth reading.
     */
    private fun handleFile(c: Collector, rawName: String, size: Long, read: (Int) -> ByteArray) {
        c.entryCount++
        val name = cleanName(rawName)
        if (name == null) {
            c.listing.add("$rawName (skipped: unsafe path)")
            return
        }
        if (isNoise(name)) {
            c.entryCount--
            return
        }
        val sizeLabel = formatSize(size)
        if (isBinaryName(name)) {
            c.listing.add("$name (binary, $sizeLabel, not shown)")
            return
        }
        if (c.textFiles >= MAX_TEXT_FILES || c.totalRead >= MAX_TOTAL_BYTES) {
            c.listing.add("$name ($sizeLabel, not read: size limit reached)")
            return
        }
        val bytes = try {
            read(MAX_ENTRY_BYTES)
        } catch (e: StreamLimit) {
            throw e
        } catch (e: Exception) {
            c.listing.add("$name (unreadable: ${e.message ?: e.javaClass.simpleName})")
            return
        }
        c.totalRead += bytes.size
        if (FileKinds.looksBinary(bytes)) {
            c.listing.add("$name (binary, $sizeLabel, not shown)")
            return
        }
        val cut = bytes.size >= MAX_ENTRY_BYTES && (size < 0 || size > bytes.size)
        val text = FileKinds.decode(bytes) + if (cut) "\n[… the rest of this file is not shown]" else ""
        c.sections.add(name to text)
        c.listing.add(if (cut) "$name ($sizeLabel, shown in part)" else "$name ($sizeLabel)")
        c.textFiles++
    }

    private fun assemble(archiveName: String, format: String, c: Collector, moreEntries: Boolean): Result {
        // README first, then the rest by path, so the part that survives a tight context budget is the best part.
        val ordered = c.sections.sortedWith(
            compareBy<Pair<String, String>>({ !it.first.substringAfterLast('/').lowercase().startsWith("readme") }, { it.first.lowercase() })
        )
        val sb = StringBuilder()
        sb.append("Archive: ").append(archiveName).append(" (").append(format).append(", ").append(c.entryCount)
            .append(if (c.entryCount == 1) " entry, " else " entries, ").append(c.textFiles).append(" readable text file")
            .append(if (c.textFiles == 1) ")" else "s)").append('\n')
        sb.append("Contents:\n")
        for (line in c.listing.sorted().take(LISTING_LINES)) sb.append("  ").append(line).append('\n')
        if (c.listing.size > LISTING_LINES) sb.append("  … and ").append(c.listing.size - LISTING_LINES).append(" more entries\n")
        if (moreEntries) sb.append("  … the archive has more than ").append(MAX_ENTRIES).append(" entries; the rest are not listed\n")
        c.note?.let { sb.append(it).append('\n') }
        for ((path, text) in ordered) {
            sb.append("\n=== ").append(path).append(" ===\n").append(text.trimEnd('\n')).append('\n')
            if (sb.length > MAX_OUTPUT_CHARS) break
        }
        var out = sb.toString()
        if (out.length > MAX_OUTPUT_CHARS) out = out.take(MAX_OUTPUT_CHARS) + "\n[… cut: the archive text is longer than the limit]"
        return Result(out, format, c.entryCount, c.textFiles)
    }

    // ---------------------------------------------------------------------------------------------------------
    // ZIP and the document formats built on it
    // ---------------------------------------------------------------------------------------------------------

    private enum class Office(val label: String) { DOCX("DOCX document"), PPTX("PPTX presentation"), XLSX("XLSX spreadsheet"), ODF("OpenDocument file"), EPUB("EPUB book") }

    private fun detectOffice(names: Set<String>): Office? = when {
        "word/document.xml" in names -> Office.DOCX
        names.any { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") } -> Office.PPTX
        "xl/workbook.xml" in names -> Office.XLSX
        "content.xml" in names && "mimetype" in names -> Office.ODF
        "META-INF/container.xml" in names && names.any { it.endsWith(".opf") } -> Office.EPUB
        else -> null
    }

    private fun fromZip(file: File, name: String): Result {
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw ArchiveException("$name is not a readable ZIP file: ${e.message ?: e.javaClass.simpleName}")
        }
        zip.use { z ->
            val entries = ArrayList<ZipEntry>()
            val en = z.entries()
            while (en.hasMoreElements() && entries.size < MAX_ENTRIES) entries.add(en.nextElement())
            val more = en.hasMoreElements()
            val names = entries.map { it.name }.toSet()
            val office = detectOffice(names)
            if (office != null && office != Office.EPUB) {
                val text = when (office) {
                    Office.DOCX -> docxText(z)
                    Office.PPTX -> pptxText(z, names)
                    Office.XLSX -> xlsxText(z, names)
                    else -> odfText(z)
                }
                if (text.isNotBlank()) {
                    val body = if (text.length > MAX_OUTPUT_CHARS) text.take(MAX_OUTPUT_CHARS) + "\n[… cut: the document text is longer than the limit]" else text
                    val header = "Document: $name (${office.label})\n\n"
                    return Result(header + body, office.label, 1, 1)
                }
                // No text found (a scanned or empty document): fall through and list the parts instead.
            }
            val c = Collector()
            try {
                for (entry in entries) {
                    if (entry.isDirectory) continue
                    handleFile(c, entry.name, entry.size) { limit -> z.getInputStream(entry).use { readBounded(it, limit.toLong(), limit) } }
                }
            } catch (e: StreamLimit) {
                c.note = "Reading stopped: the archive is larger than the read limit."
            }
            if (office == Office.EPUB) {
                // Book chapters are XHTML: give the model the words, not the markup.
                val converted = c.sections.map { (p, t) ->
                    val ext = FileKinds.extension(p)
                    if (ext == "xhtml" || ext == "html" || ext == "htm") p to HtmlText.extract(t).text else p to t
                }
                c.sections.clear()
                c.sections.addAll(converted)
            }
            return assemble(name, office?.label ?: "ZIP", c, more)
        }
    }

    private fun readEntryText(z: ZipFile, entryName: String): String? {
        val entry = z.getEntry(entryName) ?: return null
        val bytes = z.getInputStream(entry).use { readBounded(it, MAX_ENTRY_BYTES.toLong() * 4, MAX_ENTRY_BYTES * 4) }
        return String(bytes, Charsets.UTF_8)
    }

    private val ANY_TAG = Regex("<[^>]+>")
    private val DOT_ALL = RegexOption.DOT_MATCHES_ALL

    private fun tidy(s: String): String =
        s.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()

    private fun docxText(z: ZipFile): String {
        var xml = readEntryText(z, "word/document.xml") ?: return ""
        xml = Regex("<w:(instrText|delText)\\b[^>]*>.*?</w:\\1>", DOT_ALL).replace(xml, "")
        xml = xml.replace("</w:p>", "\n").replace(Regex("<w:tab\\s*/>"), "\t").replace(Regex("<w:(br|cr)\\b[^>]*/>"), "\n")
        return tidy(HtmlText.decodeEntities(ANY_TAG.replace(xml, "")))
    }

    private fun numberOf(name: String, re: Regex): Int = re.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private fun pptxText(z: ZipFile, names: Set<String>): String {
        val slideRe = Regex("ppt/slides/slide(\\d+)\\.xml")
        val slides = names.filter { slideRe.matches(it) }.sortedBy { numberOf(it, slideRe) }
        val sb = StringBuilder()
        for (slide in slides) {
            val xml = readEntryText(z, slide) ?: continue
            val text = tidy(HtmlText.decodeEntities(ANY_TAG.replace(xml.replace("</a:p>", "\n"), "")))
            val n = numberOf(slide, slideRe)
            sb.append("--- Slide ").append(n).append(" ---\n").append(text).append("\n\n")
            val notes = readEntryText(z, "ppt/notesSlides/notesSlide$n.xml")
            if (notes != null) {
                val noteText = tidy(HtmlText.decodeEntities(ANY_TAG.replace(notes.replace("</a:p>", "\n"), "")))
                // Notes pages also hold the slide number placeholder; only keep real text.
                if (noteText.replace(Regex("\\d+"), "").isNotBlank()) sb.append("Notes: ").append(noteText).append("\n\n")
            }
            if (sb.length > MAX_OUTPUT_CHARS) break
        }
        return sb.toString().trim()
    }

    private fun sharedStrings(xml: String): List<String> {
        val si = Regex("<si\\b[^>]*>(.*?)</si>", DOT_ALL)
        val t = Regex("<t\\b[^>]*>(.*?)</t>", DOT_ALL)
        val phonetic = Regex("<rPh\\b.*?</rPh>", DOT_ALL)
        return si.findAll(xml).map { m ->
            t.findAll(phonetic.replace(m.groupValues[1], "")).joinToString("") { HtmlText.decodeEntities(it.groupValues[1]) }
        }.toList()
    }

    private fun columnIndex(letters: String): Int {
        var idx = 0
        for (ch in letters) idx = idx * 26 + (ch - 'A' + 1)
        return idx - 1
    }

    private fun sheetRows(xml: String, shared: List<String>): String {
        val rowRe = Regex("<row\\b[^>]*[^/]>(.*?)</row>", DOT_ALL)
        val cellRe = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", DOT_ALL)
        val refRe = Regex("\\br=\"([A-Z]+)\\d+\"")
        val typeRe = Regex("\\bt=\"(\\w+)\"")
        val vRe = Regex("<v>(.*?)</v>", DOT_ALL)
        val tRe = Regex("<t\\b[^>]*>(.*?)</t>", DOT_ALL)
        val sb = StringBuilder()
        var rows = 0
        for (row in rowRe.findAll(xml)) {
            if (rows >= 3000) { sb.append("[… more rows not shown]\n"); break }
            val cells = ArrayList<String>()
            var next = 0
            for (cell in cellRe.findAll(row.groupValues[1])) {
                val attrs = cell.groupValues[1]
                val inner = cell.groupValues[2]
                val col = refRe.find(attrs)?.let { columnIndex(it.groupValues[1]) } ?: next
                val type = typeRe.find(attrs)?.groupValues?.get(1)
                val value = when (type) {
                    "s" -> vRe.find(inner)?.groupValues?.get(1)?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    "inlineStr" -> tRe.findAll(inner).joinToString("") { HtmlText.decodeEntities(it.groupValues[1]) }
                    "b" -> if (vRe.find(inner)?.groupValues?.get(1)?.trim() == "1") "TRUE" else "FALSE"
                    else -> vRe.find(inner)?.groupValues?.get(1)?.let { HtmlText.decodeEntities(it) } ?: ""
                }
                next = col + 1
                if (col >= MAX_COLUMNS || col < cells.size) continue
                while (cells.size < col) cells.add("")
                cells.add(value.replace('\t', ' ').replace('\n', ' '))
            }
            val line = cells.joinToString("\t").trimEnd('\t')
            if (line.isNotEmpty()) { sb.append(line).append('\n'); rows++ }
        }
        return sb.toString()
    }

    private const val MAX_COLUMNS = 200

    private fun xlsxText(z: ZipFile, names: Set<String>): String {
        val shared = readEntryText(z, "xl/sharedStrings.xml")?.let { sharedStrings(it) } ?: emptyList()
        val sheetNames = readEntryText(z, "xl/workbook.xml")?.let { wb ->
            Regex("<sheet\\b[^>]*?\\bname=\"([^\"]*)\"").findAll(wb).map { HtmlText.decodeEntities(it.groupValues[1]) }.toList()
        } ?: emptyList()
        val sheetRe = Regex("xl/worksheets/sheet(\\d+)\\.xml")
        val sheets = names.filter { sheetRe.matches(it) }.sortedBy { numberOf(it, sheetRe) }
        val sb = StringBuilder()
        for ((i, sheet) in sheets.withIndex()) {
            val xml = readEntryText(z, sheet) ?: continue
            sb.append("--- Sheet: ").append(sheetNames.getOrNull(i) ?: "Sheet${i + 1}").append(" ---\n")
            sb.append(sheetRows(xml, shared)).append('\n')
            if (sb.length > MAX_OUTPUT_CHARS) break
        }
        return sb.toString().trim()
    }

    private fun odfText(z: ZipFile): String {
        var xml = readEntryText(z, "content.xml") ?: return ""
        xml = Regex("<office:(automatic-styles|font-face-decls)\\b.*?</office:\\1>", DOT_ALL).replace(xml, "")
        xml = xml.replace(Regex("</text:(p|h)>"), "\n").replace(Regex("<text:tab\\s*/>"), "\t").replace(Regex("<text:line-break\\s*/>"), "\n")
            .replace(Regex("<text:s(?:\\s+text:c=\"(\\d+)\")?\\s*/>")) { m -> " ".repeat((m.groupValues[1].toIntOrNull() ?: 1).coerceIn(1, 100)) }
            .replace("</table:table-cell>", "\t").replace("</table:table-row>", "\n")
        return tidy(HtmlText.decodeEntities(ANY_TAG.replace(xml, "")))
    }

    // ---------------------------------------------------------------------------------------------------------
    // GZIP and TAR
    // ---------------------------------------------------------------------------------------------------------

    private class StreamLimit : IOException("archive larger than the read limit")

    /** Counts every byte read (skips included) and stops the stream at [limit]. */
    private class LimitedInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) bump(1)
            return b
        }
        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            val n = super.read(buf, off, len)
            if (n > 0) bump(n.toLong())
            return n
        }
        override fun skip(n: Long): Long {
            val tmp = ByteArray(8192)
            var left = n
            while (left > 0) {
                val r = read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
                if (r < 0) break
                left -= r
            }
            return n - left
        }
        private fun bump(n: Long) {
            count += n
            if (count > limit) throw StreamLimit()
        }
    }

    private fun fromGzip(file: File, name: String): Result {
        val fileStream = FileInputStream(file)
        val input = try {
            LimitedInput(GZIPInputStream(BufferedInputStream(fileStream)), MAX_STREAM_BYTES)
        } catch (e: Exception) {
            fileStream.close()
            throw e
        }
        input.use { raw ->
            val bin = BufferedInputStream(raw)
            bin.mark(1024)
            val head = readHead(bin, 512)
            bin.reset()
            val c = Collector()
            if (isTarMagic(head)) {
                readTar(bin, c)
                return assemble(name, "TAR.GZ", c, c.entryCount >= MAX_ENTRIES)
            }
            val inner = name.substringBeforeLast('.', name).ifEmpty { "content" }
            try {
                handleFile(c, inner, -1) { limit -> readBounded(bin, limit.toLong(), limit) }
            } catch (e: StreamLimit) {
                c.note = "Reading stopped: the data is larger than the read limit."
            }
            return assemble(name, "GZIP", c, false)
        }
    }

    private fun fromTarFile(file: File, name: String): Result {
        LimitedInput(BufferedInputStream(FileInputStream(file)), MAX_STREAM_BYTES).use { input ->
            val c = Collector()
            readTar(input, c)
            return assemble(name, "TAR", c, c.entryCount >= MAX_ENTRIES)
        }
    }

    private fun readTar(input: InputStream, c: Collector) {
        val header = ByteArray(512)
        var longName: String? = null
        var paxName: String? = null
        var zeroBlocks = 0
        try {
            while (c.entryCount < MAX_ENTRIES) {
                if (!readFully(input, header)) break
                if (header.all { it == 0.toByte() }) {
                    zeroBlocks++
                    if (zeroBlocks >= 2) break
                    continue
                }
                zeroBlocks = 0
                val size = parseOctal(header, 124, 12)
                if (size < 0) throw ArchiveException("unsupported TAR entry size")
                val type = header[156].toInt().toChar()
                var name = cString(header, 0, 100)
                if (isTarMagic(header)) {
                    val prefix = cString(header, 345, 155)
                    if (prefix.isNotEmpty()) name = "$prefix/$name"
                }
                val padded = (size + 511) / 512 * 512
                when (type) {
                    'L' -> {
                        val b = readBounded(input, size, 4096)
                        longName = cString(b, 0, b.size)
                        skipFully(input, padded - b.size)
                    }
                    'x' -> {
                        val b = readBounded(input, size, 65536)
                        paxName = parsePaxPath(String(b, Charsets.UTF_8))
                        skipFully(input, padded - b.size)
                    }
                    '0', '\u0000', '7' -> {
                        val entryName = longName ?: paxName ?: name
                        longName = null
                        paxName = null
                        var consumed = 0L
                        handleFile(c, entryName, size) { limit ->
                            val b = readBounded(input, size, limit)
                            consumed = b.size.toLong()
                            b
                        }
                        skipFully(input, padded - consumed)
                    }
                    else -> {
                        longName = null
                        paxName = null
                        skipFully(input, padded)
                    }
                }
            }
        } catch (e: StreamLimit) {
            c.note = "Reading stopped: the archive is larger than the read limit."
        }
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = minOf(b.size, off + len)
        while (end < max && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    /** Octal number field; -1 for the base-256 form used by huge sizes (not supported). */
    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        if ((b[off].toInt() and 0x80) != 0) return -1
        val s = cString(b, off, len).trim()
        if (s.isEmpty()) return 0
        return s.toLongOrNull(8) ?: -1
    }

    private fun parsePaxPath(text: String): String? {
        for (line in text.split('\n')) {
            val eq = line.indexOf("path=")
            if (eq >= 0) return line.substring(eq + 5)
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------------------
    // small stream helpers
    // ---------------------------------------------------------------------------------------------------------

    private fun readHead(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var total = 0
        while (total < n) {
            val r = input.read(buf, total, n - total)
            if (r < 0) break
            total += r
        }
        return buf.copyOf(total)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r < 0) return false
            total += r
        }
        return true
    }

    /** At most [max] bytes of the next [size] bytes of [input] (fewer when the stream ends). */
    private fun readBounded(input: InputStream, size: Long, max: Int): ByteArray {
        val want = if (size < 0) max else minOf(size, max.toLong()).toInt()
        val buf = ByteArray(want)
        var total = 0
        while (total < want) {
            val r = input.read(buf, total, want - total)
            if (r < 0) break
            total += r
        }
        return if (total == want) buf else buf.copyOf(total)
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        val tmp = ByteArray(8192)
        while (left > 0) {
            val r = input.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
            if (r < 0) break
            left -= r
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "size unknown"
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
