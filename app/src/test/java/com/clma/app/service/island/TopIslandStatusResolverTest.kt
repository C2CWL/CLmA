package com.clma.app.service.island

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopIslandStatusResolverTest {

    private fun user(text: String) = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))
    private fun assistant(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())
    private fun tool(name: String, done: Boolean) = UIMessagePart.Tool(
        toolCallId = name,
        toolName = name,
        input = "{}",
        output = if (done) listOf(UIMessagePart.Text("ok")) else emptyList(),
    )

    @Test
    fun `running tool wins and counts finished tools as steps`() {
        val status = TopIslandStatusResolver.resolve(
            listOf(
                user("hi"),
                assistant(tool("search_web", done = true), tool("fetch", done = true), tool("search_web", done = false)),
            )
        )
        assertEquals(TopIslandPhase.Tool("search_web"), status.phase)
        assertEquals(3, status.step)
    }

    @Test
    fun `open reasoning is thinking`() {
        val status = TopIslandStatusResolver.resolve(
            listOf(user("hi"), assistant(UIMessagePart.Reasoning(reasoning = "hmm", finishedAt = null)))
        )
        assertEquals(TopIslandPhase.Thinking, status.phase)
        assertEquals("hmm", status.preview)
        assertEquals(1, status.step)
    }

    @Test
    fun `text is writing and only the current turn counts`() {
        val status = TopIslandStatusResolver.resolve(
            listOf(
                user("first"),
                assistant(tool("a", done = true), UIMessagePart.Text("old")),
                user("second"),
                assistant(UIMessagePart.Text("new  reply\ntext")),
            )
        )
        assertEquals(TopIslandPhase.Writing, status.phase)
        assertEquals("new reply text", status.preview)
        assertEquals(1, status.step)
    }

    @Test
    fun `empty turn is thinking`() {
        val status = TopIslandStatusResolver.resolve(listOf(user("hi")))
        assertEquals(TopIslandPhase.Thinking, status.phase)
    }

    @Test
    fun `long preview keeps the tail`() {
        val long = "x".repeat(500)
        val status = TopIslandStatusResolver.resolve(listOf(user("hi"), assistant(UIMessagePart.Text(long))))
        assertTrue(status.preview.startsWith("…"))
        assertEquals(161, status.preview.length)
    }

    @Test
    fun `mcp names are stripped`() {
        assertEquals("read", TopIslandStatusResolver.displayToolName("mcp__srv__read"))
        assertEquals("search_web", TopIslandStatusResolver.displayToolName("search_web"))
    }

    @Test
    fun `elapsed formatting`() {
        assertEquals("0:00", TopIslandStatusResolver.formatElapsed(-5))
        assertEquals("0:24", TopIslandStatusResolver.formatElapsed(24_900))
        assertEquals("1:05", TopIslandStatusResolver.formatElapsed(65_000))
        assertEquals("1:00:01", TopIslandStatusResolver.formatElapsed(3_601_000))
    }
}
