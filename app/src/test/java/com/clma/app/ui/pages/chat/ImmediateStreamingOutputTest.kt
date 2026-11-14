package com.clma.app.ui.pages.chat

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.model.Conversation
import com.clma.app.data.model.MessageNode
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmediateStreamingOutputTest {
    private val message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
    private val node = MessageNode.of(message)
    private val base = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))

    private fun snapshot(parts: List<UIMessagePart>, finished: Boolean = false): Conversation =
        base.copy(messageNodes = listOf(node.copy(messages = listOf(message.copy(
            parts = parts,
            finishedAt = if (finished) message.createdAt else null,
        )))))

    @Test fun defaultDisplayPathAddsNoBatchingFlowOrDelay() = runTest {
        val source = listOf(
            snapshot(listOf(UIMessagePart.Text("你"))),
            snapshot(listOf(UIMessagePart.Text("你好"))),
        ).asFlow()
        assertSame(source, source.throttleAppendOnlyStreaming())
        assertEquals(source.toList(), source.throttleAppendOnlyStreaming().toList())
    }

    @Test fun completeReplyAndToolBoundariesKeepAllSnapshotsAndFinalTail() = runTest {
        val tool = UIMessagePart.Tool(toolCallId = "test", toolName = "test", input = "{}")
        val events = listOf(
            snapshot(listOf(UIMessagePart.Text("a"))),
            snapshot(listOf(UIMessagePart.Text("ab"))),
            snapshot(listOf(UIMessagePart.Text("ab"), tool)),
            snapshot(listOf(UIMessagePart.Text("ab"), tool.copy(output = listOf(UIMessagePart.Text("done"))))),
            snapshot(listOf(UIMessagePart.Text("ab"), tool.copy(output = listOf(UIMessagePart.Text("done"))), UIMessagePart.Text("尾部")), true),
        )
        val actual = events.asFlow().throttleAppendOnlyStreaming().toList()
        assertEquals(events, actual)
        assertEquals(events.last(), actual.last())
        assertTrue(actual.last().messageNodes.single().currentMessage.finishedAt != null)
    }

    @Test fun cancellationStopsUpstreamWithoutAQueuedDisplayTicker() = runTest {
        val stopped = CompletableDeferred<Unit>()
        val one = snapshot(listOf(UIMessagePart.Text("partial")))
        val actual = flow {
            try { emit(one); awaitCancellation() }
            finally { stopped.complete(Unit) }
        }.throttleAppendOnlyStreaming().take(1).toList()
        stopped.await()
        assertEquals(listOf(one), actual)
    }

    @Test fun producerFailureIsNotConvertedToACompletedReply() = runTest {
        val failure = IllegalStateException("test source failure")
        val actual = runCatching {
            flow<Conversation> { emit(base); throw failure }
                .throttleAppendOnlyStreaming().toList()
        }.exceptionOrNull()
        assertSame(failure, actual)
    }
}
