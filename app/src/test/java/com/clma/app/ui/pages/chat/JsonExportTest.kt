package com.clma.app.ui.pages.chat

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.model.Conversation
import com.clma.app.data.model.MessageNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Structured export payload: conversation metadata plus the selected messages, reusing the app's
 * own message serialisation so the exported `parts` match what is persisted locally.
 */
class JsonExportTest {

    @Test
    fun `payload carries conversation metadata and the selected messages`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("hello there")),
        )
        val conversation = Conversation(
            assistantId = Uuid.random(),
            title = "Sample chat",
            messageNodes = listOf(MessageNode(messages = listOf(message))),
        )

        val text = buildJsonExport(
            conversation = conversation,
            messages = listOf(message),
            exportedAt = "2026-11-05 05:00",
        )
        val root = Json.parseToJsonElement(text).jsonObject

        assertEquals(1, root["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals("2026-11-05 05:00", root["exportedAt"]!!.jsonPrimitive.content)
        val meta = root["conversation"]!!.jsonObject
        assertEquals("Sample chat", meta["title"]!!.jsonPrimitive.content)
        assertEquals(conversation.assistantId.toString(), meta["assistantId"]!!.jsonPrimitive.content)

        val messages = root["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        val exported = messages.single().jsonObject
        assertEquals("user", exported["role"]!!.jsonPrimitive.content)
        assertTrue(text.contains("hello there"))
        assertTrue(text.contains("\n"))
    }
}
