package com.clma.app.data.ai.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocumentSizeEstimatorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun upload(name: String, bytes: Int): File {
        val dir = File(tmp.root, "upload").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(ByteArray(bytes) { 0x50 }) }
    }

    @Test
    fun `restart with a cached CJK extraction estimates from the cache length`() {
        val source = upload("report.docx", 1_000)
        ExtractedTextCache.write(source, "文".repeat(100_000)) // 300_000 UTF-8 bytes
        val tokens = DocumentSizeEstimator.estimate(source, "report.docx", "application/msword")
        // Exact estimator value is 100_000; sampling may round up by a few tokens.
        assertTrue("got $tokens", tokens in 100_000L..100_010L)
    }

    @Test
    fun `restart with a cached ASCII extraction estimates three chars per token`() {
        val source = upload("notes.pdf", 1_000)
        ExtractedTextCache.write(source, "a".repeat(200_000))
        val tokens = DocumentSizeEstimator.estimate(source, "notes.pdf", "application/pdf")
        assertTrue("got $tokens", tokens in 66_667L..66_680L)
    }

    @Test
    fun `small cache is measured exactly`() {
        val source = upload("a.docx", 10)
        ExtractedTextCache.write(source, "hello world")
        assertEquals(4L, DocumentSizeEstimator.estimate(source, "a.docx", "application/msword"))
    }

    @Test
    fun `without a cache the file size heuristic is used`() {
        assertEquals(1_000L, DocumentSizeEstimator.estimate(upload("a.docx", 1_000), "a.docx", "application/msword"))
        assertEquals(500L, DocumentSizeEstimator.estimate(upload("b.pdf", 1_000), "b.pdf", "application/pdf"))
        assertEquals(500L, DocumentSizeEstimator.estimate(upload("c.txt", 1_000), "c.txt", "text/plain"))
        assertEquals(
            DocumentSizeEstimator.BINARY_PLACEHOLDER_TOKENS,
            DocumentSizeEstimator.estimate(upload("d.apk", 1_000), "d.apk", "application/vnd.android.package-archive"),
        )
    }

    @Test
    fun `pdf heuristic is capped`() {
        assertEquals(
            DocumentSizeEstimator.PDF_MAX_TOKENS,
            DocumentSizeEstimator.heuristicTokens(50_000_000, "big.pdf", "application/pdf"),
        )
    }

    @Test
    fun `stale cache of an older version of the file is ignored`() {
        val source = upload("a.docx", 1_000)
        ExtractedTextCache.write(source, "文".repeat(10))
        source.writeBytes(ByteArray(2_000))
        source.setLastModified(source.lastModified() + 5_000)
        assertEquals(2_000L, DocumentSizeEstimator.estimate(source, "a.docx", "application/msword"))
    }

    @Test
    fun `file urls are resolved without android Uri`() {
        assertEquals(
            File("/data/user/0/pkg/files/upload/a b.docx"),
            DocumentSizeEstimator.fileFromUrl("file:///data/user/0/pkg/files/upload/a%20b.docx"),
        )
        assertNull(DocumentSizeEstimator.fileFromUrl("content://media/external/1"))
        assertNull(DocumentSizeEstimator.fileFromUrl("not a url"))
        assertNull(DocumentSizeEstimator.estimate("file:///does/not/exist.docx", "exist.docx", "x"))
    }
}
