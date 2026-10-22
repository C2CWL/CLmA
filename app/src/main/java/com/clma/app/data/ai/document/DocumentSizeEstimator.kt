package com.clma.app.data.ai.document

import com.clma.app.utils.isAllowedFileType
import java.io.File
import java.net.URI

/**
 * Estimates a document's prompt size (tokens) from disk when nothing is known in memory —
 * typically right after an app restart, before the document has been sent again.
 *
 * Cost per call: a couple of `File.length()` lookups plus, when the extracted-text cache exists,
 * one read of at most [SAMPLE_BYTES]. Callers memoize the result (see DocumentTokenEstimates), so
 * this runs at most once per document per process.
 *
 * Order:
 * 1. Extracted-text cache (`upload/.extracted/<name>.<size>-<mtime>.txt`) present: its byte length
 *    scaled by the tokens-per-byte ratio measured on its first [SAMPLE_BYTES] (same estimator as
 *    ContextBudgetPlanner; UTF-8 CJK and ASCII both come out at ~1 token per 3 bytes).
 * 2. No cache: a conservative multiplier of the original file size (see [heuristicTokens]).
 *
 * Pure JVM (no Android Uri), so it is unit-tested with temp files.
 */
object DocumentSizeEstimator {
    const val SAMPLE_BYTES = 64 * 1024

    /**
     * Tokens per byte of the original file when there is no cache, chosen to over- rather than
     * under-estimate (an over-estimate only makes compaction / reminders fire a bit earlier):
     * - DOCX/PPTX/EPUB are zip-compressed XML: a text-only 1.43 MB docx measured ~1.0M estimated
     *   tokens for Chinese and ~1.15M for English, so 1 token per byte.
     * - PDF: text is a small share of most PDFs (fonts, images) and the parser stops at 300 pages;
     *   1 token per 2 bytes, capped at [PDF_MAX_TOKENS].
     * - Plain text: UTF-8 CJK and ASCII are ~1 token per 3 bytes, GBK CJK 1 per 2; use 1 per 2.
     * - Anything else is inlined as a one-line placeholder: [BINARY_PLACEHOLDER_TOKENS].
     */
    const val ZIP_DOCUMENT_TOKENS_PER_BYTE = 1.0
    const val PDF_TOKENS_PER_BYTE = 0.5
    const val PDF_MAX_TOKENS = 600_000L
    const val TEXT_TOKENS_PER_BYTE = 0.5
    const val BINARY_PLACEHOLDER_TOKENS = 64L

    private val ZIP_DOCUMENT_EXTENSIONS = setOf("docx", "pptx", "epub")
    private val ZIP_DOCUMENT_MIMES = setOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/epub+zip",
    )

    /** Estimate for the document at [url] (a `file:` URL), or null when it is not a local file. */
    fun estimate(url: String, fileName: String, mime: String): Long? {
        val file = fileFromUrl(url) ?: return null
        if (!file.isFile) return null
        return estimate(file, fileName, mime)
    }

    fun estimate(file: File, fileName: String, mime: String): Long {
        ExtractedTextCache.cacheFileFor(file)
            ?.takeIf { it.isFile }
            ?.let { cache -> estimateFromCache(cache)?.let { return it } }
        return heuristicTokens(file.length(), fileName, mime)
    }

    /** Scales the cache file's length by the token/byte ratio of its first [SAMPLE_BYTES]. */
    internal fun estimateFromCache(cache: File): Long? = runCatching {
        val totalBytes = cache.length()
        if (totalBytes == 0L) return@runCatching 0L
        val sample = cache.inputStream().use { input ->
            val buffer = ByteArray(minOf(SAMPLE_BYTES.toLong(), totalBytes).toInt())
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
        if (sample.isEmpty()) return@runCatching null
        val sampleTokens = DocumentInlineBudget.estimateTokens(String(sample, Charsets.UTF_8))
        if (sample.size.toLong() == totalBytes) return@runCatching sampleTokens
        // Round up: a partial multi-byte char at the sample's end costs at most one token.
        (sampleTokens.toDouble() * totalBytes / sample.size).toLong() + 1
    }.getOrNull()

    /** File-size heuristic used when no extracted text is cached; see the constants above. */
    fun heuristicTokens(fileBytes: Long, fileName: String, mime: String): Long {
        val bytes = fileBytes.coerceAtLeast(0L)
        val normalizedMime = mime.lowercase().substringBefore(';').trim()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when {
            extension in ZIP_DOCUMENT_EXTENSIONS || normalizedMime in ZIP_DOCUMENT_MIMES ->
                (bytes * ZIP_DOCUMENT_TOKENS_PER_BYTE).toLong()
            extension == "pdf" || normalizedMime == "application/pdf" ->
                (bytes * PDF_TOKENS_PER_BYTE).toLong().coerceAtMost(PDF_MAX_TOKENS)
            isAllowedFileType(fileName, normalizedMime) -> (bytes * TEXT_TOKENS_PER_BYTE).toLong()
            else -> BINARY_PLACEHOLDER_TOKENS
        }
    }

    /** `file:` URL → File without android.net.Uri (percent-escapes decoded). */
    internal fun fileFromUrl(url: String): File? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "file") return@runCatching null
        uri.path?.let(::File)
    }.getOrNull()
}
