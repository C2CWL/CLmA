package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScreenshotLedgerTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "ledger-${System.nanoTime()}").apply { mkdirs() }

    private fun deleteAction(file: File): () -> Boolean = { runCatching { file.delete() }.getOrDefault(false) }

    @Test
    fun `purge runs recorded deletions and empties the ledger`() {
        ScreenshotLedger.clear()
        val dir = tempDir()
        val a = File(dir, "a.png").apply { writeText("a") }
        val b = File(dir, "b.png").apply { writeText("b") }
        ScreenshotLedger.recordTransient(deleteAction(a))
        ScreenshotLedger.recordTransient(deleteAction(b))
        assertEquals(2, ScreenshotLedger.recordedCount())

        assertEquals(2, ScreenshotLedger.purge())

        assertFalse(a.exists())
        assertFalse(b.exists())
        assertEquals(0, ScreenshotLedger.recordedCount())
        dir.delete()
    }

    @Test
    fun `clear forgets deletions without running them`() {
        ScreenshotLedger.clear()
        val dir = tempDir()
        val f = File(dir, "keep.png").apply { writeText("x") }
        ScreenshotLedger.recordTransient(deleteAction(f))

        ScreenshotLedger.clear()

        assertTrue("clear must not delete anything", f.exists())
        assertEquals(0, ScreenshotLedger.recordedCount())
        f.delete()
        dir.delete()
    }

    @Test
    fun `purge counts only deletions that removed something and survives a throwing one`() {
        ScreenshotLedger.clear()
        ScreenshotLedger.recordTransient { false }                      // nothing to remove
        ScreenshotLedger.recordTransient { throw IllegalStateException() } // hostile deleter
        val dir = tempDir()
        val f = File(dir, "gone.png").apply { writeText("x") }
        ScreenshotLedger.recordTransient(deleteAction(f))

        assertEquals(1, ScreenshotLedger.purge())

        assertFalse(f.exists())
        assertEquals(0, ScreenshotLedger.recordedCount())
        dir.delete()
    }
}
