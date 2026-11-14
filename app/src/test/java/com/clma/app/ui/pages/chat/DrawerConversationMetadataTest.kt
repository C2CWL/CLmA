package com.clma.app.ui.pages.chat

import com.clma.ai.ui.UIMessage
import com.clma.app.data.botmode.classicAssistants
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.AssistantLibrary
import com.clma.app.data.model.Conversation
import com.clma.app.data.model.MessageNode
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class DrawerConversationMetadataTest {
    private fun conversation() = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())

    @Test fun `body title and timestamp updates do not emit drawer metadata`() = runBlocking {
        val original = conversation()
        val body = original.copy(messageNodes = listOf(MessageNode(messages = listOf(UIMessage.assistant("a")))))
        val more = body.copy(messageNodes = listOf(MessageNode(messages = listOf(UIMessage.assistant("ab")))))
        val renamed = more.copy(title = "new title", updateAt = original.updateAt.plusSeconds(1))
        val actual = flowOf(original, body, more, renamed).drawerMetadataFlow().toList()
        assertEquals(listOf(original.toDrawerMetadata()), actual)
    }

    @Test fun `mode assistant and conversation changes each emit once`() = runBlocking {
        val first = conversation()
        val mode = first.copy(agentModeEnabled = !first.agentModeEnabled)
        val assistant = mode.copy(assistantId = Uuid.random())
        val next = assistant.copy(id = Uuid.random())
        val actual = flowOf(first, first, mode, mode, assistant, next, next).drawerMetadataFlow().toList()
        assertEquals(listOf(first, mode, assistant, next).map { it.toDrawerMetadata() }, actual)
    }

    @Test fun `assistant list cache key changes for edits membership and library changes`() {
        val a = Assistant(name = "a")
        val bot = Assistant(name = "b", library = AssistantLibrary.BOT)
        val settings = Settings(assistants = listOf(a, bot))
        assertEquals(listOf(a), settings.classicAssistants())
        val edited = settings.copy(assistants = listOf(a.copy(name = "renamed"), bot))
        assertNotEquals(settings.assistants, edited.assistants)
        assertEquals("renamed", edited.classicAssistants().single().name)
        val converted = settings.copy(assistants = listOf(a, bot.copy(library = AssistantLibrary.CLASSIC)))
        assertNotEquals(settings.assistants, converted.assistants)
        assertEquals(2, converted.classicAssistants().size)
        val removed = settings.copy(assistants = listOf(bot))
        assertNotEquals(settings.assistants, removed.assistants)
        assertTrue(removed.classicAssistants().isEmpty())
    }
}
