package com.clma.app.data.ai

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.tools.conversationImageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestImageAgingTest {
    private fun userWithImage(n: Int) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text("turn $n"), UIMessagePart.Image("file:///upload/$n.jpg")),
    )

    private fun reply(n: Int) = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("reply $n")))

    private fun imagesOf(message: UIMessage) = message.parts.count { it is UIMessagePart.Image }

    @Test
    fun `keeps images of the last four user turns and elides older ones`() {
        val history = (1..6).flatMap { listOf(userWithImage(it), reply(it)) }.dropLast(1)
        val aged = history.ageOldUserImages()
        val users = aged.filter { it.role == MessageRole.USER }
        assertEquals(listOf(0, 0, 1, 1, 1, 1), users.map(::imagesOf))
        // The elided image becomes the placeholder text, in place; the text part stays.
        assertEquals(
            listOf(UIMessagePart.Text("turn 1")),
            users.first().parts.take(1),
        )
        val placeholder = (users.first().parts[1] as UIMessagePart.Text).text
        assertTrue(placeholder.contains(ELIDED_USER_IMAGE_TEXT))
        assertTrue(placeholder.contains(conversationImageRef(history.first(), 1)))
        assertTrue(placeholder.contains("conversation_image"))
    }

    @Test
    fun `request only - the stored messages are untouched`() {
        val history = (1..6).map(::userWithImage)
        val before = history.toList()
        history.ageOldUserImages()
        assertEquals(before, history)
        assertEquals(6, history.sumOf(::imagesOf))
    }

    @Test
    fun `synthetic user messages do not count as turns`() {
        val synthetic = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("continue")),
            isSynthetic = true,
        )
        val history = (1..4).map(::userWithImage) + synthetic + synthetic
        assertEquals(4, history.ageOldUserImages().sumOf(::imagesOf))
    }

    @Test
    fun `messages without images are returned as is`() {
        val message = UIMessage.user("hello")
        val aged = listOf(message, reply(1), userWithImage(1), userWithImage(2), userWithImage(3), userWithImage(4))
            .ageOldUserImages()
        assertSame(message, aged.first())
    }
}
