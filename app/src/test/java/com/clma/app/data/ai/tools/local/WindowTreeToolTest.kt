package com.clma.app.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowTreeToolTest {

    @Test
    fun `structural nodes alone do not imply semantic content`() {
        assertFalse(hasSemanticNodes(emptyList()))
        assertFalse(hasSemanticNodes(listOf(Json.parseToJsonElement("""{"class":"SurfaceView","bounds":[0,0,100,100]}""").jsonObject)))
    }

    @Test
    fun `text and actionable nodes expose semantic content`() {
        for (field in listOf("text", "content_description", "clickable", "scrollable", "editable")) {
            val value = if (field == "text" || field == "content_description") "\"label\"" else "true"
            assertTrue(hasSemanticNodes(listOf(Json.parseToJsonElement("""{"$field":$value}""").jsonObject)))
        }
    }

    @Test
    fun `read_window_tree returns service-not-active when offline`() {
        val tool = readWindowTreeTool()
        val result = execTool(tool, """{}""")
        assertTrue(
            "expected service-not-active envelope, got: $result",
            result.contains("AccessibilityService not active")
        )
    }

    @Test
    fun `read_window_tree accepts verbose param without error`() {
        val tool = readWindowTreeTool()
        val result = execTool(tool, """{"verbose":true}""")
        // Service is offline in JVM tests, so we still get the not-active envelope —
        // but no parameter-validation error.
        assertTrue(result.contains("AccessibilityService not active"))
    }

    @Test
    fun `read_window_tree accepts max_nodes param`() {
        val tool = readWindowTreeTool()
        val result = execTool(tool, """{"max_nodes":50}""")
        assertTrue(result.contains("AccessibilityService not active"))
    }

    @Test
    fun `read_window_tree accepts display_id param without error`() {
        val tool = readWindowTreeTool()
        val result = execTool(tool, """{"display_id":2}""")
        // Service is offline in JVM tests, so we get the not-active envelope —
        // but no parameter-validation error.
        assertTrue(result.contains("AccessibilityService not active"))
    }
}
