package com.clma.app.data.ai

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.datastore.AiLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AILoggingRetentionTest {
    private val messages = listOf(
        UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("x".repeat(1_000)),
                UIMessagePart.Image("file:///upload/a.jpg"),
                UIMessagePart.Document(url = "file:///upload/a.txt", fileName = "a.txt"),
            ),
        ),
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("ok"))),
    )

    private val log = AILogging.Generation(
        modelId = "m",
        messages = messages,
        stream = true,
        context = ContextInspection.capture(messages, emptyList(), ""),
    )

    @Test
    fun `info keeps only the summary, not the request messages`() {
        val kept = log.retainedFor(AiLogLevel.INFO) as AILogging.Generation
        assertTrue(kept.messages.isEmpty())
        assertTrue(kept.context!!.sections.all { it.preview == null })
        assertEquals(
            GenerationSummary(
                messageCount = 2,
                partCount = 4,
                textChars = 1_002,
                imageCount = 1,
                documentCount = 1,
                otherMediaCount = 0,
            ),
            kept.summary,
        )
    }

    @Test
    fun `debug keeps bounded previews but never raw messages`() {
        val kept = log.retainedFor(AiLogLevel.DEBUG) as AILogging.Generation
        assertTrue(kept.messages.isEmpty())
        assertTrue(kept.context!!.sections.any { it.preview != null })
        assertEquals(2, kept.summary?.messageCount)
    }
}
