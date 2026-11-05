package com.clma.app.ui.pages.chat

import com.clma.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whole-turn grouping for the conversation outline: a user message opens a turn and everything
 * after it (assistant reply, tool steps) joins that turn until the next user message.
 */
class ChatOutlineTest {

    private fun item(role: MessageRole, text: String) = ChatOutlineItem(role, text)

    @Test
    fun `user messages open a turn and later items join it`() {
        val outline = buildChatOutline(
            listOf(
                item(MessageRole.USER, "first question"),
                item(MessageRole.ASSISTANT, "first answer"),
                item(MessageRole.TOOL, "tool step"),
                item(MessageRole.USER, "second question"),
                item(MessageRole.ASSISTANT, "second answer"),
            )
        )

        assertEquals(listOf(1, 1, 1, 2, 2), outline.map { it.turn })
        assertEquals(listOf(true, false, false, true, false), outline.map { it.isTurnStart })
        assertEquals(listOf(0, 1, 2, 3, 4), outline.map { it.itemIndex })
    }

    @Test
    fun `a conversation starting with an assistant still gets a first turn`() {
        val outline = buildChatOutline(
            listOf(
                item(MessageRole.ASSISTANT, "hello"),
                item(MessageRole.TOOL, "tool step"),
            )
        )

        assertEquals(listOf(1, 1), outline.map { it.turn })
        assertTrue(outline.first().isTurnStart)
        assertFalse(outline.last().isTurnStart)
    }

    @Test
    fun `label keeps the role prefix and truncates long text`() {
        val outline = buildChatOutline(
            listOf(item(MessageRole.USER, "x".repeat(200))),
            maxLabelChars = 10,
        )

        assertEquals("USER: xxxxxxxxxx", outline.single().label)
    }

    @Test
    fun `blank text still gets a visible label`() {
        val outline = buildChatOutline(listOf(item(MessageRole.ASSISTANT, "   ")))

        assertTrue(outline.single().label.endsWith("[…]"))
    }

    @Test
    fun `an empty conversation yields no entries`() {
        assertTrue(buildChatOutline(emptyList()).isEmpty())
    }
}
