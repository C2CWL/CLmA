package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-03 / (1) - unit tests for the progressive tool catalog.
 *
 * Everything here is pure JVM logic: the catalog, the activation bookkeeping and the two tool
 * builders must not touch Android, so they can be asserted directly without an emulator.
 */
class ToolCatalogTest {

    private fun fakeTool(name: String, description: String = "test tool"): Tool = Tool(
        name = name,
        description = description,
        execute = { emptyList() },
    )

    private fun entry(
        name: String,
        summary: String = "summary for $name",
        source: ToolCatalogSource = ToolCatalogSource.MCP,
    ) = ToolCatalogEntry(
        name = name,
        summary = summary,
        source = source,
        tool = fakeTool(name),
    )

    private fun catalogOf(vararg names: String) = ToolCatalog(names.map { entry(it) })

    // ---------------------------------------------------------------- search

    @Test
    fun `blank query returns nothing`() {
        val catalog = catalogOf("mcp__vps__compute", "mcp__vps__file")
        assertTrue(catalog.search("").isEmpty())
        assertTrue(catalog.search("   ").isEmpty())
    }

    @Test
    fun `exact name match outranks a substring match`() {
        val catalog = catalogOf("mcp__vps__file", "mcp__vps__file_read")
        val hits = catalog.search("mcp__vps__file")
        assertEquals("mcp__vps__file", hits.first().name)
    }

    @Test
    fun `name hits outrank summary only hits`() {
        val catalog = ToolCatalog(
            listOf(
                entry("aaa_first", summary = "nothing relevant here"),
                entry("zzz_second", summary = "talks about logcat output"),
            )
        )
        val hits = catalog.search("logcat")
        assertEquals(1, hits.size)
        assertEquals("zzz_second", hits.first().name)
    }

    @Test
    fun `search is capped at the documented maximum`() {
        val catalog = ToolCatalog((1..20).map { entry("tool_alpha_$it") })
        assertTrue(catalog.search("alpha").size <= 8)
    }

    @Test
    fun `limit is clamped to the maximum`() {
        val catalog = ToolCatalog((1..20).map { entry("tool_alpha_$it") })
        assertEquals(8, catalog.search("alpha", limit = 100).size)
    }

    @Test
    fun `ties are broken deterministically by name`() {
        val catalog = catalogOf("tool_bbb", "tool_aaa")
        val hits = catalog.search("tool")
        assertEquals(listOf("tool_aaa", "tool_bbb"), hits.map { it.name })
    }

    @Test
    fun `search never exposes a schema`() {
        val catalog = catalogOf("mcp__vps__compute")
        val outcome = ToolSearchOutcome(
            hits = catalog.search("compute").map {
                ToolSearchHit(it.name, it.summary, it.source)
            },
            total = 1,
            truncated = false,
        )
        val json = outcome.toJson()
        assertFalse(json.contains("parameters"))
        assertFalse(json.contains("schema"))
        assertTrue(json.contains("mcp__vps__compute"))
    }

    @Test
    fun `search outcome reports total and truncation`() {
        val outcome = ToolSearchOutcome(hits = emptyList(), total = 12, truncated = true)
        val json = outcome.toJson()
        assertTrue(json.contains("\"total\":12"))
        assertTrue(json.contains("\"truncated\":true"))
    }

    @Test
    fun `catalog exposes size and lookup`() {
        val catalog = catalogOf("a_tool", "b_tool")
        assertEquals(2, catalog.size)
        assertNotNull(catalog.entry("a_tool"))
        assertNull(catalog.entry("nope"))
    }

    // ------------------------------------------------------------ activation

    @Test
    fun `activate reports unknown names without activating anything`() {
        val catalog = catalogOf("known_tool")
        val activation = ToolActivationState()
        val outcome = activation.activate(catalog, listOf("ghost_tool"))
        assertTrue(outcome.activated.isEmpty())
        assertEquals(listOf("ghost_tool"), outcome.unknown)
        assertEquals(0, outcome.activeCount)
        assertTrue(activation.active().isEmpty())
    }

    @Test
    fun `activate caps the number of new schemas per call`() {
        val catalog = ToolCatalog((1..8).map { entry("t_$it") })
        val activation = ToolActivationState()
        val outcome = activation.activate(catalog, (1..8).map { "t_$it" })
        assertEquals(4, outcome.activated.size)
        assertEquals(4, outcome.rejectedOverCap.size)
        assertEquals(4, outcome.activeCount)
    }

    @Test
    fun `activate caps the total number of active schemas`() {
        val catalog = ToolCatalog((1..10).map { entry("t_$it") })
        val activation = ToolActivationState()
        activation.activate(catalog, listOf("t_1", "t_2", "t_3", "t_4"))
        val outcome = activation.activate(catalog, listOf("t_5", "t_6", "t_7"))
        assertEquals(listOf("t_5", "t_6"), outcome.activated)
        assertEquals(listOf("t_7"), outcome.rejectedOverCap)
        assertEquals(6, outcome.activeCount)
    }

