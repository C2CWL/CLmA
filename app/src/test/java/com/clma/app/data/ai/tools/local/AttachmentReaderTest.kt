package com.clma.app.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AttachmentReaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `resolveAttachmentFile resolves inside upload and rejects escapes`() {
        val uploadDir = tempFolder.newFolder("upload")
        val file = File(uploadDir, "sample.txt").apply { writeText("hello") }
        val extractedDir = File(uploadDir, ".extracted").apply { mkdirs() }
        val extracted = File(extractedDir, "sample.txt.extracted.txt").apply { writeText("parsed") }

        assertEquals(file.canonicalPath, resolveAttachmentFile(uploadDir, "/upload/sample.txt").canonicalPath)
        assertEquals(extracted.canonicalPath, resolveAttachmentFile(uploadDir, "/upload/.extracted/sample.txt.extracted.txt").canonicalPath)

        assertThrows(IllegalArgumentException::class.java) {
            resolveAttachmentFile(uploadDir, "/other/path.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveAttachmentFile(uploadDir, "/upload/../secret.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveAttachmentFile(uploadDir, "/upload/nonexistent.txt")
        }
    }

    @Test
    fun `readAttachmentPage pages by line and limit`() {
        val file =
            tempFolder.newFile("sample.txt").apply {
                writeText("line1\nline2\nline3\nline4\n")
            }
        val p1 = readAttachmentPage(file, offset = 1, column = 0, limit = 2)
        assertEquals("line1\nline2\n", p1.text)
        assertEquals(3, p1.nextOffset)
        assertEquals(0, p1.nextColumn)

        val p2 = readAttachmentPage(file, offset = p1.nextOffset!!, column = p1.nextColumn!!, limit = 2)
        assertEquals("line3\nline4\n", p2.text)
        assertNull(p2.nextOffset)
        assertNull(p2.nextColumn)
    }

    @Test
    fun `readAttachmentPage bounds long line without newline`() {
        val file =
            tempFolder.newFile("longline.txt").apply {
                writeText("a".repeat(250))
            }
        val p1 = readAttachmentPage(file, offset = 1, column = 0, limit = 10, maxChars = 100)
        assertEquals(100, p1.text.length)
        assertEquals(1, p1.nextOffset)
        assertEquals(100, p1.nextColumn)

        val p2 = readAttachmentPage(file, offset = p1.nextOffset!!, column = p1.nextColumn!!, limit = 10, maxChars = 100)
        assertEquals(100, p2.text.length)
        assertEquals(1, p2.nextOffset)
        assertEquals(200, p2.nextColumn)

        val p3 = readAttachmentPage(file, offset = p2.nextOffset!!, column = p2.nextColumn!!, limit = 10, maxChars = 100)
        assertEquals(50, p3.text.length)
        assertNull(p3.nextOffset)
        assertNull(p3.nextColumn)
    }

    @Test
    fun `resolveAttachmentFile rejects symlink outside upload`() {
        val uploadDir = tempFolder.newFolder("symlink-upload")
        val secret = tempFolder.newFile("private.txt").apply { writeText("private") }
        java.nio.file.Files
            .createSymbolicLink(File(uploadDir, "link.txt").toPath(), secret.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            resolveAttachmentFile(uploadDir, "/upload/link.txt")
        }
    }

    @Test
    fun `readAttachmentPage preserves Chinese content and line boundaries`() {
        val file = tempFolder.newFile("chinese.txt").apply { writeText("中文内容\n第二行") }
        val first = readAttachmentPage(file, offset = 1, column = 0, limit = 1, maxChars = 100)
        assertEquals("中文内容\n", first.text)
        val rest = readAttachmentPage(file, first.nextOffset!!, first.nextColumn!!, limit = 1)
        assertEquals("第二行", rest.text)
        assertNull(rest.nextOffset)
    }
}
