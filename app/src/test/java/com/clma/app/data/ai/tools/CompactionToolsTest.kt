package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-02 / ② — regression tests for the `compact_context` tool surface.
 *
 * The tool deliberately carries no compaction logic of its own (that lives in ChatService and
 * the shared ContextCompactionPlanner), so what is worth pinning down here is the *contract*:
 * the tool name the model has to emit, the argument schema, how a missing/blank `instructions`
 * argument is normalised, and the JSON result envelope.
 */
class CompactionToolsTest {

    private fun buildTool(
        onCompact: suspend (String?) -> CompactionToolResult,
    ): Tool = buildCompactionTools(onCompact).single()

    private fun objSchema(tool: Tool): InputSchema.Obj = tool.parameters() as InputSchema.Obj

    private fun executeJson(tool: Tool, args: String): String = runBlocking {
        val parts = tool.execute(Json.parseToJsonElement(args))
        (parts.single() as UIMessagePart.Text).text
    }

    @Test
    fun `tool is named compact_context`() {
        val tool = buildTool { CompactionToolResult(true, 100, 10, 5) }
        assertEquals("compact_context", tool.name)
    }

    @Test
    fun `instructions is an optional string parameter`() {
        val tool = buildTool { CompactionToolResult(true, 100, 10, 5) }
        val schema = objSchema(tool)

        val instructions = schema.properties["instructions"]?.jsonObject
        assertTrue("instructions property must exist", instructions != null)
        assertEquals("string", instructions!!["type"]!!.jsonPrimitive.content)

        // Not required: the whole point is that `compact_context` works with no arguments.
        assertTrue(
            "instructions must not be required",
            schema.required.isNullOrEmpty(),
        )
    }

    @Test
    fun `description tells the model the effect lands next turn and nothing is deleted`() {
        val tool = buildTool { CompactionToolResult(true, 100, 10, 5) }
        assertTrue(tool.description.contains("NEXT turn"))
        assertTrue(tool.description.contains("NOT deleted"))
    }

    @Test
    fun `omitted instructions reaches the handler as null`() {
        var seen: String? = "sentinel"
        val tool = buildTool { instructions ->
            seen = instructions
            CompactionToolResult(true, 10, 5, 1)
        }

        executeJson(tool, "{}")

        assertNull(seen)
    }

    @Test
    fun `blank instructions is normalised to null`() {
        var seen: String? = "sentinel"
        val tool = buildTool { instructions ->
            seen = instructions
            CompactionToolResult(true, 10, 5, 1)
        }

        executeJson(tool, """{"instructions":"   \n\t  "}""")

        assertNull(seen)
    }

    @Test
    fun `non blank instructions is passed through trimmed`() {
        var seen: String? = null
        val tool = buildTool { instructions ->
            seen = instructions
            CompactionToolResult(true, 10, 5, 1)
        }

        executeJson(tool, """{"instructions":"  keep the failing command  "}""")

        assertEquals("keep the failing command", seen)
    }

    @Test
    fun `success result is returned as a json text part`() {
        val tool = buildTool { CompactionToolResult(true, 1200, 300, 42, note = "next turn") }

        val json = Json.parseToJsonElement(executeJson(tool, "{}")).jsonObject

        assertTrue(json["compacted"]!!.jsonPrimitive.boolean)
        assertEquals(1200, json["tokensBefore"]!!.jsonPrimitive.int)
        assertEquals(300, json["tokensAfter"]!!.jsonPrimitive.int)
        assertEquals(42, json["summaryChars"]!!.jsonPrimitive.int)
        assertEquals("next turn", json["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun `blank note is omitted from the envelope`() {
        val tool = buildTool { CompactionToolResult(false, 50, 50, 0, note = "") }

        val json = Json.parseToJsonElement(executeJson(tool, "{}")).jsonObject

        assertFalse(json["compacted"]!!.jsonPrimitive.boolean)
        assertFalse("blank note must be omitted", json.containsKey("note"))
    }

    @Test
    fun `handler exception propagates so the loop can emit tool_failed`() {
        val tool = buildTool { error("no compression model configured") }

        val thrown = runCatching { executeJson(tool, "{}") }

        assertTrue(thrown.isFailure)
        assertEquals("no compression model configured", thrown.exceptionOrNull()?.message)
    }

    @Test
    fun `tool does not require approval`() {
        // Compaction only rewrites the request context; the original messages stay stored.
        val tool = buildTool { CompactionToolResult(true, 1, 1, 1) }
        assertFalse(tool.needsApproval(JsonNull))
    }

    @Test
    fun `factory returns exactly one tool`() {
        val tools = buildCompactionTools { CompactionToolResult(true, 1, 1, 1) }
        assertEquals(1, tools.size)
        assertEquals("compact_context", tools.single().name)
    }
}