    @Test
    fun `already active names are reported, not re-activated`() {
        val catalog = catalogOf("t_1")
        val activation = ToolActivationState()
        activation.activate(catalog, listOf("t_1"))
        val outcome = activation.activate(catalog, listOf("t_1"))
        assertTrue(outcome.activated.isEmpty())
        assertEquals(listOf("t_1"), outcome.alreadyActive)
        assertEquals(1, outcome.activeCount)
    }

    @Test
    fun `activation order is the caller's order`() {
        val catalog = catalogOf("t_1", "t_2", "t_3")
        val activation = ToolActivationState()
        activation.activate(catalog, listOf("t_3", "t_1", "t_2"))
        assertEquals(listOf("t_3", "t_1", "t_2"), activation.active().toList())
    }

    @Test
    fun `retain drops names that left the catalog`() {
        val catalog = catalogOf("t_1", "t_2")
        val activation = ToolActivationState()
        activation.activate(catalog, listOf("t_1", "t_2"))
        activation.retain(setOf("t_2"))
        assertEquals(listOf("t_2"), activation.active().toList())
    }

    @Test
    fun `clear empties the activation set`() {
        val catalog = catalogOf("t_1")
        val activation = ToolActivationState()
        activation.activate(catalog, listOf("t_1"))
        activation.clear()
        assertTrue(activation.active().isEmpty())
    }

    @Test
    fun `open outcome always serialises every list`() {
        val outcome = ToolOpenOutcome(
            activated = listOf("a"),
            alreadyActive = emptyList(),
            unknown = listOf("b"),
            rejectedOverCap = emptyList(),
            activeCount = 1,
        )
        val json = outcome.toJson()
        assertTrue(json.contains("\"alreadyActive\":[]"))
        assertTrue(json.contains("\"rejectedOverCap\":[]"))
        assertTrue(json.contains("\"activeCount\":1"))
    }

    @Test
    fun `open outcome tells the model activation lands next turn`() {
        val outcome = ToolOpenOutcome(
            activated = listOf("a"),
            alreadyActive = emptyList(),
            unknown = emptyList(),
            rejectedOverCap = emptyList(),
            activeCount = 1,
        )
        assertTrue(outcome.toJson().contains("NEXT turn"))
    }

    // ------------------------------------------------------------ tool wiring

    @Test
    fun `factory returns the search and open tools`() {
        val tools = buildToolCatalogTools(catalogOf("t_1"), ToolActivationState())
        assertEquals(listOf("tool_search", "tool_open"), tools.map { it.name })
    }

    @Test
    fun `catalog tools do not require approval`() {
        val tools = buildToolCatalogTools(catalogOf("t_1"), ToolActivationState())
        assertTrue(tools.none { it.needsApproval(JsonNull) })
    }

    @Test
    fun `tool_search execute returns hits for a matching query`() = runBlocking {
        val tools = buildToolCatalogTools(catalogOf("mcp__vps__compute"), ToolActivationState())
        val search = tools.first { it.name == "tool_search" }
        val parts = search.execute(buildJsonObject { put("query", "compute") })
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue(text.contains("mcp__vps__compute"))
    }

    @Test
    fun `tool_search tolerates a missing query`() = runBlocking {
        val tools = buildToolCatalogTools(catalogOf("t_1"), ToolActivationState())
        val search = tools.first { it.name == "tool_search" }
        val parts = search.execute(JsonObject(emptyMap()))
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue(text.contains("\"returned\":0"))
    }

    @Test
    fun `tool_open activates the requested names`() = runBlocking {
        val activation = ToolActivationState()
        val tools = buildToolCatalogTools(catalogOf("t_1", "t_2"), activation)
        val open = tools.first { it.name == "tool_open" }
        val parts = open.execute(
            buildJsonObject {
                put("names", JsonArray(listOf(JsonPrimitive("t_1"))))
            }
        )
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue(text.contains("t_1"))
        assertEquals(listOf("t_1"), activation.active().toList())
    }

    @Test
    fun `tool_open without names activates nothing`() = runBlocking {
        val activation = ToolActivationState()
        val tools = buildToolCatalogTools(catalogOf("t_1"), activation)
        val open = tools.first { it.name == "tool_open" }
        val parts = open.execute(JsonObject(emptyMap()))
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue(activation.active().isEmpty())
        assertTrue(text.contains("tool_search"))
    }

    @Test
    fun `tool_search accepts a json result that can be parsed back`() = runBlocking {
        val tools = buildToolCatalogTools(catalogOf("mcp__vps__compute"), ToolActivationState())
        val search = tools.first { it.name == "tool_search" }
        val text = (search.execute(buildJsonObject { put("query", "compute") }).single() as UIMessagePart.Text).text
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
        assertEquals(1, parsed["total"]?.jsonPrimitive?.content?.toInt())
        val results = parsed["results"] as JsonArray
        assertEquals("mcp__vps__compute", results.single().jsonObject["name"]?.jsonPrimitive?.content)
    }
}
