package me.rerere.ai.provider.stream

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for models that emit tool calls as literal text rather than as native
 * `tool_calls` deltas — seen both with Gemini Nano on-device and with the MiniMax-based free tier
 * behind an OpenAI-compatible gateway, where the raw `<tool_call>` markup was shown in the chat and
 * the tool was never executed.
 *
 * The first draft of this feature came from a community PR (#131); these tests keep its cases and
 * add the end-of-stream, disabled-fallback and empty-offer paths.
 */
class TextToolCallParserTest {

    private val echo = Tool(
        name = "agent-core",
        description = "core",
        parameters = {
            InputSchema.Obj(
                properties = JsonObject(mapOf("task" to JsonPrimitive("string"))),
                required = listOf("task"),
            )
        },
        execute = { emptyList() },
    )

    private val agent = Tool(
        name = "autonomous-agent",
        description = "agent",
        parameters = {
            InputSchema.Obj(
                properties = JsonObject(mapOf("goal" to JsonPrimitive("string"))),
                required = listOf("goal"),
            )
        },
        execute = { emptyList() },
    )

    /**
     * Feed the deltas then flush the tail, mirroring how the decoders drain the parser at end of
     * stream — the last few characters stay buffered in case they are a partial tag.
     */
    private fun parts(parser: TextToolCallParser, vararg deltas: String): List<UIMessagePart> {
        val out = deltas.flatMap { parser.feed(it) }.toMutableList()
        out += parser.flushPending()
        return out
    }

    private fun toolCalls(out: List<UIMessagePart>) = out.filterIsInstance<UIMessagePart.Tool>()

    private fun text(out: List<UIMessagePart>) =
        out.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    @Test
    fun `parses a well formed text tool call`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(
            parser,
            "Let me check that. <tool_call>{\"name\":\"agent-core\",\"input\":{\"task\":\"battery\"}}</tool_call>"
        )
        val tool = toolCalls(out).single()
        assertEquals("agent-core", tool.toolName)
        assertTrue(tool.input.contains("\"task\":\"battery\""))
        assertEquals("tool_calls", parser.consumePendingFinishReason())
    }

    @Test
    fun `keeps the prose around the call`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(
            parser,
            "Checking now. <tool_call>{\"name\":\"agent-core\",\"input\":{\"task\":\"x\"}}</tool_call> Done."
        )
        assertEquals("Checking now.  Done.", text(out))
    }

    @Test
    fun `survives a tag split across stream chunks`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(
            parser,
            "<tool_", "call>{\"name\":\"agent-core\",", "\"input\":{\"task\":\"x\"}}</tool_", "call>"
        )
        assertEquals("agent-core", toolCalls(out).single().toolName)
    }

    @Test
    fun `handles zero width characters inside the tags`() {
        val parser = TextToolCallParser(listOf(echo))
        // These models sometimes wedge U+200B into the tag itself; matching must survive it.
        val zwsp = "\u200b"
        val out = parts(
            parser,
            "<${zwsp}tool_call>{\"name\":\"agent-core\",\"input\":{\"task\":\"y\"}}</${zwsp}tool_call>"
        )
        assertEquals("agent-core", toolCalls(out).single().toolName)
    }

    @Test
    fun `streams plain prose untouched when there is no tool call`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(parser, "Hello there, general Kenobi")
        assertEquals("Hello there, general Kenobi", text(out))
        assertTrue(toolCalls(out).isEmpty())
        assertNull(parser.consumePendingFinishReason())
    }

    @Test
    fun `refuses a tool that was never offered`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(parser, "<tool_call>{\"name\":\"rm-rf-slash\",\"input\":{}}</tool_call>")
        // Not dispatched — surfaced as visible text instead.
        assertTrue(toolCalls(out).isEmpty())
        assertTrue(text(out).contains("rm-rf-slash"))
        assertNull(parser.consumePendingFinishReason())
    }

    @Test
    fun `parses nothing at all when no tools were offered`() {
        // This is how the decoders implement the user-facing off switch.
        val parser = TextToolCallParser(emptyList())
        val out = parts(parser, "<tool_call>{\"name\":\"agent-core\",\"input\":{\"task\":\"x\"}}</tool_call>")
        assertTrue(toolCalls(out).isEmpty())
        assertTrue(text(out).contains("agent-core"))
        assertNull(parser.consumePendingFinishReason())
    }

    @Test
    fun `parses the arguments key as well as input`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(parser, "<tool_call>{\"name\":\"agent-core\",\"arguments\":{\"task\":\"z\"}}</tool_call>")
        assertEquals("agent-core", toolCalls(out).single().toolName)
    }

    @Test
    fun `nests a primitive input under the first required parameter`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(parser, "<tool_call>{\"name\":\"agent-core\",\"input\":\"battery level\"}</tool_call>")
        val tool = toolCalls(out).single()
        assertTrue(tool.input.contains("\"task\""))
        assertTrue(tool.input.contains("battery level"))
    }

    @Test
    fun `repairs a missing closing brace`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(parser, "<tool_call>{\"name\":\"agent-core\",\"input\":{\"task\":\"x\"}</tool_call>")
        assertEquals("agent-core", toolCalls(out).single().toolName)
        val tool = toolCalls(out).single()
        assertTrue(tool.input.contains("\"task\""))
    }

    @Test
    fun `parses two calls in one block`() {
        val parser = TextToolCallParser(listOf(echo, agent))
        val out = parts(
            parser,
            "<tool_call>{\"name\":\"agent-core\",\"input\":{\"task\":\"1\"}}</tool_call>" +
                "<tool_call>{\"name\":\"autonomous-agent\",\"input\":{\"goal\":\"2\"}}</tool_call>"
        )
        assertEquals(
            listOf("agent-core", "autonomous-agent"),
            toolCalls(out).map { it.toolName }
        )
    }

    @Test
    fun `shows malformed markup as text rather than swallowing it`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(parser, "<tool_call>not json at all</tool_call>")
        assertTrue(toolCalls(out).isEmpty())
        assertTrue(text(out).contains("not json"))
    }

    @Test
    fun `a plain delta is emitted whole rather than sliced at the tail`() {
        // Regression: the first version withheld a fixed number of trailing characters, so an
        // ordinary answer arrived split in two — and when the next thing was a structured tool
        // call, the withheld tail landed *after* that tool part (caught by the recorded
        // DeepSeek/OpenRouter stream traces).
        val parser = TextToolCallParser(listOf(echo))
        val prose = "I'll research each of these technologies independently."
        val out = parser.feed(prose)
        assertEquals(1, out.size)
        assertEquals(prose, (out.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `a stray angle bracket does not hold the stream back`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parser.feed("a < b and c > d")
        assertEquals("a < b and c > d", text(out))
    }

    @Test
    fun `an unterminated tag is flushed as text at end of stream`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(parser, "working <tool_call>{\"name\":\"agent-core\",\"input\":{\"task\":\"x\"}}")
        assertTrue(toolCalls(out).isEmpty())
        assertEquals("working <tool_call>{\"name\":\"agent-core\",\"input\":{\"task\":\"x\"}}", text(out))
    }

    @Test
    fun `a partial opening tag at end of stream is not lost`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(parser, "hello <tool_")
        assertEquals("hello <tool_", text(out))
    }
}
