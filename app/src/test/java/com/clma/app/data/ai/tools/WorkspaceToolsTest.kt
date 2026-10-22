package com.clma.app.data.ai.tools

import com.clma.workspace.WorkspaceTreeEntry
import com.clma.workspace.WorkspaceTreeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [40] G1/G2 pure helpers in WorkspaceTools.kt:
 * - [String.isOutsideWritableRoots]: the writable-root prefix check, including the ".." smuggle fix.
 * - [formatWorkspaceTree]: the workspace_read_folder model-facing text renderer.
 */
class WorkspaceToolsTest {

    @Test
    fun `clean path inside a writable root is not outside`() {
        assertFalse("/workspace/notes.txt".isOutsideWritableRoots())
        assertFalse("/tmp/scratch".isOutsideWritableRoots())
        assertFalse("/workspace".isOutsideWritableRoots())
        // /skills is a writable safe zone as of upstream 019c46aee.
        assertFalse("/skills/foo".isOutsideWritableRoots())
    }

    @Test
    fun `path outside every writable root is outside`() {
        assertTrue("/etc/passwd".isOutsideWritableRoots())
        assertTrue("/var/log/x".isOutsideWritableRoots())
    }

    @Test
    fun `dotdot smuggle attempt is treated as outside even with a writable prefix`() {
        assertTrue("/workspace/../etc/x".isOutsideWritableRoots())
        assertTrue("/tmp/../../etc/passwd".isOutsideWritableRoots())
    }

    @Test
    fun `formatWorkspaceTree renders empty directory`() {
        val text = formatWorkspaceTree("/workspace", WorkspaceTreeResult(entries = emptyList(), truncated = false))
        assertEquals("/workspace/ (empty)", text)
    }

    @Test
    fun `formatWorkspaceTree indents nested entries by depth`() {
        val result = WorkspaceTreeResult(
            entries = listOf(
                WorkspaceTreeEntry(path = "src", name = "src", isDirectory = true, sizeBytes = 0, depth = 1),
                WorkspaceTreeEntry(path = "src/a.txt", name = "a.txt", isDirectory = false, sizeBytes = 12, depth = 2),
                WorkspaceTreeEntry(path = "b.txt", name = "b.txt", isDirectory = false, sizeBytes = 3, depth = 1),
            ),
            truncated = false,
        )

        val text = formatWorkspaceTree("/workspace", result)

        assertEquals(
            """
            /workspace/
              src/
                a.txt (12 bytes)
              b.txt (3 bytes)
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `formatWorkspaceTree appends a truncation notice when capped`() {
        val result = WorkspaceTreeResult(
            entries = listOf(
                WorkspaceTreeEntry(path = "a.txt", name = "a.txt", isDirectory = false, sizeBytes = 1, depth = 1),
            ),
            truncated = true,
        )

        val text = formatWorkspaceTree("/workspace", result)

        assertTrue(text.contains("truncated"))
        assertTrue(text.endsWith("truncated: showing 1 entries; narrow the path for a complete listing)"))
    }

    @Test
    fun `read_file without range returns a small file unchanged`() {
        val page = paginateText("a\nb\n", offset = null, limit = null)
        assertEquals("a\nb\n", page.text)
        assertEquals(2, page.totalLines)
        assertFalse(page.paged)
        assertEquals(null, page.nextOffset)
    }

    @Test
    fun `read_file offset and limit select lines and report where to continue`() {
        val text = (1..10).joinToString("\n") { "line$it" }
        val page = paginateText(text, offset = 3, limit = 4)
        assertEquals("line3\nline4\nline5\nline6", page.text)
        assertEquals(10, page.totalLines)
        assertEquals(3, page.startLine)
        assertEquals(4, page.linesReturned)
        assertEquals(7, page.nextOffset)
        assertTrue(page.paged)

        val last = paginateText(text, offset = 9, limit = 100)
        assertEquals("line9\nline10", last.text)
        assertEquals(null, last.nextOffset)
    }

    @Test
    fun `read_file pages a large file even without a range`() {
        val text = (1..5_000).joinToString("\n") { "x".repeat(10) }
        val page = paginateText(text, offset = null, limit = null, maxChars = 1_000)
        assertTrue(page.paged)
        assertTrue(page.text.length <= 1_000)
        assertEquals(5_000, page.totalLines)
        assertEquals(page.linesReturned + 1, page.nextOffset)
    }

    @Test
    fun `read_file default limit caps lines`() {
        val text = (1..5_000).joinToString("\n") { "y" }
        val page = paginateText(text, offset = 1, limit = null)
        assertEquals(READ_FILE_DEFAULT_LIMIT_LINES, page.linesReturned)
        assertEquals(READ_FILE_DEFAULT_LIMIT_LINES + 1, page.nextOffset)
    }

    @Test
    fun `read_file offset past the end returns nothing`() {
        val page = paginateText("a\nb", offset = 5, limit = 10)
        assertEquals("", page.text)
        assertEquals(0, page.linesReturned)
        assertEquals(null, page.nextOffset)
    }

    @Test
    fun `read_file cuts a single over-long line at the page cap`() {
        val page = paginateText("z".repeat(50) + "\nnext", offset = 1, limit = 10, maxChars = 20)
        assertEquals("z".repeat(20), page.text)
        assertEquals(2, page.nextOffset)
    }
}
