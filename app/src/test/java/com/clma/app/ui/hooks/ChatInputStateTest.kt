package com.clma.app.ui.hooks

import com.clma.ai.ui.UIMessagePart
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatInputStateTest {
    private val image = UIMessagePart.Image("file:///image.jpg")
    private val document = UIMessagePart.Document(
        url = "file:///notes.txt", fileName = "notes.txt", mime = "text/plain",
    )

    @Test
    fun `mixed attachments send in the displayed order`() {
        val state = ChatInputState()
        state.messageContent = listOf(image, document)
        state.moveAttachment(1, 0)
        assertEquals(listOf(document, image), state.getContents())
        state.moveAttachment(0, 1)
        assertEquals(listOf(image, document), state.getContents())
    }

    @Test
    fun `boundary moves leave attachments unchanged`() {
        val state = ChatInputState()
        state.messageContent = listOf(image, document)
        state.moveAttachment(0, -1)
        state.moveAttachment(1, 2)
        assertEquals(listOf(image, document), state.getContents())
    }

    @Test
    fun `editing respects reordered attachments without losing text`() {
        val state = ChatInputState()
        val text = UIMessagePart.Text("caption")
        state.setContents(listOf(image, text, document))
        state.editingMessage = Uuid.random()
        state.moveAttachment(1, 0)
        assertEquals(listOf(document, text, image), state.getContents())
    }

    @Test
    fun `removing while editing preserves untouched text placement`() {
        val state = ChatInputState()
        val text = UIMessagePart.Text("caption")
        state.setContents(listOf(image, text, document))
        state.editingMessage = Uuid.random()
        state.messageContent = listOf(document)
        assertEquals(listOf(text, document), state.getContents())
    }
}
