package com.clma.app.data.datastore.migration

import com.clma.app.data.ai.tools.ToolSurfaceMode
import com.clma.app.data.model.Assistant
import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** V7: the progressive tool catalog becomes the default, and stored assistants follow once. */
class ToolSurfaceDefaultMigrationTest {

    private fun decode(json: String) = JsonInstant.decodeFromString<List<Assistant>>(json)

    @Test
    fun `a new assistant defaults to the progressive catalog`() {
        assertEquals(ToolSurfaceMode.PROGRESSIVE_CATALOG, Assistant().toolSurfaceMode)
    }

    @Test
    fun `stored DIRECT is rewritten`() {
        val out = decode(
            migrateAssistantToolSurfaceJson("""[{"id":"3d47790c-c415-4b90-9388-751128adb0a0","toolSurfaceMode":"DIRECT"}]""")
        )
        assertEquals(ToolSurfaceMode.PROGRESSIVE_CATALOG, out.single().toolSurfaceMode)
    }

    @Test
    fun `an assistant without the field gets it too`() {
        val out = decode(
            migrateAssistantToolSurfaceJson("""[{"id":"3d47790c-c415-4b90-9388-751128adb0a0","name":"a"}]""")
        )
        assertEquals(ToolSurfaceMode.PROGRESSIVE_CATALOG, out.single().toolSurfaceMode)
    }

    @Test
    fun `other fields survive the rewrite`() {
        val out = decode(
            migrateAssistantToolSurfaceJson(
                """[{"id":"3d47790c-c415-4b90-9388-751128adb0a0","name":"小明","systemPrompt":"be brief",
                   |"enableMemory":true,"toolSurfaceMode":"DIRECT"}]""".trimMargin()
            )
        ).single()
        assertEquals("小明", out.name)
        assertEquals("be brief", out.systemPrompt)
        assertEquals(true, out.enableMemory)
        assertEquals(ToolSurfaceMode.PROGRESSIVE_CATALOG, out.toolSurfaceMode)
    }

    @Test
    fun `every assistant in the array is rewritten`() {
        val out = decode(
            migrateAssistantToolSurfaceJson(
                """[{"id":"3d47790c-c415-4b90-9388-751128adb0a0","toolSurfaceMode":"DIRECT"},
                   |{"id":"11111111-2222-3333-4444-555555555555","toolSurfaceMode":"DIRECT"}]""".trimMargin()
            )
        )
        assertEquals(2, out.size)
        out.forEach { assertEquals(ToolSurfaceMode.PROGRESSIVE_CATALOG, it.toolSurfaceMode) }
    }

    @Test
    fun `an already progressive array is returned untouched`() {
        val input = """[{"id":"3d47790c-c415-4b90-9388-751128adb0a0","toolSurfaceMode":"PROGRESSIVE_CATALOG"}]"""
        assertSame(input, migrateAssistantToolSurfaceJson(input))
    }

    @Test
    fun `junk is returned untouched`() {
        assertSame("not json", migrateAssistantToolSurfaceJson("not json"))
        assertSame("{}", migrateAssistantToolSurfaceJson("{}"))
    }
}
