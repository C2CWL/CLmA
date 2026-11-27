package com.clma.app.service

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationSteersTest {
    private val conversationId = Uuid.random()

    @Before
    fun clearPending() {
        ConversationSteers.clear(conversationId)
    }

    @Test
    fun `remove cancels only the matching pending interject`() {
        val first = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("first")))
        val second = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("second")))
        ConversationSteers.push(conversationId, first)
        ConversationSteers.push(conversationId, second)

        assertTrue(ConversationSteers.contains(conversationId, first.id))
        assertFalse(ConversationSteers.remove(conversationId, Uuid.random()))
        assertTrue(ConversationSteers.remove(conversationId, first.id))
        assertFalse(ConversationSteers.contains(conversationId, first.id))
        assertEquals(1, ConversationSteers.pendingCount(conversationId))

        val remaining = ConversationSteers.drainIntoRequest(conversationId, mutableListOf())
        assertEquals(listOf(second.id), remaining.map { it.id })
    }
}
