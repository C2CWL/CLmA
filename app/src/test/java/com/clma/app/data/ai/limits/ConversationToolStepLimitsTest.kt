package me.rerere.rikkahub.data.ai.limits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pins the per-conversation tool-step override holder: set/get/clear round-trips, one
 * conversation's cap never leaks into another, and nonsense values (< 1) act as a clear
 * rather than parking a turn at zero steps forever.
 */
class ConversationToolStepLimitsTest {

    @Test
    fun `an unset conversation has no override`() {
        assertNull(ConversationToolStepLimits.get(Uuid.random()))
    }

    @Test
    fun `set then get round-trips and clear releases`() {
        val id = Uuid.random()
        ConversationToolStepLimits.set(id, 12)
        assertEquals(12, ConversationToolStepLimits.get(id))
        ConversationToolStepLimits.clear(id)
        assertNull(ConversationToolStepLimits.get(id))
    }

    @Test
    fun `one conversation's cap does not leak into another`() {
        val a = Uuid.random()
        val b = Uuid.random()
        ConversationToolStepLimits.set(a, 5)
        assertNull(ConversationToolStepLimits.get(b))
        ConversationToolStepLimits.clear(a)
    }

    @Test
    fun `values below 1 are treated as a clear`() {
        val id = Uuid.random()
        ConversationToolStepLimits.set(id, 7)
        ConversationToolStepLimits.set(id, 0)
        assertNull(ConversationToolStepLimits.get(id))
    }
}
