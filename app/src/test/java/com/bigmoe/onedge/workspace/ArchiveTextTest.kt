package com.bigmoe.onedge.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveTextTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(name: String, entries: List<Pair<String, ByteArray>>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, b) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(b)
                z.closeEntry()
            }
        }
        return f
    }

    private fun t(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun tarBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in entries) {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h)
            "0000644".toByteArray().copyInto(h, 100)
            String.format("%011o", data.size).toByteArray().copyInto(h, 124)
            h[156] = '0'.code.toByte()
            "ustar".toByteArray().copyInto(h, 257)
            out.write(h)
            out.write(data)
            out.write(ByteArray((512 - data.size % 512) % 512))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun gz(bytes: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        GZIPOutputStream(o).use { it.write(bytes) }
        return o.toByteArray()
    }

    @Test
    fun zipTextFilesAreReadAndBinaryOnesOnlyListed() {
        val f = zip("proj.zip", listOf(
            "src/main.py" to t("print('hi')\n"),
            "README.md" to t("# Title\nhello"),
            "logo.png" to byteArrayOf(1, 2, 3, 0, 0),
            "data/blob.dat" to ByteArray(2000) { (it % 7).toByte() }
        ))
        val r = ArchiveText.extract(f, "proj.zip")
        assertEquals("ZIP", r.format)
        assertEquals(2, r.textFileCount)
        assertTrue(r.text.contains("=== src/main.py ==="))
        assertTrue(r.text.contains("print('hi')"))
        assertTrue(r.text.contains("logo.png (binary"))
        assertTrue(r.text.contains("blob.dat (binary"))
        // README comes before the other files so it survives a tight budget
        assertTrue(r.text.indexOf("=== README.md ===") < r.text.indexOf("=== src/main.py ==="))
    }

    @Test
    fun unsafePathsAreListedAsSkippedAndNeverRead() {
        val f = zip("evil.zip", listOf("../../etc/passwd" to t("root:x"), "ok.txt" to t("fine")))
        val r = ArchiveText.extract(f, "evil.zip")
        assertTrue(r.text.contains("skipped: unsafe path"))
        assertFalse(r.text.contains("root:x"))
        assertTrue(r.text.contains("fine"))
    }

    @Test
    fun hugeEntryIsCutAtTheEntryLimitInsteadOfFillingMemory() {
        val big = ByteArray(5_000_000) { 'a'.code.toByte() }
        val f = zip("bomb.zip", listOf("big.txt" to big))
        val r = ArchiveText.extract(f, "bomb.zip")
        assertTrue(r.text.length < 1_200_000)
        assertTrue(r.text.contains("the rest of this file is not shown"))
    }

    @Test
    fun noiseEntriesAreIgnored() {
        val f = zip("mac.zip", listOf("__MACOSX/._a.txt" to t("junk"), ".DS_Store" to t("junk"), "a.txt" to t("real")))
        val r = ArchiveText.extract(f, "mac.zip")
        assertEquals(1, r.entryCount)
        assertFalse(r.text.contains("junk"))
    }

    @Test
    fun docxTextIsExtractedWithoutFieldCodes() {
        val xml = """<w:document><w:body><w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:instrText> HYPERLINK "x" </w:instrText></w:r><w:r><w:tab/><w:t>world &amp; co</w:t></w:r></w:p><w:p><w:r><w:t>Second line</w:t></w:r></w:p></w:body></w:document>"""
        val f = zip("a.docx", listOf("[Content_Types].xml" to t("<x/>"), "word/document.xml" to t(xml)))
        val r = ArchiveText.extract(f, "a.docx")
        assertEquals("DOCX document", r.format)
        assertTrue(r.text.contains("Hello\tworld & co"))
        assertTrue(r.text.contains("Second line"))
        assertFalse(r.text.contains("HYPERLINK"))
    }

    @Test
    fun xlsxRowsKeepTheirColumnsAndSharedStrings() {
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1"><v>3.5</v></c></row><row r="2"/><row r="3"><c r="B3" t="inlineStr"><is><t>x &amp; y</t></is></c><c r="D3" t="b"><v>1</v></c></row></sheetData></worksheet>"""
        val f = zip("a.xlsx", listOf(
            "xl/workbook.xml" to t("""<workbook><sheets><sheet name="Budget" sheetId="1" r:id="rId1"/></sheets></workbook>"""),
            "xl/sharedStrings.xml" to t("""<sst><si><t>Name</t></si></sst>"""),
            "xl/worksheets/sheet1.xml" to t(sheet)
        ))
        val r = ArchiveText.extract(f, "a.xlsx")
        assertEquals("XLSX spreadsheet", r.format)
        assertTrue(r.text.contains("--- Sheet: Budget ---"))
        assertTrue(r.text.contains("Name\t\t3.5"))
        assertTrue(r.text.contains("\tx & y\t\tTRUE"))
    }

    @Test
    fun pptxSlidesAreNumberedInOrder() {
        val f = zip("a.pptx", listOf(
            "ppt/slides/slide2.xml" to t("<p:sld><a:p><a:r><a:t>Second</a:t></a:r></a:p></p:sld>"),
            "ppt/slides/slide1.xml" to t("<p:sld><a:p><a:r><a:t>First</a:t></a:r></a:p></p:sld>")
        ))
        val r = ArchiveText.extract(f, "a.pptx")
        assertTrue(r.text.indexOf("--- Slide 1 ---") < r.text.indexOf("--- Slide 2 ---"))
        assertTrue(r.text.contains("First") && r.text.contains("Second"))
    }

    @Test
    fun odtParagraphsAndTables() {
        val xml = """<office:document-content><office:automatic-styles><style:style name="P1"/></office:automatic-styles><office:body><text:p>One</text:p><text:p>Two<text:tab/>x</text:p></office:body></office:document-content>"""
        val f = zip("a.odt", listOf("mimetype" to t("application/vnd.oasis.opendocument.text"), "content.xml" to t(xml)))
        val r = ArchiveText.extract(f, "a.odt")
        assertTrue(r.text.contains("One\nTwo\tx"))
        assertFalse(r.text.contains("P1"))
    }

    @Test
    fun epubChaptersBecomePlainText() {
        val f = zip("b.epub", listOf(
            "mimetype" to t("application/epub+zip"),
            "META-INF/container.xml" to t("<container/>"),
            "OEBPS/content.opf" to t("<package/>"),
            "OEBPS/ch1.xhtml" to t("<html><body><h1>Chapter</h1><p>Once upon a time</p></body></html>")
        ))
        val r = ArchiveText.extract(f, "b.epub")
        assertTrue(r.text.contains("Once upon a time"))
        assertFalse(r.text.contains("<p>"))
    }

    @Test
    fun tarAndTarGzAndSingleGz() {
        val tar = tarBytes(listOf("a/hello.txt" to t("hello tar"), "b.bin" to ByteArray(600) { 0 }))
        val plain = tmp.newFile("x.tar").also { it.writeBytes(tar) }
        val r1 = ArchiveText.extract(plain, "x.tar")
        assertEquals("TAR", r1.format)
        assertTrue(r1.text.contains("hello tar"))
        assertTrue(r1.text.contains("b.bin (binary"))

        val tgz = tmp.newFile("x.tar.gz").also { it.writeBytes(gz(tar)) }
        val r2 = ArchiveText.extract(tgz, "x.tar.gz")
        assertEquals("TAR.GZ", r2.format)
        assertTrue(r2.text.contains("hello tar"))

        val single = tmp.newFile("log.txt.gz").also { it.writeBytes(gz(t("line one\nline two"))) }
        val r3 = ArchiveText.extract(single, "log.txt.gz")
        assertEquals("GZIP", r3.format)
        assertTrue(r3.text.contains("line two"))
    }

    @Test
    fun damagedAndNonArchiveFilesGiveAReadableError() {
        val notZip = tmp.newFile("fake.zip").also { it.writeText("this is just text") }
        try {
            ArchiveText.extract(notZip, "fake.zip")
            fail("expected ArchiveException")
        } catch (e: ArchiveException) {
            assertTrue(e.message!!.contains("fake.zip"))
        }
        val cut = tmp.newFile("cut.zip").also { f ->
            val whole = zip("whole.zip", listOf("a.txt" to t("hello world".repeat(100)))).readBytes()
            f.writeBytes(whole.copyOf(whole.size / 2))
        }
        try {
            ArchiveText.extract(cut, "cut.zip")
            fail("expected ArchiveException")
        } catch (e: ArchiveException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun nameAndMagicDetection() {
        assertTrue(ArchiveText.isArchiveName("a.ZIP"))
        assertTrue(ArchiveText.isArchiveName("x.tar.gz"))
        assertTrue(ArchiveText.isArchiveName("report.docx"))
        assertFalse(ArchiveText.isArchiveName("notes.txt"))
        assertTrue(ArchiveText.isZipMagic(byteArrayOf(0x50, 0x4B, 3, 4)))
        assertTrue(ArchiveText.isGzipMagic(byteArrayOf(0x1F, 0x8B.toByte())))
        assertFalse(ArchiveText.looksLikeArchive("plain".toByteArray()))
    }
}
