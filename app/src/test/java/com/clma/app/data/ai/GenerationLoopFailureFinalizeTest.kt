package com.clma.app.data.ai

import com.clma.ai.ui.UIMessage
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A non-cancel generation failure must finalize the trailing assistant reply (set finishedAt).
 * finishedAt == null is the marker for the unfinished front half of an interjection, so a failed
 * reply left at null got merged with the user's next "继续" and the following reply.
 */
class GenerationLoopFailureFinalizeTest {

    private val now = LocalDateTime(2026, 10, 22, 13, 42, 46)

    @Test
    fun `failed assistant reply gets finishedAt`() {
        val messages = listOf(UIMessage.user("q"), UIMessage.assistant("partial"))

        val result = messages.markFailedTurnFinished(now)

        assertEquals(now, result.last().finishedAt)
        assertEquals("partial", result.last().toText())
        assertSame(messages.first(), result.first())
    }

    @Test
    fun `already finished reply keeps its finishedAt`() {
        val earlier = LocalDateTime(2026, 10, 22, 13, 0, 0)
        val messages = listOf(UIMessage.user("q"), UIMessage.assistant("done").copy(finishedAt = earlier))

        assertSame(messages, messages.markFailedTurnFinished(now))
    }

    @Test
    fun `trailing user message is left alone`() {
        // The request failed before any assistant output: nothing to finalize.
        val messages = listOf(UIMessage.user("q"))

        val result = messages.markFailedTurnFinished(now)

        assertSame(messages, result)
        assertNull(result.last().finishedAt)
    }

    @Test
    fun `empty history is left alone`() {
        assertEquals(emptyList<UIMessage>(), emptyList<UIMessage>().markFailedTurnFinished(now))
    }
}
