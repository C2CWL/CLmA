package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [buildContextBudgetReminder] / [injectContextReminder].
 *
 * Contract: a `<context_reminder>` is produced only once the estimated request context reaches
 * the assistant's configured reminder percentage (of the model context window), and it is injected
 * as a synthetic user message immediately before the LAST user message.
 *
 * Estimator facts relied on here (ContextBudgetPlanner): an ASCII string costs `ceil(len / 3)`
 * tokens, and every message adds a fixed 8-token overhead.
 */
class ContextBudgetReminderTransformerTest {

    private fun getMessageText(msg: UIMessage): String =
        msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    /** 3000 ASCII chars => ceil(3000/3) = 1000 tokens + 8 overhead = 1008. */
    private val bigMessage = "a".repeat(3000)

    @Test
    fun `below the reminder percentage returns null`() {
        val messages = listOf(UIMessage.user("short"))
        assertNull(buildContextBudgetReminder(messages, contextLength = 100_000, reminderPercent = 70))
    }

    @Test
    fun `at or above the reminder percentage returns a reminder`() {
        // contextLength 1000, 70% => trigger 700; estimate 1008 >= 700.
        val reminder = buildContextBudgetReminder(
            messages = listOf(UIMessage.user(bigMessage)),
            contextLength = 1000,
            reminderPercent = 70,
        )
        assertNotNull(reminder)
        assertTrue(reminder!!.contains("<context_reminder>"))
        assertTrue(reminder.contains("compact_context"))
    }

    @Test
    fun `a higher reminder percentage defers the reminder`() {
        val messages = listOf(UIMessage.user(bigMessage))
        // contextLength 3000, 70% => 2100 > estimate 1008 => still below the threshold.
        assertNull(buildContextBudgetReminder(messages, contextLength = 3000, reminderPercent = 70))
        // ... but 30% => 900 <= 1008 => fires.
        assertNotNull(buildContextBudgetReminder(messages, contextLength = 3000, reminderPercent = 30))
    }

    @Test
    fun `inject inserts the reminder before the last user message`() {
        val messages = listOf(
            UIMessage.user("first"),
            UIMessage.assistant("reply"),
            UIMessage.user("second"),
        )
        val result = injectContextReminder(messages, "<context_reminder>x</context_reminder>")
        assertEquals(4, result.size)
        assertEquals("first", getMessageText(result[0]))
        assertEquals("reply", getMessageText(result[1]))
        assertTrue(result[2].isSynthetic)
        assertTrue(getMessageText(result[2]).contains("<context_reminder>"))
        assertEquals("second", getMessageText(result[3]))
    }

    @Test
    fun `inject appends at the end when there is no user message`() {
        val messages = listOf(UIMessage.assistant("hi"))
        val result = injectContextReminder(messages, "<context_reminder>x</context_reminder>")
        assertEquals(2, result.size)
        assertEquals("hi", getMessageText(result[0]))
        assertTrue(result[1].isSynthetic)
    }
}
