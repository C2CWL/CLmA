package com.clma.app.data.ai.tools

import com.clma.ai.core.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-05 (chat half) — unit tests for the per-turn surface holder.
 *
 * Everything here is pure JVM logic: no Android, no coroutines beyond `tool_open`'s own execute
 * lambda. The point of the class is that [ChatToolSurface.tools] can be re-asked between steps,
 * so the tests drive it exactly the way the generation loop does — snapshot, call `tool_open`,
 * snapshot again.
 */
class ChatToolSurfaceTest {

    private fun fakeTool(name: String): Tool = Tool(
        name = name,
        description = "test tool $name",
        execute = { emptyList() },
    )

    private fun catalogWith(vararg names: String, group: String = "volume") = ToolCatalog(
        names.map { name ->
            ToolCatalogEntry(
                name = name,
                summary = "summary for $name",
                source = ToolCatalogSource.LOCAL,
                tool = fakeTool(name),
                group = group,
            )
        },
    )

    @Test
    fun `a direct surface is the fixed list, by reference`() {
        val fixed = listOf(fakeTool("a"), fakeTool("b"))
        val surface = ChatToolSurface(fixed = fixed)
        assertFalse(surface.isProgressive)
        assertSame(fixed, surface.tools())
        assertNull(surface.schemaForUnloadedTool("a"))
    }

    @Test
    fun `a progressive surface starts with the discovery pair only`() {
        val surface = ChatToolSurface(
            fixed = listOf(fakeTool("core")),
            catalog = catalogWith("set_volume"),
            activation = ToolActivationState(),
        )
        assertEquals(listOf("core", "tool_search", "tool_open", "tool_close"), surface.tools().map { it.name })
    }

    @Test
    fun `activation lands in the very next snapshot`() = runBlocking {
        val surface = ChatToolSurface(
            fixed = listOf(fakeTool("core")),
            catalog = catalogWith("set_volume", "get_volume"),
            activation = ToolActivationState(),
        )
        assertFalse(surface.tools().any { it.name == "set_volume" })

        // Exactly what the loop does with a tool_open call: resolve it from the current snapshot,
        // run it, then ask for the list again for the next request.
        val open = surface.tools().first { it.name == "tool_open" }
        open.execute(buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive("set_volume")))) })

        val next = surface.tools().map { it.name }
        assertTrue(next.contains("set_volume"))
        assertFalse(next.contains("get_volume"))
        // Order: fixed prefix, discovery pair, then activated schemas.
        assertEquals("set_volume", next.last())
    }

    @Test
    fun `an unopened catalogued tool exposes its schema for the interception envelope`() {
        val surface = ChatToolSurface(
            fixed = emptyList(),
            catalog = catalogWith("set_volume"),
            activation = ToolActivationState(),
        )
        val schema = surface.schemaForUnloadedTool("set_volume")
        assertTrue(schema!!.contains("set_volume"))
        assertNull(surface.schemaForUnloadedTool("not_catalogued"))
    }

    @Test
    fun `only ask_user is core among the local groups`() {
        assertEquals(setOf<LocalToolOption>(LocalToolOption.AskUser), CORE_LOCAL_TOOL_GROUPS)
    }

    @Test
    fun `category keys match the serialized group spelling`() {
        assertEquals("media_player", catalogCategoryKey(LocalToolOption.MediaPlayer))
        assertEquals("wifi_info", catalogCategoryKey(LocalToolOption.WifiInfo))
        assertEquals("js_skills", catalogCategoryKey(LocalToolOption.JsSkills))
        assertEquals("ssh", catalogCategoryKey(LocalToolOption.Ssh))
        assertEquals("ask_user", catalogCategoryKey(LocalToolOption.AskUser))
        assertEquals("mcp_control", catalogCategoryKey(LocalToolOption.McpControl))
        assertEquals("screen_automation", catalogCategoryKey(LocalToolOption.ScreenAutomation))
        assertEquals("virtual_display", catalogCategoryKey(LocalToolOption.VirtualDisplay))
    }
}