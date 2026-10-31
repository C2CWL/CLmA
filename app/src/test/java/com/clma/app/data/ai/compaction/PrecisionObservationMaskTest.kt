package com.clma.app.data.ai.compaction

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecisionObservationMaskTest {
    private val longOutput = "HEAD-" + "x".repeat(2_000) + "-TAIL"

    @Test
    fun `disabled returns the same list`() {
        val messages = listOf(assistant(read("a"), read("b"), read("c")))
        assertSame(messages, PrecisionObservationMask.apply(messages, enabled = false))
    }

    @Test
    fun `five reads keep the latest two and stub the older long outputs`() {
        val original = assistant(
            read("1"),
            read("2"),
            read("3"),
            read("4"),
            read("5"),
        )
        val masked = PrecisionObservationMask.apply(listOf(original), enabled = true).single()

        assertEquals(longOutput, textOf(original.parts[0]))
        assertStubbed(masked.parts[0])
        assertStubbed(masked.parts[1])
        assertStubbed(masked.parts[2])
        assertSame(original.parts[3], masked.parts[3])
        assertSame(original.parts[4], masked.parts[4])
        val stub = textOf(masked.parts[0])
        assertTrue(stub.contains("call_id=call-1"))
        assertTrue(stub.contains("\"path\":\"/tmp/1.txt\""))
        assertTrue(stub.contains("head:\nHEAD-"))
        assertTrue(stub.contains("-TAIL"))
    }

    @Test
    fun `a write between old reads stays verbatim`() {
        val original = assistant(
            read("1"),
            write("edit"),
            read("3"),
            read("4"),
            read("5"),
        )
        val masked = PrecisionObservationMask.apply(listOf(original), enabled = true).single()

        assertStubbed(masked.parts[0])
        assertSame(original.parts[1], masked.parts[1])
        assertStubbed(masked.parts[2])
        assertSame(original.parts[3], masked.parts[3])
        assertSame(original.parts[4], masked.parts[4])
    }

    @Test
    fun `shell and a posting web fetch are not masked`() {
        val original = assistant(
            shell("sh"),
            webFetch("post", """{"url":"https://example.com","method":"POST"}"""),
            read("3"),
            read("4"),
            read("5"),
        )
        val masked = PrecisionObservationMask.apply(listOf(original), enabled = true).single()

        assertSame(original.parts[0], masked.parts[0])
        assertSame(original.parts[1], masked.parts[1])
        assertStubbed(masked.parts[2])
    }

    @Test
    fun `an old get web fetch is masked`() {
        val original = assistant(
            webFetch("get", """{"url":"https://example.com"}"""),
            read("2"),
            read("3"),
        )
        val masked = PrecisionObservationMask.apply(listOf(original), enabled = true).single()

        assertStubbed(masked.parts[0])
        assertTrue(textOf(masked.parts[0]).contains("tool=web_fetch"))
    }

    @Test
    fun `short text and non-text output stay verbatim`() {
        val image = UIMessagePart.Tool(
            toolCallId = "img",
            toolName = "workspace_read_file",
            input = """{"path":"/tmp/a.png"}""",
            output = listOf(UIMessagePart.Image("file:///tmp/a.png"), UIMessagePart.Text(longOutput)),
        )
        val short = UIMessagePart.Tool(
            toolCallId = "short",
            toolName = "workspace_read_file",
            input = """{"path":"/tmp/short.txt"}""",
            output = listOf(UIMessagePart.Text("ok")),
        )
        val original = assistant(image, short, read("3"), read("4"))
        val masked = PrecisionObservationMask.apply(listOf(original), enabled = true).single()

        assertSame(original.parts[0], masked.parts[0])
        assertSame(original.parts[1], masked.parts[1])
    }

    private fun read(id: String) = UIMessagePart.Tool(
        toolCallId = "call-$id",
        toolName = "workspace_read_file",
        input = """{"path":"/tmp/$id.txt"}""",
        output = listOf(UIMessagePart.Text(longOutput)),
    )

    private fun write(id: String) = UIMessagePart.Tool(
        toolCallId = "call-$id",
        toolName = "workspace_edit_file",
        input = """{"path":"/tmp/$id.txt"}""",
        output = listOf(UIMessagePart.Text(longOutput)),
    )

    private fun shell(id: String) = UIMessagePart.Tool(
        toolCallId = "call-$id",
        toolName = "workspace_shell",
        input = """{"command":"ls"}""",
        output = listOf(UIMessagePart.Text(longOutput)),
    )

    private fun webFetch(id: String, input: String) = UIMessagePart.Tool(
        toolCallId = "call-$id",
        toolName = "web_fetch",
        input = input,
        output = listOf(UIMessagePart.Text(longOutput)),
    )

    private fun assistant(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
    )

    private fun textOf(part: UIMessagePart): String =
        (part as UIMessagePart.Tool).output.filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }

    private fun assertStubbed(part: UIMessagePart) {
        val text = textOf(part)
        assertTrue(text.contains(PrecisionObservationMask.MARKER))
        assertFalse(text.contains("x".repeat(2_000)))
        assertTrue(text.length < longOutput.length)
    }
}
