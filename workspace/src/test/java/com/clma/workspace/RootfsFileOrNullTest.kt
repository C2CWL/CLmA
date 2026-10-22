package com.clma.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootfsFileOrNullTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val root = "img-workspace"

    private fun createManager(toolOutputs: File): WorkspaceManager =
        WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = listOf(WorkspaceBindMount(source = toolOutputs, target = "/tool_outputs")),
        ).also { it.ensureWorkspace(root) }

    @Test
    fun `workspace image path maps to the files area`() {
        val manager = createManager(tempFolder.newFolder("tool_outputs"))
        val image = File(manager.filesDir(root), "out/a.png").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        assertEquals(image.canonicalFile, manager.resolveRootfsFileOrNull(root, "/workspace/out/a.png"))
    }

    @Test
    fun `bind mount and rootfs paths resolve`() {
        val toolOutputs = tempFolder.newFolder("tool_outputs")
        val manager = createManager(toolOutputs)
        val mounted = File(toolOutputs, "b.png").apply { writeBytes(byteArrayOf(1)) }
        val inRootfs = File(manager.linuxDir(root), "root/c.png").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        assertEquals(mounted.canonicalFile, manager.resolveRootfsFileOrNull(root, "/tool_outputs/b.png"))
        assertEquals(inRootfs.canonicalFile, manager.resolveRootfsFileOrNull(root, "/root/c.png"))
    }

    @Test
    fun `missing, directory, escaping and kernel paths return null`() {
        val manager = createManager(tempFolder.newFolder("tool_outputs"))
        assertNull(manager.resolveRootfsFileOrNull(root, "/workspace/missing.png"))
        assertNull(manager.resolveRootfsFileOrNull(root, "/workspace"))
        assertNull(manager.resolveRootfsFileOrNull(root, "/workspace/../../../etc/passwd"))
        assertNull(manager.resolveRootfsFileOrNull(root, "/proc/self/status"))
        assertNull(manager.resolveRootfsFileOrNull(root, "relative.png"))
    }
}
