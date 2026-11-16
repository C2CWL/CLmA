package com.clma.llamacpp

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestMapperTest {

    @Test
    fun `images preserve their position and attachment order`() {
        val message = UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Text("read"), UIMessagePart.Image("file:///first.png"),
            UIMessagePart.Text("compare"), UIMessagePart.Image("content://second"),
        ))
        val request = JSONObject(ChatRequestMapper.toRequestJson(listOf(message), emptyList()))
        val content = request.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(4, content.length())
        assertEquals("media_marker", content.getJSONObject(1).getString("type"))
        assertEquals("<__media__>", content.getJSONObject(3).getString("text"))
        assertEquals(listOf("file:///first.png", "content://second"), ChatRequestMapper.imageUrls(listOf(message)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `assistant images fail rather than silently losing markers`() {
        ChatRequestMapper.imageUrls(listOf(UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Image("file:///image.png")))))
    }

    @Test
    fun `a user message becomes an openai shaped message`() {
        val json = JSONObject(
            ChatRequestMapper.toRequestJson(listOf(UIMessage.user("hello")), emptyList())
        )
        val messages = json.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("hello", messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun `no tools means no tools key rather than an empty array`() {
        // An empty tools array makes some templates emit a tool preamble for nothing.
        val json = JSONObject(
            ChatRequestMapper.toRequestJson(listOf(UIMessage.user("hi")), emptyList())
        )
        assertTrue("tools must be absent when none are declared", !json.has("tools"))
    }

    @Test
    fun `thinking can be switched off explicitly and stays unset by default`() {
        val defaultJson = JSONObject(
            ChatRequestMapper.toRequestJson(listOf(UIMessage.user("hi")), emptyList())
        )
        assertFalse("template default must be left alone", defaultJson.has("enable_thinking"))

        val off = JSONObject(
            ChatRequestMapper.toRequestJson(listOf(UIMessage.user("hi")), emptyList(), false)
        )
        assertFalse(off.getBoolean("enable_thinking"))
        assertEquals("hi", off.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun `tool declaration byte counts match the emitted json`() {
        // The planner budgets on these counts, so they must describe what is really sent.
        val tools = TestTools.two()
        val declarations = ChatRequestMapper.toolDeclarations(tools)
        val emitted = JSONObject(ChatRequestMapper.toRequestJson(listOf(UIMessage.user("x")), tools))
            .getJSONArray("tools")

        assertEquals(tools.size, declarations.size)
        repeat(emitted.length()) { i ->
            val actualBytes = emitted.getJSONObject(i).toString().toByteArray().size
            val claimed = declarations[i].jsonBytes
            // Both call sites run the same private toolsArray() builder over the same
            // tools list, so the true difference is always exactly 0, not merely close.
            // Exact equality catches a future regression that slack would hide, e.g. one
            // call site accidentally routed through a different JSON config.
            assertEquals(
                "declared $claimed bytes but emitted $actualBytes for ${declarations[i].name}",
                claimed,
                actualBytes,
            )
        }
    }

    @Test
    fun `text, an executed tool call, and more text become three ordered messages`() {
        // The tool result must sit between the two assistant turns, not after both, and
        // the two texts must not be glued together into a single assistant message.
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me check."),
                UIMessagePart.Tool(
                    toolCallId = "1",
                    toolName = "get_weather",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("sunny")),
                ),
                UIMessagePart.Text("It's sunny."),
            ),
        )

        val messages = JSONObject(
            ChatRequestMapper.toRequestJson(listOf(message), emptyList())
        ).getJSONArray("messages")

        assertEquals(3, messages.length())

        val firstTurn = messages.getJSONObject(0)
        assertEquals("assistant", firstTurn.getString("role"))
        assertEquals("Let me check.", firstTurn.getString("content"))
        val calls = firstTurn.getJSONArray("tool_calls")
        assertEquals(1, calls.length())
        assertEquals("1", calls.getJSONObject(0).getString("id"))
        assertEquals("function", calls.getJSONObject(0).getString("type"))
        val function = calls.getJSONObject(0).getJSONObject("function")
        assertEquals("get_weather", function.getString("name"))
        assertEquals("{}", function.getString("arguments"))

        val toolResult = messages.getJSONObject(1)
        assertEquals("tool", toolResult.getString("role"))
        assertEquals("get_weather", toolResult.getString("name"))
        assertEquals("1", toolResult.getString("tool_call_id"))
        assertEquals("sunny", toolResult.getString("content"))

        val secondTurn = messages.getJSONObject(2)
        assertEquals("assistant", secondTurn.getString("role"))
        assertEquals("It's sunny.", secondTurn.getString("content"))
        assertFalse("second turn called nothing", secondTurn.has("tool_calls"))
    }

    @Test
    fun `an unexecuted tool call is never sent as a tool_calls entry`() {
        // Pending (not-yet-approved) tool calls have no output yet. Sending one in
        // tool_calls with no matching tool message would leave an orphaned
        // tool_call_id, which a strict template rejects the whole conversation for.
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "1",
                    toolName = "get_weather",
                    input = "{}",
                    output = emptyList(),
                ),
            ),
        )

        val json = ChatRequestMapper.toRequestJson(listOf(message), emptyList())
        assertFalse("pending tool call must not appear in the wire format", json.contains("tool_calls"))
        assertFalse("pending tool call must not appear as a tool result", json.contains("\"role\":\"tool\""))
    }
}
