package com.clma.app.data.gemini

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.GoogleThoughtMetadata
import com.clma.ai.ui.MessageChunk
import com.clma.ai.ui.StreamChunk
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessageChoice
import com.clma.ai.ui.UIMessagePart
import com.clma.ai.ui.toMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for issue #48: [GeminiStreamChunkAdapter] must forward a Tool part's
 * metadata onto the emitted [StreamChunk.ToolCallStart]/[StreamChunk.ToolCallDelta], or a
 * `thoughtSignature` that Gemini attached directly to the functionCall part (rather than to a
 * preceding thought part) is dropped, and the continuation request comes back
 * `400: Function call is missing a thought_signature`.
 */
class GeminiStreamChunkAdapterTest {

    @Test
    fun `tool part metadata is forwarded to ToolCallStart and ToolCallDelta`() {
        val metadata = GoogleThoughtMetadata(thoughtSignature = "sig-xyz").toMetadata()
        val chunk = MessageChunk(
            id = "resp-1",
            model = "gemini-2.5-pro",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "tool-1",
                                toolName = "search",
                                input = "{}",
                                metadata = metadata,
                            )
                        ),
                    ),
                    message = null,
                    finishReason = null,
                )
            ),
        )

        val out = GeminiStreamChunkAdapter().translate(chunk)

        val start = out.filterIsInstance<StreamChunk.ToolCallStart>().single()
        val delta = out.filterIsInstance<StreamChunk.ToolCallDelta>().single()
        assertEquals(metadata, start.metadata)
        assertEquals(metadata, delta.metadata)
    }
}
