package com.clma.app.reliability

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogReaderTest {
    @Test
    fun `filters literal keywords ignoring case and limits lines`() {
        val page = AppLogReader.read("OK\nError one\nother\nERROR two", "error", 0, 1, 100)
        assertEquals("Error one\n", page.getValue("text").jsonPrimitive.content)
        val next = page.getValue("next_start_index").jsonPrimitive.int
        val rest = AppLogReader.read("OK\nError one\nother\nERROR two", "error", next, 1, 100)
        assertEquals("ERROR two", rest.getValue("text").jsonPrimitive.content)
        assertFalse(rest.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test
    fun `character pages preserve long lines without losing content`() {
        val text = "x".repeat(350) + "\nlast"
        val result = StringBuilder()
        var offset = 0
        do {
            val page = AppLogReader.read(text, "", offset, 2, 100)
            result.append(page.getValue("text").jsonPrimitive.content)
            val next = page["next_start_index"]?.jsonPrimitive?.int
            if (next == null) break
            assertTrue(next > offset)
            offset = next
        } while (true)
        assertEquals(text, result.toString())
    }

    @Test
    fun `empty results and offsets beyond end finish without a next page`() {
        for (page in listOf(
            AppLogReader.read("", "", 0, 200, 100),
            AppLogReader.read("abc", "missing", 0, 200, 100),
            AppLogReader.read("abc", "", Int.MAX_VALUE, 200, 100),
        )) {
            assertEquals("", page.getValue("text").jsonPrimitive.content)
            assertFalse(page.getValue("truncated").jsonPrimitive.boolean)
            assertFalse(page.containsKey("next_start_index"))
        }
    }
}
