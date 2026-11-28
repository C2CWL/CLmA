package com.clma.app.data.ai.transformers

import kotlinx.datetime.LocalDateTime
import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.ContextCompactionView
import com.clma.app.data.model.Conversation
import com.clma.app.data.model.ConversationCompaction
import com.clma.app.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Coverage for [applyTimeReminder].
 *
 * Contract: reminders are appended at the request tail so that changing clock/gap text never
 * rewrites an earlier message and breaks the prompt cache prefix.
 */
class TimeReminderTransformerTest {

    private fun userMessage(text: String, createdAt: LocalDateTime) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = createdAt,
    )

    private fun getMessageText(msg: UIMessage): String =
        msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    @Test
    fun `single user message gets a reminder appended at tail`() {
        val messages = listOf(userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)))
        val result = applyTimeReminder(messages)
        assertEquals(2, result.size)
        assertEquals("Hello", getMessageText(result[0]))
        assertTrue(result[1].isSynthetic)
        val injected = getMessageText(result[1])
        assertTrue(injected.contains("<time_reminder>"))
        assertFalse(injected.contains("since last message"))
    }

    @Test
    fun `gap less than 1 hour appends only the first-message reminder`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 10, 30, 0)),
        )
        val result = applyTimeReminder(messages)
        assertEquals(3, result.size)
        assertEquals("Hello", getMessageText(result[0]))
        assertEquals("World", getMessageText(result[1]))
        assertTrue(getMessageText(result[2]).contains("<time_reminder>"))
    }

    @Test
    fun `gap exactly 1 hour appends only the first-message reminder`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 11, 0, 0)),
        )
        val result = applyTimeReminder(messages)
        assertEquals(3, result.size)
        assertTrue(getMessageText(result[2]).contains("<time_reminder>"))
    }

    @Test
    fun `gap more than 1 hour appends a reminder at tail with gap text`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 12, 0, 0)),
        )
        val result = applyTimeReminder(messages)
        assertEquals(4, result.size)
        assertEquals("Hello", getMessageText(result[0]))
        assertEquals("World", getMessageText(result[1]))
        assertTrue(getMessageText(result[2]).contains("<time_reminder>"))
        assertFalse(getMessageText(result[2]).contains("since last message"))
        val gapReminder = getMessageText(result[3])
        assertTrue(gapReminder.contains("<time_reminder>"))
        assertTrue(gapReminder.contains("since last message"))
    }

    @Test
    fun `appended gap reminder contains day of week and gap in hours`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 12, 0, 0)),
        )
        val result = applyTimeReminder(messages)
        val injected = getMessageText(result[3])
        assertTrue(injected.contains(","))
        assertTrue(injected.contains("2 h since last message"))
    }

    @Test
    fun `gap in days formats correctly`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 20, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 10, 0, 0)),
        )
        val result = applyTimeReminder(messages)
        assertTrue(getMessageText(result[3]).contains("2 d since last message"))
    }

    @Test
    fun `multiple large gaps append reminders at tail in order`() {
        val messages = listOf(
            userMessage("Msg 1", LocalDateTime(2026, 2, 20, 10, 0, 0)),
            userMessage("Msg 2", LocalDateTime(2026, 2, 21, 10, 0, 0)),
            userMessage("Msg 3", LocalDateTime(2026, 2, 22, 10, 0, 0)),
        )
        val result = applyTimeReminder(messages)
        assertEquals(6, result.size)
        assertEquals("Msg 1", getMessageText(result[0]))
        assertEquals("Msg 2", getMessageText(result[1]))
        assertEquals("Msg 3", getMessageText(result[2]))
        assertTrue(getMessageText(result[3]).contains("<time_reminder>"))
        assertTrue(getMessageText(result[4]).contains("1 d since last message"))
        assertTrue(getMessageText(result[5]).contains("1 d since last message"))
    }

    @Test
    fun `custom interval should use strict threshold and format minutes`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 10, 30, 0)),
        )
        assertEquals(3, applyTimeReminder(messages, intervalMinutes = 30).size)
        val result = applyTimeReminder(messages, intervalMinutes = 15)
        assertEquals(4, result.size)
        assertTrue(getMessageText(result[3]).contains("30 min since last message"))
    }

    @Test
    fun `large interval should not overflow or inject prematurely`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 12, 0, 0)),
        )
        assertEquals(3, applyTimeReminder(messages, intervalMinutes = 180).size)
        assertEquals(3, applyTimeReminder(messages, intervalMinutes = Int.MAX_VALUE).size)
    }

    @Test
    fun `empty messages should return empty`() {
        assertEquals(0, applyTimeReminder(emptyList()).size)
    }

    @Test
    fun `tail placement keeps original history bytes identical`() {
        val messages = listOf(
            userMessage("Hello", LocalDateTime(2026, 2, 22, 10, 0, 0)),
            userMessage("World", LocalDateTime(2026, 2, 22, 10, 30, 0)),
        )
        val result = applyTimeReminder(messages)
        assertEquals(messages.map(::getMessageText), result.take(messages.size).map(::getMessageText))
    }
}
