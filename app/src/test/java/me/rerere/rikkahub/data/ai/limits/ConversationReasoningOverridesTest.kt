package me.rerere.rikkahub.data.ai.limits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid
import me.rerere.ai.core.ReasoningLevel

/**
 * Pins the per-conversation reasoning-level override holder: set/get/clear round-trips and
 * one conversation's level never leaks into another. A stale override here would silently
 * change the thinking depth of an unrelated conversation, so the lifecycle stays explicit.
 */
class ConversationReasoningOverridesTest {

    @Test
    fun `an unset conversation has no override`() {
        assertNull(ConversationReasoningOverrides.get(Uuid.random()))
    }

    @Test
    fun `set then get round-trips and clear releases`() {
        val id = Uuid.random()
        ConversationReasoningOverrides.set(id, ReasoningLevel.HIGH)
        assertEquals(ReasoningLevel.HIGH, ConversationReasoningOverrides.get(id))
        ConversationReasoningOverrides.clear(id)
        assertNull(ConversationReasoningOverrides.get(id))
    }

    @Test
    fun `one conversation's level does not leak into another`() {
        val a = Uuid.random()
        val b = Uuid.random()
        ConversationReasoningOverrides.set(a, ReasoningLevel.LOW)
        assertNull(ConversationReasoningOverrides.get(b))
        ConversationReasoningOverrides.clear(a)
    }

    @Test
    fun `a later set replaces the earlier one`() {
        val id = Uuid.random()
        ConversationReasoningOverrides.set(id, ReasoningLevel.OFF)
        ConversationReasoningOverrides.set(id, ReasoningLevel.MAX)
        assertEquals(ReasoningLevel.MAX, ConversationReasoningOverrides.get(id))
        ConversationReasoningOverrides.clear(id)
    }
}
