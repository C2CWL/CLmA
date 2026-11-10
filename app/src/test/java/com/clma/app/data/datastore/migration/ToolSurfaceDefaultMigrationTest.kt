package com.clma.app.data.datastore.migration

import com.clma.app.data.ai.tools.ToolSurfaceMode
import com.clma.app.data.model.Assistant
import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * V7 rewrote every assistant to PROGRESSIVE_CATALOG; V11 rewrites them back to DIRECT
 * (product default again, to keep prompt-cache hits on long sessions). Both helpers are
 * covered here so the historical one-off and the reverse stay locked.
 */
class ToolSurfaceDefaultMigrationTest {

    private fun decode(json: String) = JsonInstant.decodeFromString<List<Assistant>>(json)

    @Test
    fun `a new assistant defaults to direct tool surface`() {
        assertEquals(ToolSurfaceMode.DIRECT, Assistant().toolSurfaceMode)
    }

    // --- V7: migrateAssistantToolSurfaceJson → PROGRESSIVE_CATALOG ---

    @Test
    fun `v7 stored DIRECT is rewritten to progressive`() {
        val out = decode(
            migrateAssistantToolSurfaceJson("""[{"id":"3d47790c-c415-4b90-9388-751128adb0a0","toolSurfaceMode":"DIRECT"}]""")
        )
        assertEquals(ToolSurfaceMode.PROGRESSIVE_CATALOG, out.single().toolSurfaceMode)
    }

    @Test
    fun `v7 an assistant without the field gets progressive`() {
        val out = decode(
            migrateAssistantToolSurfaceJson("""[{"id":"3d47790c-c415-4b90-9388-751128adb0a0","name":"a"}]""")
        )
        assertEquals(ToolSurfaceMode.PROGRESSIVE_CATALOG, out.single().toolSurfaceMode)
    }

    @Test
    fun `v7 other fields survive the rewrite`() {
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
    fun `v7 every assistant in the array is rewritten`() {
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
    fun `v7 an already progressive array is returned untouched`() {
        val input = """[{"id":"3d47790c-c415-4b90-9388-751128adb0a0","toolSurfaceMode":"PROGRESSIVE_CATALOG"}]"""
        assertSame(input, migrateAssistantToolSurfaceJson(input))
    }

    @Test
    fun `v7 junk is returned untouched`() {
        assertSame("not json", migrateAssistantToolSurfaceJson("not json"))
        assertSame("{}", migrateAssistantToolSurfaceJson("{}"))
    }

    // --- V11: migrateAssistantToolSurfaceToDirectJson → DIRECT ---

    @Test
    fun `v11 stored progressive is rewritten to direct`() {
        val out = decode(
            migrateAssistantToolSurfaceToDirectJson(
                """[{"id":"3d47790c-c415-4b90-9388-751128adb0a0","toolSurfaceMode":"PROGRESSIVE_CATALOG"}]"""
            )
        )
        assertEquals(ToolSurfaceMode.DIRECT, out.single().toolSurfaceMode)
    }

    @Test
    fun `v11 an assistant without the field gets direct`() {
        val out = decode(
            migrateAssistantToolSurfaceToDirectJson(
                """[{"id":"3d47790c-c415-4b90-9388-751128adb0a0","name":"a"}]"""
            )
        )
        assertEquals(ToolSurfaceMode.DIRECT, out.single().toolSurfaceMode)
    }

    @Test
    fun `v11 other fields survive the rewrite`() {
        val out = decode(
            migrateAssistantToolSurfaceToDirectJson(
                """[{"id":"3d47790c-c415-4b90-9388-751128adb0a0","name":"小明","systemPrompt":"be brief",
                   |"enableMemory":true,"toolSurfaceMode":"PROGRESSIVE_CATALOG"}]""".trimMargin()
            )
        ).single()
        assertEquals("小明", out.name)
        assertEquals("be brief", out.systemPrompt)
        assertEquals(true, out.enableMemory)
        assertEquals(ToolSurfaceMode.DIRECT, out.toolSurfaceMode)
    }

    @Test
    fun `v11 every assistant in the array is rewritten`() {
        val out = decode(
            migrateAssistantToolSurfaceToDirectJson(
                """[{"id":"3d47790c-c415-4b90-9388-751128adb0a0","toolSurfaceMode":"PROGRESSIVE_CATALOG"},
                   |{"id":"11111111-2222-3333-4444-555555555555","toolSurfaceMode":"PROGRESSIVE_CATALOG"}]""".trimMargin()
            )
        )
        assertEquals(2, out.size)
        out.forEach { assertEquals(ToolSurfaceMode.DIRECT, it.toolSurfaceMode) }
    }

    @Test
    fun `v11 an already direct array is returned untouched`() {
        val input = """[{"id":"3d47790c-c415-4b90-9388-751128adb0a0","toolSurfaceMode":"DIRECT"}]"""
        assertSame(input, migrateAssistantToolSurfaceToDirectJson(input))
    }

    @Test
    fun `v11 junk is returned untouched`() {
        assertSame("not json", migrateAssistantToolSurfaceToDirectJson("not json"))
        assertSame("{}", migrateAssistantToolSurfaceToDirectJson("{}"))
    }
}
