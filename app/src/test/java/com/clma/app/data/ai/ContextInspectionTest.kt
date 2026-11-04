package com.clma.app.data.ai

import com.clma.ai.core.InputSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.clma.ai.core.MessageRole
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextInspectionTest {
    @Test
    fun `counts UTF8 bytes without pretending they are tokens or double counting memory`() {
        val inspection = ContextInspection.capture(listOf(
            UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text("中文"))),
            UIMessage.user("summary").copy(isSynthetic = true),
            UIMessage.user("history"),
        ), emptyList(), "中文")
        assertNull(inspection.tokenCount)
        val system = inspection.sections.first { it.kind == ContextSectionKind.SYSTEM }
        assertTrue(system.utf8Bytes > system.chars)
        assertEquals(inspection.sections.filter { it.kind != ContextSectionKind.MEMORY_SOURCE }.sumOf { it.utf8Bytes }, inspection.utf8Bytes)
        assertTrue(inspection.sections.first { it.kind == ContextSectionKind.SYNTHETIC }.preview!!.contains("summary"))
    }

    @Test
    fun `tool schemas included without retaining executor and previews are redacted and bounded`() {
        val tool = Tool(
            name = "test",
            description = "description",
            parameters = { InputSchema.Obj(properties = buildJsonObject { put("example", "schema-value") }) },
            execute = { error("must not run") },
        )
        val inspection = ContextInspection.capture(listOf(UIMessage.user("password=secret " + "x".repeat(20_000))), listOf(tool), "")
        val history = inspection.sections.first { it.kind == ContextSectionKind.HISTORY }
        assertFalse(history.preview!!.contains("password=secret"))
        assertTrue(history.truncated)
        assertTrue(history.preview.length <= 12_000)
        assertTrue(inspection.sections.first { it.kind == ContextSectionKind.TOOLS }.preview!!.contains("description"))
        assertTrue(inspection.sections.first { it.kind == ContextSectionKind.TOOLS }.preview!!.contains("schema-value"))
        assertTrue(inspection.withoutPreviews().sections.all { it.preview == null })
    }

    @Test
    fun `info capture does not construct content previews`() {
        val inspection = ContextInspection.capture(listOf(UIMessage.user("private")), emptyList(), "", includePreviews = false)
        assertTrue(inspection.sections.all { it.preview == null })
        assertTrue(inspection.utf8Bytes > 0)
    }
}
