package com.clma.app.data.ai.document

import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reads only the head of a large text file, plus its full-length statistics, without holding
 * the whole file in memory.
 *
 * Documents used to be read whole (`readBytes()` / `readText()`) on every request and tool step,
 * even when only the part that fits the context window was inlined - a 40 MB log file became a
 * 40 MB byte array plus an 80 MB String each time. The statistics (characters, estimated tokens,
 * lines) are gathered in one streaming pass, so the truncation notice the model gets is exact.
 *
 * Pure JVM, unit-tested with temp files.
 */
object BoundedTextReader {
    private const val CHUNK_CHARS = 64 * 1024
    private const val CHARSET_SNIFF_BYTES = 64 * 1024
    private const val CHARS_PER_TOKEN = 3L

    /** Size of the whole text, as [DocumentInlineBudget] would measure it on the full String. */
    data class FullSize(val chars: Int, val tokens: Long, val lines: Int)

    data class Result(val head: String, val full: FullSize) {
        /** True when the head is the whole text. */
        val isComplete: Boolean get() = head.length == full.chars
    }

    data class DetectedCharset(val charset: Charset, val bomBytes: Int)

    /**
     * Reads at most [maxChars] characters of [file] decoded as [charset] (after skipping
     * [bomBytes]), and counts the whole text in the same pass.
     */
    fun read(file: File, charset: Charset, bomBytes: Int, maxChars: Int): Result {
        val limit = maxChars.coerceAtLeast(0)
        val head = StringBuilder(minOf(limit, CHUNK_CHARS))
        var chars = 0L
        var ascii = 0L
        var nonAscii = 0L
        var newlines = 0L
        var lastChar = '\u0000'
        file.inputStream().use { input ->
            input.skipFully(bomBytes.toLong())
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            InputStreamReader(input, decoder).use { reader ->
                val buffer = CharArray(CHUNK_CHARS)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    for (i in 0 until n) {
                        val c = buffer[i]
                        if (c.code <= 0x7F) ascii++ else nonAscii++
                        if (c == '\n') newlines++
                    }
                    val room = limit - head.length
                    if (room > 0) head.append(buffer, 0, minOf(room, n))
                    lastChar = buffer[n - 1]
                    chars += n
                }
            }
        }
        val lines = when {
            chars == 0L -> 0L
            lastChar == '\n' -> newlines
            else -> newlines + 1
        }
        return Result(
            head = head.toString(),
            full = FullSize(
                chars = chars.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                tokens = nonAscii + (ascii + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN,
                lines = lines.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ),
        )
    }

    /**
     * The charset [DocumentTextExtractor.decodeText] would pick, decided from the first
     * [CHARSET_SNIFF_BYTES] only: BOM, then strict UTF-8, then GB18030, then lenient UTF-8.
     */
    fun detectCharset(file: File): DetectedCharset {
        val head = file.inputStream().use { it.readUpTo(CHARSET_SNIFF_BYTES) }
        return detectCharset(head, isWholeFile = head.size.toLong() == file.length())
    }

    internal fun detectCharset(head: ByteArray, isWholeFile: Boolean): DetectedCharset {
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) {
            return DetectedCharset(Charsets.UTF_8, 3)
        }
        if (head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) {
            return DetectedCharset(Charsets.UTF_16BE, 2)
        }
        if (head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) {
            return DetectedCharset(Charsets.UTF_16LE, 2)
        }
        // A sample cut mid-character is not malformed: allow dropping a partial trailing
        // sequence (up to 3 bytes for UTF-8, 1 for GB18030's 2-byte form).
        if (decodesStrictly(head, Charsets.UTF_8, if (isWholeFile) 0 else 3)) {
            return DetectedCharset(Charsets.UTF_8, 0)
        }
        runCatching { Charset.forName("GB18030") }.getOrNull()?.let { gb ->
            if (decodesStrictly(head, gb, if (isWholeFile) 0 else 3)) return DetectedCharset(gb, 0)
        }
        return DetectedCharset(Charsets.UTF_8, 0)
    }

    private fun decodesStrictly(bytes: ByteArray, charset: Charset, maxTrim: Int): Boolean =
        (0..minOf(maxTrim, bytes.size)).any { trim ->
            try {
                charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, bytes.size - trim))
                true
            } catch (_: CharacterCodingException) {
                false
            }
        }

    private fun InputStream.readUpTo(max: Int): ByteArray {
        val buffer = ByteArray(max)
        var read = 0
        while (read < max) {
            val n = read(buffer, read, max - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}
