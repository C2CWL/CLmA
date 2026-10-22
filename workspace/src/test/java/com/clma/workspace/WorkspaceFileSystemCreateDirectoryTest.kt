package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Coverage for [WorkspaceFileSystem.createDirectory] — the primitive behind the picker's
 * "new folder" action. Before it, the only way to create a directory was the mkdirs side effect
 * of [WorkspaceFileSystem.writeText], which always left a file behind.
 */
class WorkspaceFileSystemCreateDirectoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `creates a nested directory and reports it as a directory`() {
        val root = tempFolder.newFolder("workspace")
        val fs = WorkspaceFileSystem()

        val entry = fs.createDirectory(root, "notes/memory")

        assertTrue(entry.isDirectory)
        assertEquals("notes/memory", entry.path.replace('\\', '/'))
        assertEquals("memory", entry.name)
        // And it is a real directory the browser can descend into.
        assertTrue(fs.list(root, "notes").any { it.name == "memory" && it.isDirectory })
    }

    @Test
    fun `is idempotent for an existing directory`() {
        val root = tempFolder.newFolder("workspace")
        val fs = WorkspaceFileSystem()
        fs.createDirectory(root, "memory")

        val again = fs.createDirectory(root, "memory")

        assertTrue(again.isDirectory)
        assertEquals("memory", again.name)
    }

    @Test
    fun `refuses when a file already occupies the path`() {
        val root = tempFolder.newFolder("workspace")
        val fs = WorkspaceFileSystem()
        fs.writeText(root, "memory", "not a folder")

        val failure = runCatching { fs.createDirectory(root, "memory") }

        assertTrue(failure.isFailure)
        // The file is untouched.
        assertEquals("not a folder", fs.readText(root, "memory"))
    }

    @Test
    fun `rejects a path that escapes the workspace root`() {
        val root = tempFolder.newFolder("workspace")
        val fs = WorkspaceFileSystem()

        assertFalse(runCatching { fs.createDirectory(root, "../escape") }.isSuccess)
    }
}
