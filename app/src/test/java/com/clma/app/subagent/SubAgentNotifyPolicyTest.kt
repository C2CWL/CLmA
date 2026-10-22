package me.rerere.rikkahub.subagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the parent-wake policy for finished background runs: cancelled runs and noResult
 * successes stay silent (the first reads as "the app kept talking after I pressed stop",
 * the second defeats the point of fire-and-forget), while failures and timeouts always
 * notify so background work never fails in silence.
 */
class SubAgentNotifyPolicyTest {

    private fun runWith(status: SubAgentStatus, noResult: Boolean = false) = SubAgentRun(
        id = "r1",
        parentChatId = "parent",
        parentAssistantId = "assistant",
        label = "label",
        task = "task",
        modelId = null,
        tools = null,
        runInBackground = true,
        noResult = noResult,
        timeoutSeconds = 60,
        maxTrips = 12,
        status = status,
        startedAtMs = 0L,
    )

    @Test
    fun `a successful background run with a result notifies`() {
        assertTrue(shouldNotifyParent(runWith(SubAgentStatus.SUCCEEDED)))
    }

    @Test
    fun `a successful noResult background run stays silent`() {
        assertFalse(shouldNotifyParent(runWith(SubAgentStatus.SUCCEEDED, noResult = true)))
    }

    @Test
    fun `a cancelled run stays silent even when it would have carried a result`() {
        assertFalse(shouldNotifyParent(runWith(SubAgentStatus.CANCELLED)))
        assertFalse(shouldNotifyParent(runWith(SubAgentStatus.CANCELLED, noResult = true)))
    }

    @Test
    fun `failures and timeouts notify even for noResult runs`() {
        assertTrue(shouldNotifyParent(runWith(SubAgentStatus.FAILED)))
        assertTrue(shouldNotifyParent(runWith(SubAgentStatus.FAILED, noResult = true)))
        assertTrue(shouldNotifyParent(runWith(SubAgentStatus.TIMED_OUT)))
        assertTrue(shouldNotifyParent(runWith(SubAgentStatus.TIMED_OUT, noResult = true)))
    }

    @Test
    fun `non-terminal runs never notify`() {
        assertFalse(shouldNotifyParent(runWith(SubAgentStatus.PENDING)))
        assertFalse(shouldNotifyParent(runWith(SubAgentStatus.RUNNING)))
    }
}
