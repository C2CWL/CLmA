package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-36 — the two PURE halves of the expert tool-surface write path this card adds.
 *
 * The tool factories themselves take a `SettingsStore` (Context-backed) and a Room repository, so
 * their `execute` lambdas are exercised on-device — the same line P2-06b drew. What is carved out
 * on purpose, and pinned here, is:
 *
 *  - [JsonObject.fieldUpdate]: the tri-state reader. `null` = inherit the parent and `[]` =
 *    own-empty are *different* surfaces ([SubAgentSurface.hasOwnSurface] treats them differently),
 *    and an absent key means "leave it alone" on update. Collapsing any two of these would put
 *    the P2-06 bug back: an expert that owns no tools would start inheriting the parent's.
 *  - [parseLocalTools] / [parseStringSet] / [parseMcpServers]: model input → the store's canonical
 *    shapes (a canonicalised, de-duplicated [LocalToolOption] list; sorted sets; MCP Uuids).
 */
class AgentDefinitionSurfaceFieldsTest {

    // ---- fieldUpdate: absent / null / array ----------------------------------------------

    @Test
    fun `absent key is Unchanged, explicit null is Inherit, an array is Own`() {
        val params = buildJsonObject {
            put("present", buildJsonArray { add("x") })
            put("nulled", JsonNull)
        }
        assertTrue(params.fieldUpdate("missing") is FieldUpdate.Unchanged)
        assertTrue(params.fieldUpdate("nulled") is FieldUpdate.Inherit)
        assertTrue(params.fieldUpdate("present") is FieldUpdate.Own)
    }

    // ---- local_tools ---------------------------------------------------------------------

    @Test
    fun `local_tools accept string ids and canonicalise to LocalToolGroups order`() {
        val parsed = parseLocalTools(
            buildJsonArray {
                add("screen_automation")
                add("time_info")
            },
        ).getOrThrow()
        assertEquals(listOf(LocalToolOption.TimeInfo, LocalToolOption.ScreenAutomation), parsed)
    }

    @Test
    fun `local_tools accept the object form the encoder emits`() {
        val parsed = parseLocalTools(
            buildJsonArray { add(buildJsonObject { put("type", "time_info") }) },
        ).getOrThrow()
        assertEquals(listOf(LocalToolOption.TimeInfo), parsed)
    }

    @Test
    fun `local_tools deduplicate`() {
        val parsed = parseLocalTools(
            buildJsonArray {
                add("time_info")
                add("time_info")
            },
        ).getOrThrow()
        assertEquals(listOf(LocalToolOption.TimeInfo), parsed)
    }

    @Test
    fun `an empty local_tools array is an own-empty surface, not a failure`() {
        assertEquals(emptyList<LocalToolOption>(), parseLocalTools(buildJsonArray {}).getOrThrow())
    }

    @Test
    fun `an unknown local tool id fails and the message lists the valid ids`() {
        val failure = parseLocalTools(buildJsonArray { add("not_a_tool") }).exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("time_info"))
    }

    @Test
    fun `the valid id vocabulary includes the ids the schema advertises`() {
        assertTrue(VALID_LOCAL_TOOL_IDS.contains("time_info"))
        assertTrue(VALID_LOCAL_TOOL_IDS.contains("screen_automation"))
    }

    // ---- string sets ---------------------------------------------------------------------

    @Test
    fun `a string set sorts and rejects non-strings`() {
        assertEquals(
            setOf("alpha", "zebra"),
            parseStringSet(
                buildJsonArray {
                    add("zebra")
                    add("alpha")
                },
                "skills",
            ).getOrThrow(),
        )
        assertTrue(parseStringSet(buildJsonArray { add(1) }, "skills").isFailure)
    }

    // ---- mcp server resolution -----------------------------------------------------------

    @Test
    fun `an unresolved mcp server fails and says none are configured`() {
        val failure = parseMcpServers(buildJsonArray { add("nope") }, emptyList()).exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("none configured"))
    }
}
