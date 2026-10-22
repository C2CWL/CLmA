package me.rerere.rikkahub.data.usage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P2-12d — the run-attribution registry's mark / lookup / unmark semantics.
 *
 * The registry is a process-wide singleton, so every test clears it on the way in and out; the
 * whole point of these cases is that a finished run cannot leak its id onto later calls.
 */
class UsageRunContextsTest {

    @After
    fun tearDown() = UsageRunContexts.clear()

    @Test
    fun `mark then get returns the attribution`() {
        UsageRunContexts.mark(conversationId = "conv-1", runId = "run-1", parentRunId = "parent-1")

        val attribution = UsageRunContexts.get("conv-1")

        assertEquals("run-1", attribution?.runId)
        assertEquals("parent-1", attribution?.parentRunId)
        assertEquals(UsagePurpose.SUBAGENT, attribution?.purpose)
    }

    @Test
    fun `an unknown conversation has no attribution`() {
        assertNull(UsageRunContexts.get("never-marked"))
    }

    @Test
    fun `a null conversation id has no attribution`() {
        assertNull(UsageRunContexts.get(null))
    }

    @Test
    fun `marking a conversation again replaces the attribution and drops the old run`() {
        UsageRunContexts.mark("conv-1", runId = "run-1", parentRunId = "parent-1")
        UsageRunContexts.mark("conv-1", runId = "run-2", parentRunId = "parent-2")

        assertEquals("run-2", UsageRunContexts.get("conv-1")?.runId)
        assertEquals(1, UsageRunContexts.size)

        // The superseded run's forward mapping is gone: unmarking it must not touch conv-1.
        UsageRunContexts.unmarkRun("run-1")
        assertEquals("run-2", UsageRunContexts.get("conv-1")?.runId)
    }

    @Test
    fun `unmarkRun removes the conversation entry`() {
        UsageRunContexts.mark("conv-1", runId = "run-1", parentRunId = null)

        UsageRunContexts.unmarkRun("run-1")

        assertNull(UsageRunContexts.get("conv-1"))
        assertEquals(0, UsageRunContexts.size)
    }

    @Test
    fun `unmarkConversation removes the entry`() {
        UsageRunContexts.mark("conv-1", runId = "run-1", parentRunId = null)

        UsageRunContexts.unmarkConversation("conv-1")

        assertNull(UsageRunContexts.get("conv-1"))
        assertEquals(0, UsageRunContexts.size)
    }

    @Test
    fun `unmarking something unknown is a no-op`() {
        UsageRunContexts.mark("conv-1", runId = "run-1", parentRunId = null)

        UsageRunContexts.unmarkRun("run-absent")
        UsageRunContexts.unmarkConversation("conv-absent")

        assertEquals("run-1", UsageRunContexts.get("conv-1")?.runId)
        assertEquals(1, UsageRunContexts.size)
    }

    @Test
    fun `clear drops everything`() {
        UsageRunContexts.mark("conv-1", runId = "run-1", parentRunId = null)
        UsageRunContexts.mark("conv-2", runId = "run-2", parentRunId = null)

        UsageRunContexts.clear()

        assertEquals(0, UsageRunContexts.size)
        assertNull(UsageRunContexts.get("conv-1"))
    }
}
