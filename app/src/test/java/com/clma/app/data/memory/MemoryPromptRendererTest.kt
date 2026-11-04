package com.clma.app.data.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryPromptRendererTest {
    @Test fun `hot serialization is stable across query orders and preserves prose`() {
        val records = listOf(9 to "line\n\"quoted\"", 2 to "首选项")
        assertEquals(MemoryPromptRenderer.hot(records), MemoryPromptRenderer.hot(records.reversed()))
        val rendered = MemoryPromptRenderer.hot(records)
        assertTrue(rendered.indexOf("\"id\": 2") < rendered.indexOf("\"id\": 9"))
        assertTrue(rendered.contains("line\\n\\\"quoted\\\""))
        assertEquals(rendered, MemoryPromptRenderer.hot(records))
    }

    @Test fun `cold formatting does not depend on multiline content indentation`() {
        assertEquals(null, MemoryPromptRenderer.coldIndex(" \n", 30))
        val result = MemoryPromptRenderer.coldIndex("first\nsecond", 30)!!
        assertTrue(result.startsWith("**Cold Memory Index (INDEX.md)**\nBelow"))
        assertTrue(result.endsWith("```markdown\nfirst\nsecond\n```"))
        assertTrue(MemoryPromptRenderer.coldIndex("abcdef", 3)!!.contains("abc\n[Index truncated to 3 characters."))
    }
}
