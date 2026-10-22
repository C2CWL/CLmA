package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.agentdef.AgentDefinition
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-06b — the two PURE halves of the expert write tools.
 *
 * The three tool factories themselves take a `SettingsStore` (Context-backed, no Robolectric in
 * this module) and an `AgentDefinitionRepository` (Room-backed), so their `execute` lambdas are
 * exercised on-device — same rule the MCP control tools follow. What is unit-testable is carved
 * out on purpose and pinned here:
 *
 *  - [encodeDefinition]: the model-visible shape of a stored expert. Omission is load-bearing —
 *    a field the expert left at "inherit the parent" must not come back as `null`, or a model
 *    echoing the object back would silently clear it — and the local-tool array must use the very
 *    same vocabulary as `Assistant.localTools`, since `AgentDefinitionConverters` reads the string
 *    form of exactly that array.
 *  - [findNameClash]: dispatch matches experts case-insensitively, so a second expert differing
 *    only by case makes every dispatch ambiguous. Create/update reject such a name before the
 *    store ever sees it, and this is the predicate that decides.
 */
class AgentDefinitionToolsTest {

    private fun definition(
        id: String = "def-1",
        name: String = "Researcher",
        description: String = "",
        systemPrompt: String = "",
        modelId: String? = null,
        enabled: Boolean = true,
        localTools: List<LocalToolOption>? = null,
        disabledLocalTools: Set<String>? = null,
        mcpServers: Set<String>? = null,
        skills: Set<String>? = null,
        slug: String? = null,
        tokenBudget: Long? = null,
    ) = AgentDefinition(
        id = id,
        name = name,
        description = description,
        systemPrompt = systemPrompt,
        modelId = modelId,
        enabled = enabled,
        localTools = localTools,
        disabledLocalTools = disabledLocalTools,
        mcpServers = mcpServers,
        skills = skills,
        slug = slug,
        tokenBudget = tokenBudget,
    )

    // ---- encodeDefinition ----------------------------------------------------------------

    @Test
    fun `an expert that sets nothing but its name encodes as identity plus enabled`() {
        val json = encodeDefinition(definition())
        assertEquals(setOf("id", "name", "description", "enabled"), json.keys)
        assertEquals("def-1", json["id"]?.jsonPrimitive?.content)
        assertEquals("Researcher", json["name"]?.jsonPrimitive?.content)
        assertEquals("", json["description"]?.jsonPrimitive?.content)
        assertTrue(json["enabled"]?.jsonPrimitive?.content?.toBoolean() == true)
    }

    @Test
    fun `inherit-the-parent fields are omitted rather than sent as null`() {
        // The whole point: a model that reads this object and echoes it back on a later write
        // must not turn "inherit" into "clear", and the reply stays as small as the expert is.
        val json = encodeDefinition(definition())
        listOf("model_id", "slug", "token_budget", "system_prompt", "local_tools", "disabled_local_tools")
            .forEach { key -> assertFalse("$key must be omitted when unset", json.containsKey(key)) }
        listOf("mcp_servers", "skills").forEach { key ->
            assertFalse("$key must be omitted when unset", json.containsKey(key))
        }
    }

    @Test
    fun `every field the expert did set is present`() {
        val json = encodeDefinition(
            definition(
                description = "digs things up",
                systemPrompt = "be thorough",
                modelId = "11111111-1111-1111-1111-111111111111",
                slug = "research",
                tokenBudget = 200_000,
                localTools = listOf(LocalToolOption.TimeInfo),
                disabledLocalTools = setOf("read_sensor"),
                mcpServers = setOf("22222222-2222-2222-2222-222222222222"),
                skills = setOf("skill-x"),
            ),
        )
        assertEquals("digs things up", json["description"]?.jsonPrimitive?.content)
        assertEquals("be thorough", json["system_prompt"]?.jsonPrimitive?.content)
        assertEquals("11111111-1111-1111-1111-111111111111", json["model_id"]?.jsonPrimitive?.content)
        assertEquals("research", json["slug"]?.jsonPrimitive?.content)
        assertEquals(200_000L, json["token_budget"]?.jsonPrimitive?.content?.toLong())
        assertEquals("read_sensor", json["disabled_local_tools"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("skill-x", json["skills"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("22222222-2222-2222-2222-222222222222", json["mcp_servers"]?.jsonArray?.single()?.jsonPrimitive?.content)
    }

    @Test
    fun `local tools encode with the same vocabulary Assistant localTools uses`() {
        // Ground truth shared with AgentDefinitionConvertersTest: the lenient serializer emits
        // the tool's @SerialName, and its output is what the store round-trips.
        val json = encodeDefinition(definition(localTools = listOf(LocalToolOption.TimeInfo)))
        assertEquals("""[{"type":"time_info"}]""", json["local_tools"]?.toString())
    }

    @Test
    fun `an explicitly empty collection survives as an empty array, not as omitted`() {
        // null means "inherit the parent"; empty means "this expert has none". The two must not
        // collapse into each other on the way out to the model.
        val json = encodeDefinition(definition(disabledLocalTools = emptySet()))
        assertTrue(json.containsKey("disabled_local_tools"))
        assertTrue(json["disabled_local_tools"]?.jsonArray?.isEmpty() == true)
    }

    @Test
    fun `collection members are sorted so the same expert always encodes byte-for-byte the same`() {
        val json = encodeDefinition(
            definition(
                disabledLocalTools = setOf("zebra", "alpha"),
                skills = setOf("s-b", "s-a"),
            ),
        )
        assertEquals(
            listOf("alpha", "zebra"),
            json["disabled_local_tools"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("s-a", "s-b"),
            json["skills"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    // ---- findNameClash -------------------------------------------------------------------

    @Test
    fun `a name clash is case-insensitive because dispatch resolves case-insensitively`() {
        val stored = definition(id = "def-1", name = "Researcher")
        val clash = findNameClash(listOf(stored), "researcher", exceptId = null)
        assertEquals(stored, clash)
    }

    @Test
    fun `an expert keeps its own name through an update`() {
        val stored = definition(id = "def-1", name = "Researcher")
        val other = definition(id = "def-2", name = "Writer")
        // Each keeps its own name...
        assertNull(findNameClash(listOf(stored, other), "Researcher", exceptId = "def-1"))
        assertNull(findNameClash(listOf(stored, other), "Writer", exceptId = "def-2"))
        // ...but somebody else may not take it, not even with different case.
        assertEquals(stored, findNameClash(listOf(stored, other), "Researcher", exceptId = "def-2"))
        assertEquals(stored, findNameClash(listOf(stored, other), "researcher", exceptId = "def-2"))
    }

    @Test
    fun `a disabled duplicate is still a clash`() {
        // A disabled row is not resolvable today, but it is one toggle away from making every
        // dispatch ambiguous. The settings screen always rejected it; so does this.
        val disabled = definition(id = "def-9", name = "Researcher", enabled = false)
        assertEquals(disabled, findNameClash(listOf(disabled), "Researcher", exceptId = null))
    }

    @Test
    fun `a blank stored name never clashes`() {
        val blank = definition(id = "def-1", name = "   ")
        assertNull(findNameClash(listOf(blank), "   ", exceptId = null))
        assertNull(findNameClash(listOf(blank), "", exceptId = null))
    }

    @Test
    fun `no clash against an empty library`() {
        assertNull(findNameClash(emptyList(), "Researcher", exceptId = null))
    }
}
