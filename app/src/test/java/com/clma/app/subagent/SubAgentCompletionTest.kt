package com.clma.app.subagent

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubAgentCompletionTest {
    @Test
    fun `blank output is never success`() {
        assertNotNull(subAgentCompletionError("", null, 0))
        assertNotNull(subAgentCompletionError(" \n", null, 0))
    }

    @Test
    fun `successful retry accepts text while terminal failure preserves error`() {
        assertNull(subAgentCompletionError("report", null, 1))
        assertEquals("provider failed (after 1 retry attempt(s))",
            subAgentCompletionError("partial", "provider failed", 1))
    }

    @Test
    fun `last plain text reply is the report`() {
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("earlier"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(" report "))),
        )
        assertEquals("report", subAgentResultText(messages))
    }

    @Test
    fun `tool narration remains partial even with text`() {
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("inspecting"),
            UIMessagePart.Tool(toolCallId = "1", toolName = "lookup", input = "{}"),
        )))
        assertEquals("", subAgentResultText(messages))
        assertEquals("inspecting", subAgentResultText(messages, allowPartial = true))
    }

    @Test
    fun `merged tool rounds harvest only text after last tool`() {
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("inspecting"),
            UIMessagePart.Tool(toolCallId = "1", toolName = "lookup", input = "{}"),
            UIMessagePart.Text("final report"),
        )))
        assertEquals("final report", subAgentResultText(messages))
    }

    @Test
    fun `empty closing reply cannot turn earlier narration into success`() {
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("earlier"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
        )
        assertEquals("", subAgentResultText(messages))
        assertEquals("earlier", subAgentResultText(messages, allowPartial = true))
        assertEquals("", subAgentResultText(emptyList()))
    }
}
