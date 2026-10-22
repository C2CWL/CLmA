package com.clma.app.data.ai.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Routing by content: the parser is chosen from the file's bytes, not only from the (often wrong)
 * MIME label, and binary files are never read as text. Parsers are faked so the test runs on the
 * plain JVM (no MuPDF / XmlPull).
 */
class DocumentTextExtractorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val calls = mutableListOf<String>()
    private val fakeParsers = DocumentParsers(
        pdf = { calls += "pdf"; "PDF TEXT" },
        docx = { calls += "docx"; "DOCX TEXT" },
        pptx = { calls += "pptx"; "PPTX TEXT" },
        epub = { calls += "epub"; "EPUB TEXT" },
    )

    private fun zip(name: String, vararg entries: String): File {
        val file = tmp.newFile(name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { entry ->
                out.putNextEntry(ZipEntry(entry))
                out.write("<x/>".toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun bytes(name: String, data: ByteArray): File = tmp.newFile(name).apply { writeBytes(data) }

    @Test
    fun `docx labelled as msword is parsed as docx`() {
        val file = zip("report.docx", "[Content_Types].xml", "word/document.xml")
        assertEquals(DocumentFormat.DOCX, DocumentTextExtractor.detectFormat(file, "report.docx", "application/msword"))
        val result = DocumentTextExtractor.extract(file, "report.docx", "application/msword", fakeParsers)
        assertEquals("DOCX TEXT", result.text)
        assertEquals(listOf("docx"), calls)
        assertTrue(result.cacheable)
    }

    @Test
    fun `docx labelled as octet-stream is parsed as docx`() {
        val file = zip("report.docx", "word/document.xml")
        val result = DocumentTextExtractor.extract(file, "report.docx", "application/octet-stream", fakeParsers)
        assertEquals("DOCX TEXT", result.text)
    }

    @Test
    fun `docx content is recognised even with a misleading name`() {
        val file = zip("download.bin", "word/document.xml")
        assertEquals(DocumentFormat.DOCX, DocumentTextExtractor.detectFormat(file, "download.bin", "application/octet-stream"))
    }

    @Test
    fun `pptx and epub are recognised from their zip entries`() {
        val pptx = zip("a.pptx", "ppt/presentation.xml", "ppt/slides/slide1.xml")
        val epub = zip("b.epub", "mimetype", "META-INF/container.xml")
        assertEquals(DocumentFormat.PPTX, DocumentTextExtractor.detectFormat(pptx, "a.pptx", "application/octet-stream"))
        assertEquals(DocumentFormat.EPUB, DocumentTextExtractor.detectFormat(epub, "b.epub", "application/zip"))
    }

    @Test
    fun `xlsx and other zips are binary and never read as text`() {
        val xlsx = zip(
            "sheet.xlsx",
            "xl/workbook.xml",
        )
        val result = DocumentTextExtractor.extract(
            xlsx,
            "sheet.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            fakeParsers,
        )
        assertEquals(DocumentFormat.BINARY, result.format)
        assertTrue(result.text.startsWith("[binary file, "))
        assertTrue(calls.isEmpty())

        // A zip renamed to .txt still is not dumped into the prompt.
        val disguised = zip("notes.txt", "a.bin")
        assertEquals(DocumentFormat.BINARY, DocumentTextExtractor.detectFormat(disguised, "notes.txt", "text/plain"))
    }

    @Test
    fun `legacy OLE office file is binary`() {
        val ole = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        ) + ByteArray(512) { 0x41 }
        val file = bytes("old.doc", ole)
        val result = DocumentTextExtractor.extract(file, "old.doc", "application/msword", fakeParsers)
        assertEquals(DocumentFormat.BINARY, result.format)
        assertFalse(result.cacheable)
        assertTrue(result.text.startsWith("[binary file, ${ole.size} bytes"))
    }

    @Test
    fun `text with NUL bytes is binary even with a text name`() {
        val file = bytes("data.txt", byteArrayOf(0x41, 0x00, 0x42, 0x43))
        assertEquals(DocumentFormat.BINARY, DocumentTextExtractor.detectFormat(file, "data.txt", "text/plain"))
    }

    @Test
    fun `pdf is recognised by magic bytes`() {
        val file = bytes("x.bin", "%PDF-1.7\n...".toByteArray())
        val result = DocumentTextExtractor.extract(file, "x.bin", "application/octet-stream", fakeParsers)
        assertEquals("PDF TEXT", result.text)
    }

    @Test
    fun `utf8 text is inlined unchanged`() {
        val file = bytes("a.md", "# 标题\nhello".toByteArray(Charsets.UTF_8))
        val result = DocumentTextExtractor.extract(file, "a.md", "text/markdown", fakeParsers)
        assertEquals(DocumentFormat.TEXT, result.format)
        assertEquals("# 标题\nhello", result.text)
        assertFalse(result.cacheable)
    }

    @Test
    fun `GB18030 text is decoded instead of turning into replacement characters`() {
        val text = "中文文本，第二行。\n结束"
        val file = bytes("gbk.txt", text.toByteArray(charset("GB18030")))
        assertEquals(text, DocumentTextExtractor.extract(file, "gbk.txt", "text/plain", fakeParsers).text)
    }

    @Test
    fun `unknown non-text type stays a placeholder`() {
        val file = bytes("app.apk", ByteArray(64) { 0x7F })
        assertEquals(DocumentFormat.BINARY, DocumentTextExtractor.detectFormat(file, "app.apk", "application/vnd.android.package-archive"))
    }

    @Test
    fun `parser error strings are not cached`() {
        val file = zip("broken.docx", "word/document.xml")
        val parsers = DocumentParsers(docx = { "Error parsing DOCX file: boom" })
        assertFalse(DocumentTextExtractor.extract(file, "broken.docx", "application/msword", parsers).cacheable)
    }
}
