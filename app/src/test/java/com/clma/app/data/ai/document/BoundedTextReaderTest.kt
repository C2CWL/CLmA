package com.clma.app.data.ai.document

import java.io.File
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedTextReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val text = buildString {
        repeat(5_000) { append("第 $it 行 line $it\n") }
        append("last line without newline")
    }

    @Test
    fun `reads only the head but measures the whole text exactly`() {
        val file = tmp.newFile("big.txt").apply { writeText(text, Charsets.UTF_8) }
        val result = BoundedTextReader.read(file, Charsets.UTF_8, bomBytes = 0, maxChars = 1_000)
        assertEquals(text.substring(0, 1_000), result.head)
        assertFalse(result.isComplete)
        assertEquals(text.length, result.full.chars)
        assertEquals(DocumentInlineBudget.estimateTokens(text), result.full.tokens)
        assertEquals(DocumentInlineBudget.lineCount(text), result.full.lines)
    }

    @Test
    fun `a file within the cap is complete`() {
        val file = tmp.newFile("small.txt").apply { writeText("a\nb\n") }
        val result = BoundedTextReader.read(file, Charsets.UTF_8, bomBytes = 0, maxChars = 1_000)
        assertEquals("a\nb\n", result.head)
        assertTrue(result.isComplete)
        assertEquals(DocumentInlineBudget.lineCount("a\nb\n"), result.full.lines)
    }

    @Test
    fun `charset detection matches decodeText on a cut sample`() {
        val utf8 = "中文内容".repeat(100).toByteArray(Charsets.UTF_8)
        // Cut in the middle of a 3-byte character: still UTF-8, not GB18030.
        val cut = utf8.copyOf(utf8.size - 1)
        assertEquals(Charsets.UTF_8, BoundedTextReader.detectCharset(cut, isWholeFile = false).charset)
        val gb = Charset.forName("GB18030")
        val gbBytes = "中文内容".repeat(100).toByteArray(gb)
        assertEquals(gb, BoundedTextReader.detectCharset(gbBytes, isWholeFile = true).charset)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "abc".toByteArray()
        assertEquals(BoundedTextReader.DetectedCharset(Charsets.UTF_8, 3), BoundedTextReader.detectCharset(bom, true))
    }

    @Test
    fun `GB18030 file is decoded and skips a BOM`() {
        val gb = Charset.forName("GB18030")
        val file = tmp.newFile("gb.txt").apply { writeBytes("你好世界\n第二行".toByteArray(gb)) }
        val detected = BoundedTextReader.detectCharset(file)
        val result = BoundedTextReader.read(file, detected.charset, detected.bomBytes, maxChars = 2)
        assertEquals("你好", result.head)
        assertEquals("你好世界\n第二行".length, result.full.chars)
        assertEquals(2, result.full.lines)
    }

    @Test
    fun `streaming cache copy transcodes to UTF-8`() {
        val upload = tmp.newFolder("upload")
        val gb = Charset.forName("GB18030")
        val source = File(upload, "gb.txt").apply { writeBytes("你好世界".toByteArray(gb)) }
        val cache = ExtractedTextCache.writeStreaming(source, gb, 0)!!
        assertEquals("你好世界", cache.readText(Charsets.UTF_8))
    }
}
