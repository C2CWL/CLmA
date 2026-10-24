package com.clma.app.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolOutputNamesTest {

    @Test
    fun `plain id keeps its shape`() {
        assertEquals("call_abc123.txt", ToolOutputNames.fileNameFor("call_abc123"))
    }

    /** tool-call id 由模型/供应商提供，路径分隔符绝不能把它带出 tool_outputs/。 */
    @Test
    fun `path separators cannot escape the directory`() {
        val dir = File("/data/tool_outputs")
        for (id in listOf("../../shared_prefs/x", "a/b", "..\\..\\x", "/etc/passwd", "a\x00b")) {
            val name = ToolOutputNames.fileNameFor(id)
            assertFalse(name, name.contains('/') || name.contains('\\'))
            assertFalse(name, name.startsWith("."))
            assertEquals("id=$id name=$name", dir, File(dir, name).parentFile)
        }
    }

    @Test
    fun `name is bounded, ascii only and never blank`() {
        val long = ToolOutputNames.fileNameFor("a".repeat(500))
        assertEquals(ToolOutputNames.MAX_NAME_CHARS, long.length)

        for (id in listOf("", "...", "   ", "调用 一")) {
            val name = ToolOutputNames.fileNameFor(id)
            assertTrue("id=$id name=$name", name.endsWith(ToolOutputNames.EXTENSION))
            assertTrue("id=$id name=$name", name.length > ToolOutputNames.EXTENSION.length)
            assertTrue("id=$id name=$name", name.all { it.code < 0x80 })
        }
        assertEquals("output.txt", ToolOutputNames.fileNameFor("..."))
    }
}
