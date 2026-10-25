package com.clma.app.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class ToolOutputReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `resolveSafeFile finds file with or without extension`() {
        val dir = tempFolder.newFolder("outputs")
        val file = File(dir, "call_123.txt").apply { writeText("hello") }

        val resolvedDirect = ToolOutputReader.resolveSafeFile(dir, "call_123.txt")
        assertEquals(file.canonicalPath, resolvedDirect.canonicalPath)

        val resolvedWithoutExt = ToolOutputReader.resolveSafeFile(dir, "call_123")
        assertEquals(file.canonicalPath, resolvedWithoutExt.canonicalPath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resolveSafeFile rejects blank file name`() {
        val dir = tempFolder.newFolder("outputs")
        ToolOutputReader.resolveSafeFile(dir, "   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resolveSafeFile rejects path with forward slash`() {
        val dir = tempFolder.newFolder("outputs")
        ToolOutputReader.resolveSafeFile(dir, "sub/file.txt")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resolveSafeFile rejects path with backward slash`() {
        val dir = tempFolder.newFolder("outputs")
        ToolOutputReader.resolveSafeFile(dir, "sub\\file.txt")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resolveSafeFile rejects path traversal dots`() {
        val dir = tempFolder.newFolder("outputs")
        ToolOutputReader.resolveSafeFile(dir, "../outside.txt")
    }

    @Test(expected = FileNotFoundException::class)
    fun `resolveSafeFile throws when file does not exist`() {
        val dir = tempFolder.newFolder("outputs")
        ToolOutputReader.resolveSafeFile(dir, "nonexistent.txt")
    }

    @Test
    fun `read slices lines with offset and limit`() {
        val dir = tempFolder.newFolder("outputs")
        val file = File(dir, "test_slice.txt")
        val totalLinesCount = 50
        file.writeText((1..totalLinesCount).joinToString("\n") { "Line $it" })

        // Page 1: lines 1..20
        val page1 = ToolOutputReader.read(
            outputDir = dir,
            fileName = "test_slice.txt",
            offset = 1,
            limit = 20,
        )
        assertEquals(50, page1.total_lines)
        assertEquals(1, page1.offset)
        assertEquals(20, page1.limit)
        assertEquals(20, page1.returned_lines)
        assertTrue(page1.has_more)
        assertEquals(21, page1.next_offset)
        assertTrue(page1.content.startsWith("1: Line 1\n2: Line 2"))
        assertTrue(page1.content.endsWith("20: Line 20"))

        // Page 2: lines 21..40
        val page2 = ToolOutputReader.read(
            outputDir = dir,
            fileName = "test_slice.txt",
            offset = page1.next_offset!!,
            limit = 20,
        )
        assertEquals(21, page2.offset)
        assertEquals(20, page2.returned_lines)
        assertTrue(page2.has_more)
        assertEquals(41, page2.next_offset)
        assertTrue(page2.content.startsWith("21: Line 21"))
        assertTrue(page2.content.endsWith("40: Line 40"))

        // Page 3: lines 41..50 (last page)
        val page3 = ToolOutputReader.read(
            outputDir = dir,
            fileName = "test_slice.txt",
            offset = page2.next_offset!!,
            limit = 20,
        )
        assertEquals(41, page3.offset)
        assertEquals(10, page3.returned_lines)
        assertFalse(page3.has_more)
        assertNull(page3.next_offset)
        assertTrue(page3.content.startsWith("41: Line 41"))
        assertTrue(page3.content.endsWith("50: Line 50"))
    }

    @Test
    fun `read filters lines with regex or substring pattern`() {
        val dir = tempFolder.newFolder("outputs")
        val file = File(dir, "log.txt")
        file.writeText(
            """
            INFO starting application
            DEBUG memory ok
            ERROR failed to bind port
            INFO retrying connection
            ERROR timeout after 30s
            WARN low disk space
            """.trimIndent()
        )

        val result = ToolOutputReader.read(
            outputDir = dir,
            fileName = "log.txt",
            offset = 1,
            limit = 10,
            pattern = "ERROR.*",
        )

        assertEquals(6, result.total_lines)
        assertEquals(2, result.matched_total_lines)
        assertEquals(2, result.returned_lines)
        assertFalse(result.has_more)
        assertNull(result.next_offset)
        assertEquals("3: ERROR failed to bind port\n5: ERROR timeout after 30s", result.content)
    }

    @Test
    fun `read respects maxChars budget and marks truncated`() {
        val dir = tempFolder.newFolder("outputs")
        val file = File(dir, "long_lines.txt")
        val lines = (1..20).map { "Line $it: " + "A".repeat(100) }
        file.writeText(lines.joinToString("\n"))

        // Set maxChars tight so only ~3 lines fit
        val result = ToolOutputReader.read(
            outputDir = dir,
            fileName = "long_lines.txt",
            offset = 1,
            limit = 20,
            maxChars = 350,
        )

        assertTrue(result.truncated_by_max_chars)
        assertTrue(result.returned_lines < 20)
        assertTrue(result.content.length <= 350)
    }
}
