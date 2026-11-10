package com.clma.app.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputPreviewTest {
    @Test
    fun `short text is unchanged`() {
        assertEquals("hello", toolOutputPreview("hello", 5))
        assertEquals("", toolOutputPreview("", 5))
    }

    @Test
    fun `long log retains its final error`() {
        val result = toolOutputPreview("HEAD" + "x".repeat(100) + "FAIL", 8)
        assertEquals("HEAD\n…[100 characters omitted; head and tail shown]…\nFAIL", result)
    }

    @Test
    fun `cuts do not split surrogate pairs`() {
        val result = toolOutputPreview("a😀" + "x".repeat(100) + "😀z", 4)
        assertTrue(result.startsWith("a\n"))
        assertTrue(result.endsWith("\nz"))
        assertFalse(result.any { it.isSurrogate() })
    }

    @Test
    fun `single character budget keeps the end`() {
        assertTrue(toolOutputPreview("abc", 1).endsWith("c"))
    }
}
