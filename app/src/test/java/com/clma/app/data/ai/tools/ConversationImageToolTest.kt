package com.clma.app.data.ai.tools

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.ageOldUserImages
import com.clma.app.data.ai.tools.local.NULL_CONTEXT
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationImageToolTest {
    private fun imageMessage(
        role: MessageRole = MessageRole.USER,
        url: String = "file:///upload/image.jpg",
    ) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text("screenshot"), UIMessagePart.Image(url)),
    )

    private fun execute(
        messages: List<UIMessage>,
        args: String = "{}",
        vision: Boolean = true,
    ) = runBlocking {
        val tool = createConversationImageTool(NULL_CONTEXT, vision) { messages }
        val parts = tool.execute(Json.parseToJsonElement(args))
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }

    @Test
    fun `listing exposes stable refs but no attachment paths`() {
        val message = imageMessage()
        val result = execute(listOf(message))
        val ref =
            result
                .getValue("images")
                .jsonArray
                .single()
                .jsonObject
                .getValue("image_ref")
                .jsonPrimitive.content
        assertEquals(conversationImageRef(message, 1), ref)
        assertTrue(
            listOf(message)
                .ageOldUserImages(keepLastTurns = 0)
                .single()
                .toText()
                .contains(ref),
        )
        assertFalse(result.toString().contains("file:///"))
    }

    @Test
    fun `listing excludes assistant and synthetic images and paginates`() {
        val messages =
            (1..21).map { imageMessage() } +
                imageMessage(MessageRole.ASSISTANT) + imageMessage().copy(isSynthetic = true)
        val first = execute(messages)
        assertEquals(20, first.getValue("images").jsonArray.size)
        assertEquals("20", first.getValue("next_offset").jsonPrimitive.content)
        val second = execute(messages, """{"offset":20}""")
        assertEquals(1, second.getValue("images").jsonArray.size)
        assertFalse(second.containsKey("next_offset"))
        assertEquals(0, execute(messages, """{"offset":2147483647}""").getValue("images").jsonArray.size)
    }

    @Test
    fun `unrelated reference cannot resolve outside supplied conversation`() {
        val other = imageMessage()
        val result = execute(listOf(imageMessage()), """{"image_ref":"${conversationImageRef(other, 1)}"}""")
        assertEquals("image_not_found", result.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `text only model cannot claim to see image`() {
        val message = imageMessage()
        val result = execute(listOf(message), """{"image_ref":"${conversationImageRef(message, 1)}"}""", vision = false)
        assertEquals("vision_unavailable", result.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `remote attachment is rejected before any fetch`() {
        val message = imageMessage(url = "https://example.com/image.jpg")
        val result = execute(listOf(message), """{"image_ref":"${conversationImageRef(message, 1)}"}""")
        assertEquals("unsupported_source", result.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `chat mode allows history image recall`() {
        assertTrue(ChatModeTools.isAllowed("conversation_image"))
    }
}
