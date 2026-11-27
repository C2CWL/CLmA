package com.clma.app.data.ai.tools

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationAttachmentToolTest {
    private fun documentMessage(
        role: MessageRole = MessageRole.USER,
        url: String = "file:///upload/a.txt",
        fileName: String = "a.txt",
    ) = UIMessage(
        role = role,
        parts = listOf(
            UIMessagePart.Text("nearby summary text"),
            UIMessagePart.Document(url = url, fileName = fileName, mime = "text/plain"),
        ),
    )

    private fun execute(
        messages: List<UIMessage>,
        args: String = "{}",
    ) = runBlocking {
        val tool = createConversationAttachmentTool { messages }
        val parts = tool.execute(Json.parseToJsonElement(args))
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }

    @Test
    fun `listing returns stable refs and upload paths without document content`() {
        val message = documentMessage()
        val result = execute(listOf(message))
        val attachment = result.getValue("attachments").jsonArray.single().jsonObject
        val ref = attachment.getValue("attachment_ref").jsonPrimitive.content

        assertEquals(conversationAttachmentRef(message, 1), ref)
        assertEquals("a.txt", attachment.getValue("file_name").jsonPrimitive.content)
        assertEquals("/upload/a.txt", attachment.getValue("path").jsonPrimitive.content)
        assertEquals("text/plain", attachment.getValue("mime").jsonPrimitive.content)
        assertEquals("nearby summary text", attachment.getValue("text").jsonPrimitive.content.trim())
        assertFalse(result.toString().contains("file body that must not be listed"))
    }

    @Test
    fun `listing excludes assistant synthetic remote and non-upload files and paginates`() {
        val messages =
            (1..21).map { documentMessage(url = "file:///upload/a$it.txt", fileName = "a$it.txt") } +
                documentMessage(MessageRole.ASSISTANT, url = "file:///upload/assistant.txt", fileName = "assistant.txt") +
                documentMessage().copy(isSynthetic = true) +
                documentMessage(url = "file:///data/user/0/private.txt", fileName = "private.txt") +
                documentMessage(url = "https://example.com/remote.txt", fileName = "remote.txt")
        val first = execute(messages)
        assertEquals(20, first.getValue("attachments").jsonArray.size)
        assertEquals("20", first.getValue("next_offset").jsonPrimitive.content)
        val second = execute(messages, """{"offset":20}""")
        assertEquals(1, second.getValue("attachments").jsonArray.size)
        assertFalse(second.containsKey("next_offset"))
        assertEquals(0, execute(messages, """{"offset":2147483647}""").getValue("attachments").jsonArray.size)
    }

    @Test
    fun `attachment ref resolves within supplied conversation`() {
        val message = documentMessage()
        val ref = conversationAttachmentRef(message, 1)
        val result = execute(listOf(message), """{"attachment_ref":"$ref"}""")
        val attachment = result.getValue("attachment").jsonObject

        assertEquals(conversationAttachmentRef(message, 1), attachment.getValue("attachment_ref").jsonPrimitive.content)
        assertEquals("/upload/a.txt", attachment.getValue("path").jsonPrimitive.content)
    }

    @Test
    fun `unrelated attachment ref is rejected`() {
        val other = documentMessage()
        val result = execute(listOf(documentMessage()), """{"attachment_ref":"${conversationAttachmentRef(other, 1)}"}""")
        assertEquals("attachment_not_found", result.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `chat mode allows attachment recall and bounded reads`() {
        assertTrue(ChatModeTools.isAllowed("conversation_attachment"))
        assertTrue(ChatModeTools.isAllowed("read_attachment"))
    }
}
