package me.rerere.rikkahub.data.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.providers.openai.ResponseStreamErrorException
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U3 — the mid-stream continuation / restart policy. These pin the pure decisions the stream loop
 * makes after a transport break, without needing a live provider.
 */
class GenerationHandlerContinuationTest {

    private fun assistantText(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    // ---- shouldContinueGenerationStream ------------------------------------

    @Test
    fun `continues after a transport break once output has arrived`() {
        assertTrue(
            shouldContinueGenerationStream(
                failure = IOException("Software caused connection abort"),
                receivedMeaningfulOutput = true,
                continuationsUsed = 0,
            )
        )
    }

    @Test
    fun `does not continue when nothing was written yet`() {
        assertFalse(
            shouldContinueGenerationStream(
                failure = IOException("connection reset"),
                receivedMeaningfulOutput = false,
                continuationsUsed = 0,
            )
        )
    }

    @Test
    fun `does not continue past the continuation budget`() {
        assertFalse(
            shouldContinueGenerationStream(
                failure = IOException("connection reset"),
                receivedMeaningfulOutput = true,
                continuationsUsed = 2,
                maxContinuations = 2,
            )
        )
    }

    @Test
    fun `does not continue a user cancellation`() {
        assertFalse(
            shouldContinueGenerationStream(
                failure = CancellationException("Generation cancelled by user"),
                receivedMeaningfulOutput = true,
                continuationsUsed = 0,
            )
        )
    }

    @Test
    fun `does not continue an explicit response stream error`() {
        assertFalse(
            shouldContinueGenerationStream(
                failure = ResponseStreamErrorException("server_error", "stream failed"),
                receivedMeaningfulOutput = true,
                continuationsUsed = 0,
            )
        )
    }

    @Test
    fun `does not continue a deterministic client error`() {
        assertFalse(
            shouldContinueGenerationStream(
                failure = HttpException("bad request", 400),
                receivedMeaningfulOutput = true,
                continuationsUsed = 0,
            )
        )
    }

    @Test
    fun `does not continue a context limit failure`() {
        assertFalse(
            shouldContinueGenerationStream(
                failure = IOException("maximum context length exceeded"),
                receivedMeaningfulOutput = true,
                continuationsUsed = 0,
            )
        )
    }

    // ---- canContinueFromPartial --------------------------------------------

    @Test
    fun `a text-only partial can be continued`() {
        assertTrue(canContinueFromPartial(assistantText("The answer so far is")))
    }

    @Test
    fun `a partial holding a tool call cannot be continued`() {
        assertFalse(
            canContinueFromPartial(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text("let me check"),
                        UIMessagePart.ToolCall("call-1", "search", "{}"),
                    ),
                )
            )
        )
    }

    @Test
    fun `a blank partial cannot be continued`() {
        assertFalse(canContinueFromPartial(assistantText("   ")))
    }

    @Test
    fun `a user message is not a continuable partial`() {
        assertFalse(
            canContinueFromPartial(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))
            )
        )
    }

    @Test
    fun `a missing partial cannot be continued`() {
        assertFalse(canContinueFromPartial(null))
    }

    // ---- continuationRequestMessages ---------------------------------------

    @Test
    fun `continuation appends the partial turn and a synthetic user instruction`() {
        val base = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("question"))),
        )
        val current = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("question"))),
            assistantText("half an answer"),
        )

        val result = continuationRequestMessages(base, current)

        assertEquals(3, result.size)
        assertEquals(MessageRole.USER, result[0].role)
        assertEquals(MessageRole.ASSISTANT, result[1].role)
        assertEquals("half an answer", (result[1].parts.single() as UIMessagePart.Text).text)
        assertEquals(MessageRole.USER, result[2].role)
        assertTrue(result[2].isSynthetic)
    }

    @Test
    fun `continuation falls back to the base request when the tail is not assistant`() {
        val base = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("question"))),
        )
        val current = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("again"))),
        )

        assertEquals(base, continuationRequestMessages(base, current))
    }

    // ---- shouldRestartGenerationStreamAfterPartial -------------------------

    @Test
    fun `restarts once after a mid-stream failure`() {
        assertTrue(
            shouldRestartGenerationStreamAfterPartial(
                failure = IOException("connection reset"),
                receivedMeaningfulOutput = true,
                alreadyRestarted = false,
            )
        )
    }

    @Test
    fun `does not restart twice`() {
        assertFalse(
            shouldRestartGenerationStreamAfterPartial(
                failure = IOException("connection reset"),
                receivedMeaningfulOutput = true,
                alreadyRestarted = true,
            )
        )
    }

    @Test
    fun `does not restart when nothing was written`() {
        assertFalse(
            shouldRestartGenerationStreamAfterPartial(
                failure = IOException("connection reset"),
                receivedMeaningfulOutput = false,
                alreadyRestarted = false,
            )
        )
    }

    @Test
    fun `does not restart on cancellation`() {
        assertFalse(
            shouldRestartGenerationStreamAfterPartial(
                failure = CancellationException("stop"),
                receivedMeaningfulOutput = true,
                alreadyRestarted = false,
            )
        )
    }
}
