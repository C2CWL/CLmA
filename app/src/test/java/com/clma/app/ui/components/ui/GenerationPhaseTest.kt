package com.clma.app.ui.components.ui

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationPhaseTest {

    private fun assistant(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    @Test
    fun `streaming reasoning is thinking`() {
        val msg = assistant(UIMessagePart.Reasoning(reasoning = "hmm", finishedAt = null))
        assertEquals(GenerationPhase.THINKING, generationPhaseOf(msg))
    }

    @Test
    fun `text after reasoning is replying`() {
        val msg = assistant(
            UIMessagePart.Reasoning(reasoning = "hmm"),
            UIMessagePart.Text("Hello"),
        )
        assertEquals(GenerationPhase.REPLYING, generationPhaseOf(msg))
    }

    @Test
    fun `tools, finished reasoning and unknown states are working`() {
        assertEquals(GenerationPhase.WORKING, generationPhaseOf(null))
        assertEquals(GenerationPhase.WORKING, generationPhaseOf(assistant()))
        assertEquals(GenerationPhase.WORKING, generationPhaseOf(assistant(UIMessagePart.Reasoning(reasoning = "done"))))
        assertEquals(
            GenerationPhase.WORKING,
            generationPhaseOf(assistant(UIMessagePart.Tool(toolCallId = "1", toolName = "search", input = "{}"))),
        )
        // The user's own message is last right after sending: nothing streamed yet.
        assertEquals(
            GenerationPhase.WORKING,
            generationPhaseOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))),
        )
    }
}
