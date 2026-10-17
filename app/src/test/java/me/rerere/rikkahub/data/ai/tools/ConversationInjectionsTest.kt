package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pins the system-injection queue: push/drain ordering, drain-is-destructive, per-conversation
 * isolation, the MAX_PENDING cap dropping the oldest entries, and clear. This queue is how a
 * background sub-agent's completion reaches the parent's model without ever becoming a
 * visible chat message — a leak or a wrong order here is a model-facing bug.
 */
class ConversationInjectionsTest {

    @Test
    fun `drain on an untouched conversation returns empty`() {
        assertTrue(ConversationInjections.drain(Uuid.random()).isEmpty())
    }

    @Test
    fun `pushes drain in arrival order and leave the queue empty`() {
        val id = Uuid.random()
        ConversationInjections.push(id, "first")
        ConversationInjections.push(id, "second")
        ConversationInjections.push(id, "third")
        assertEquals(listOf("first", "second", "third"), ConversationInjections.drain(id))
        assertTrue(ConversationInjections.drain(id).isEmpty())
        assertEquals(0, ConversationInjections.pendingCount(id))
    }

    @Test
    fun `blank pushes are ignored`() {
        val id = Uuid.random()
        ConversationInjections.push(id, "   ")
        assertEquals(0, ConversationInjections.pendingCount(id))
    }

    @Test
    fun `one conversation's notices never leak into another`() {
        val a = Uuid.random()
        val b = Uuid.random()
        ConversationInjections.push(a, "for-a")
        assertTrue(ConversationInjections.drain(b).isEmpty())
        assertEquals(listOf("for-a"), ConversationInjections.drain(a))
    }

    @Test
    fun `past the cap the oldest notices are dropped`() {
        val id = Uuid.random()
        for (i in 1..ConversationInjections.MAX_PENDING + 3) {
            ConversationInjections.push(id, "notice-$i")
        }
        assertEquals(ConversationInjections.MAX_PENDING, ConversationInjections.pendingCount(id))
        val drained = ConversationInjections.drain(id)
        assertEquals(ConversationInjections.MAX_PENDING, drained.size)
        assertEquals("notice-4", drained.first())
        assertEquals("notice-${ConversationInjections.MAX_PENDING + 3}", drained.last())
    }

    @Test
    fun `clear discards pending notices`() {
        val id = Uuid.random()
        ConversationInjections.push(id, "stale")
        ConversationInjections.clear(id)
        assertTrue(ConversationInjections.drain(id).isEmpty())
    }
}
