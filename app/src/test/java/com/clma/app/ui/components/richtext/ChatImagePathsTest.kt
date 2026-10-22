package com.clma.app.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatImagePathsTest {

    @Test
    fun `rootfs absolute paths are workspace paths`() {
        assertEquals("/workspace/out/a.png", ChatImagePaths.workspacePathOf("/workspace/out/a.png"))
        assertEquals("/tool_outputs/x.png", ChatImagePaths.workspacePathOf("/tool_outputs/x.png"))
        assertEquals("/root/a.png", ChatImagePaths.workspacePathOf("/root/a.png"))
    }

    @Test
    fun `relative paths resolve against the workspace files root`() {
        assertEquals("/workspace/a.png", ChatImagePaths.workspacePathOf("a.png"))
        assertEquals("/workspace/img/a.png", ChatImagePaths.workspacePathOf("./img/a.png"))
        assertEquals("/workspace/a.png", ChatImagePaths.workspacePathOf("  <a.png>  "))
    }

    @Test
    fun `file and sandbox urls pointing into the rootfs are workspace paths`() {
        assertEquals("/workspace/a.png", ChatImagePaths.workspacePathOf("file:///workspace/a.png"))
        assertEquals("/workspace/a.png", ChatImagePaths.workspacePathOf("file:/workspace/a.png"))
        assertEquals("/workspace/a.png", ChatImagePaths.workspacePathOf("file://localhost/workspace/a.png"))
        assertEquals("/workspace/a.png", ChatImagePaths.workspacePathOf("sandbox:/workspace/a.png"))
        assertEquals("/workspace/a.png", ChatImagePaths.workspacePathOf("workspace:a.png"))
    }

    @Test
    fun `percent encoding is decoded but plus signs are kept`() {
        assertEquals("/workspace/暖调 1.png", ChatImagePaths.workspacePathOf("/workspace/%E6%9A%96%E8%B0%83%201.png"))
        assertEquals("/workspace/a+b.png", ChatImagePaths.workspacePathOf("/workspace/a+b.png"))
    }

    @Test
    fun `network, inline and content urls are left alone`() {
        assertNull(ChatImagePaths.workspacePathOf("https://example.com/a.png"))
        assertNull(ChatImagePaths.workspacePathOf("http://example.com/a.png"))
        assertNull(ChatImagePaths.workspacePathOf("data:image/png;base64,iVBORw0KGgo="))
        assertNull(ChatImagePaths.workspacePathOf("content://com.clma.app.fileprovider/x.png"))
    }

    @Test
    fun `android host paths and agent home paths are left alone`() {
        assertNull(ChatImagePaths.workspacePathOf("/data/user/0/com.clma.app/files/upload/a.png"))
        assertNull(ChatImagePaths.workspacePathOf("file:///data/user/0/com.clma.app/files/upload/a.png"))
        assertNull(ChatImagePaths.workspacePathOf("/storage/emulated/0/Pictures/a.png"))
        assertNull(ChatImagePaths.workspacePathOf("/sdcard/Pictures/a.png"))
        assertNull(ChatImagePaths.workspacePathOf("~/a.png"))
    }

    @Test
    fun `blank and anchor values are ignored`() {
        assertNull(ChatImagePaths.workspacePathOf(null))
        assertNull(ChatImagePaths.workspacePathOf(""))
        assertNull(ChatImagePaths.workspacePathOf("   "))
        assertNull(ChatImagePaths.workspacePathOf("#top"))
    }
}
