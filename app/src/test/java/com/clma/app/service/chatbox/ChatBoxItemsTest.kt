package com.clma.app.service.chatbox

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBoxItemsTest {

    private fun msg(role: MessageRole, vararg parts: UIMessagePart) = UIMessage(role = role, parts = parts.toList())
    private fun tool(name: String, done: Boolean) = UIMessagePart.Tool(
        toolCallId = name,
        toolName = name,
        input = "{}",
        output = if (done) listOf(UIMessagePart.Text("ok")) else emptyList(),
    )

    @Test
    fun `tool calls split the assistant text into bubbles and chips in order`() {
        val items = ChatBoxItems.build(
            listOf(
                msg(MessageRole.SYSTEM, UIMessagePart.Text("system prompt")),
                msg(MessageRole.USER, UIMessagePart.Text("hi"), UIMessagePart.Image("file://a.png")),
                msg(
                    MessageRole.ASSISTANT,
                    UIMessagePart.Reasoning(reasoning = "hmm", finishedAt = null),
                    UIMessagePart.Text("let me look"),
                    tool("search_web", done = true),
                    tool("fetch", done = false),
                    UIMessagePart.Text("found it"),
                ),
            )
        )
        assertEquals(
            listOf(
                ChatBoxItem.Bubble(fromUser = true, text = "hi", images = 1),
                ChatBoxItem.Bubble(fromUser = false, text = "let me look"),
                ChatBoxItem.ToolChip("search_web", done = true),
                ChatBoxItem.ToolChip("fetch", done = false),
                ChatBoxItem.Bubble(fromUser = false, text = "found it"),
            ),
            items,
        )
    }

    @Test
    fun `only the newest rows are kept and long text keeps its end`() {
        val many = (1..50).map { msg(MessageRole.USER, UIMessagePart.Text("m$it")) }
        val items = ChatBoxItems.build(many)
        assertEquals(ChatBoxItems.MAX_ITEMS, items.size)
        assertEquals(ChatBoxItem.Bubble(true, "m50"), items.last())

        val long = "x".repeat(ChatBoxItems.MAX_BUBBLE_CHARS) + "END"
        val bubble = ChatBoxItems.build(listOf(msg(MessageRole.ASSISTANT, UIMessagePart.Text(long)))).single()
        assertTrue((bubble as ChatBoxItem.Bubble).text.endsWith("END"))
        assertTrue(bubble.text.startsWith("…"))
    }

    @Test
    fun `empty assistant placeholder shows nothing`() {
        val items = ChatBoxItems.build(listOf(msg(MessageRole.ASSISTANT, UIMessagePart.Text(""))))
        assertTrue(items.isEmpty())
    }
}
