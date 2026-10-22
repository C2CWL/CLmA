package com.clma.app.data.ai.document

import com.clma.app.utils.isAllowedFileType
import com.clma.document.DocxParser
import com.clma.document.EpubParser
import com.clma.document.PdfParser
import com.clma.document.PptxParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** What a document attachment really is, decided from its bytes first and its labels second. */
enum class DocumentFormat { PDF, DOCX, PPTX, EPUB, TEXT, BINARY }

/** Parsers per format; injectable so routing can be tested without MuPDF / XmlPull. */
class DocumentParsers(
    val pdf: (File) -> String = PdfParser::parserPdf,
    val docx: (File) -> String = DocxParser::parse,
    val pptx: (File) -> String = PptxParser::parse,
    val epub: (File) -> String = EpubParser::parse,
)

/**
 * Turns an attached file into prompt text.
 *
 * The format is detected from the file's magic bytes, with the MIME type and file extension only
 * as tie-breakers. This matters because pickers mislabel files: a real `.docx` arrives as
 * `application/msword` or `application/octet-stream`, and reading such a file with `readText()`
 * used to put the raw zip bytes into the prompt. Binary content is never read as text.
 */
object DocumentTextExtractor {
    private const val SNIFF_BYTES = 8 * 1024

    private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // PK\3\4
    private val ZIP_EMPTY_MAGIC = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val OLE_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )

    private const val MIME_PDF = "application/pdf"
    private const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    private const val MIME_EPUB = "application/epub+zip"

    /** Prefixes of the short error strings the parsers return instead of throwing. */
    private val PARSER_ERROR_PREFIXES = listOf(
        "Error parsing", "Unable to find", "Unable to read", "No readable content",
    )

    fun binaryPlaceholder(size: Long): String =
        "[binary file, $size bytes — content not inlined; use workspace tools on the path above]"

    fun detectFormat(file: File, fileName: String, mime: String): DocumentFormat {
        val head = readHead(file)
        val normalizedMime = mime.lowercase().substringBefore(';').trim()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when {
            head.startsWith(PDF_MAGIC) -> DocumentFormat.PDF
            head.startsWith(ZIP_MAGIC) || head.startsWith(ZIP_EMPTY_MAGIC) ->
                zipFormat(file) ?: declaredZipFormat(normalizedMime, extension) ?: DocumentFormat.BINARY
            head.startsWith(OLE_MAGIC) -> DocumentFormat.BINARY // legacy .doc/.xls/.ppt: no parser
            // Some PDFs carry a few junk bytes before %PDF; let MuPDF decide when labelled so.
            normalizedMime == MIME_PDF || extension == "pdf" -> DocumentFormat.PDF
            looksLikeText(head) && isAllowedFileType(fileName, normalizedMime) -> DocumentFormat.TEXT
            else -> DocumentFormat.BINARY
        }
    }

    /**
     * Extracts the text to inline, or a short placeholder for binaries. Parser failures come
     * back as short `[ERROR …]`/parser messages; [Extraction.cacheable] is false for those.
     */
    fun extract(
        file: File,
        fileName: String,
        mime: String,
        parsers: DocumentParsers = DocumentParsers(),
    ): Extraction {
        val format = detectFormat(file, fileName, mime)
        return runCatching {
            val text = when (format) {
                DocumentFormat.PDF -> parsers.pdf(file)
                DocumentFormat.DOCX -> parsers.docx(file)
                DocumentFormat.PPTX -> parsers.pptx(file)
                DocumentFormat.EPUB -> parsers.epub(file)
                DocumentFormat.TEXT -> decodeText(file.readBytes())
                DocumentFormat.BINARY -> return Extraction(format, binaryPlaceholder(file.length()), cacheable = false)
            }
            val isError = PARSER_ERROR_PREFIXES.any { text.startsWith(it) }
            Extraction(format, text, cacheable = !isError && format != DocumentFormat.TEXT)
        }.getOrElse {
            Extraction(format, "[ERROR, failed to read file: $fileName]", cacheable = false)
        }
    }

    data class Extraction(
        val format: DocumentFormat,
        val text: String,
        /** True for successfully parsed PDF/DOCX/PPTX/EPUB output worth caching on disk. */
        val cacheable: Boolean,
    )

    /**
     * Decodes text bytes: BOM-marked UTF-8/UTF-16 first, then strict UTF-8, then GB18030 (common
     * for Chinese .txt files), and finally lenient UTF-8.
     */
    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }
        runCatching { Charset.forName("GB18030") }.getOrNull()
            ?.let { strictDecode(bytes, it) }
            ?.let { return it }
        return String(bytes, Charsets.UTF_8)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun readHead(file: File): ByteArray = runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(SNIFF_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** Text has no NUL bytes; control characters other than tab/CR/LF/FF/ESC are rare. */
    internal fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return true
        var control = 0
        for (b in head) {
            val v = b.toInt() and 0xFF
            if (v == 0) return false
            if (v < 0x20 && v != 0x09 && v != 0x0A && v != 0x0D && v != 0x0C && v != 0x1B) control++
        }
        return control * 100 / head.size < 5
    }

    /**
     * Office/EPUB containers are zips; their entry names say which one it is. Returns null only
     * when the zip directory cannot be read at all.
     */
    private fun zipFormat(file: File): DocumentFormat? = runCatching {
        ZipFile(file).use { zip ->
            when {
                zip.getEntry("word/document.xml") != null -> DocumentFormat.DOCX
                zip.getEntry("ppt/presentation.xml") != null ||
                    zip.entries().asSequence().any { it.name.startsWith("ppt/slides/") } -> DocumentFormat.PPTX
                zip.getEntry("META-INF/container.xml") != null -> DocumentFormat.EPUB
                // A readable zip that is none of the above (xlsx, apk, plain zip): binary.
                else -> DocumentFormat.BINARY
            }
        }
    }.getOrNull()

    /** Used only when the zip directory cannot be read (e.g. truncated file): trust the label. */
    private fun declaredZipFormat(mime: String, extension: String): DocumentFormat? = when {
        mime == MIME_DOCX || extension == "docx" -> DocumentFormat.DOCX
        mime == MIME_PPTX || extension == "pptx" -> DocumentFormat.PPTX
        mime == MIME_EPUB || extension == "epub" -> DocumentFormat.EPUB
        else -> null
    }
}
