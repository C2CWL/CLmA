package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A stream that dies after its Start markers but before any delta is retried, and the retry used
 * to append after the failed attempt's empty parts - a blank text part between tool calls that
 * split one step group into several. Driving the real retryWhen path needs Android Context + the
 * Koin graph, so this exercises the extracted rollback with a real [StreamChunkHandler].
 */
class StreamRetryRollbackTest {

    private val model = Model(modelId = "test-model")

    private val attemptStart = listOf(
        UIMessage.user("fetch two pages"),
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(toolCallId = "call-1", toolName = "web_fetch", input = "{}"),
                UIMessagePart.Tool(toolCallId = "call-2", toolName = "web_fetch", input = "{}"),
            ),
        ),
    )

    @Test
    fun `retry before meaningful output drops the empty parts left by start markers`() {
        val handler = StreamChunkHandler(model)
        var messages = attemptStart
        messages = handler.handle(messages, StreamChunk.TextStart("text-1"))
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call-3", "web_fetch"))
        // Precondition: the bare markers really did leave empty parts behind.
        assertEquals(4, messages.last().parts.size)

        val rolledBack = messagesForStreamRetry(
            current = messages,
            attemptStart = attemptStart,
            receivedMeaningfulOutput = false,
        )

        assertSame(attemptStart, rolledBack)
    }

    @Test
    fun `retried attempt appends directly after the previous tools`() {
        var messages = StreamChunkHandler(model).handle(attemptStart, StreamChunk.TextStart("text-1"))
        messages = messagesForStreamRetry(messages, attemptStart, receivedMeaningfulOutput = false)

        val retryHandler = StreamChunkHandler(model)
        messages = retryHandler.handle(messages, StreamChunk.ToolCallStart("call-3", "web_fetch"))
        messages = retryHandler.handle(messages, StreamChunk.ToolCallDelta("call-3", inputDelta = "{}"))

        val parts = messages.last().parts
        assertEquals(3, parts.size)
        assertEquals(listOf("call-1", "call-2", "call-3"), parts.map { (it as UIMessagePart.Tool).toolCallId })
    }

    @Test
    fun `meaningful output is never rolled back`() {
        val handler = StreamChunkHandler(model)
        var messages = handler.handle(attemptStart, StreamChunk.TextStart("text-1"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "partial"))

        val result = messagesForStreamRetry(messages, attemptStart, receivedMeaningfulOutput = true)

        assertSame(messages, result)
    }
}
