package me.rerere.rikkahub.subagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the completion-notice text a finished background run injects into the parent's next
 * model request: the not-a-user-message framing (the notice rides in the USER slot, so the
 * framing is what keeps the model from answering it like a message), the status/error/result
 * sections, and the noResult guard that must keep a suppressed result out of the notice even
 * if the formatter is reached by accident.
 */
class SubAgentCompletionNoticeTest {

    private fun runWith(
        status: SubAgentStatus,
        noResult: Boolean = false,
        result: String? = "the answer is 42",
        error: String? = null,
    ) = SubAgentRun(
        id = "r1",
        parentChatId = "parent",
        parentAssistantId = "assistant",
        label = "research-42",
        task = "task",
        modelId = null,
        tools = null,
        runInBackground = true,
        noResult = noResult,
        timeoutSeconds = 60,
        maxTrips = 12,
        status = status,
        result = result,
        error = error,
        startedAtMs = 0L,
    )

    @Test
    fun `the notice declares it is not a user message`() {
        val text = SubAgentCompletionNotice.format(runWith(SubAgentStatus.SUCCEEDED), null)
        assertTrue(text.contains("not a user message"))
        assertTrue(text.contains("System notification"))
    }

    @Test
    fun `a succeeded notice carries label, status, usage and result`() {
        val text = SubAgentCompletionNotice.format(
            runWith(SubAgentStatus.SUCCEEDED),
            "Usage: 3 model calls, 100 input / 50 output tokens.",
        )
        assertTrue(text.contains("research-42"))
        assertTrue(text.contains("SUCCEEDED"))
        assertTrue(text.contains("3 model calls"))
        assertTrue(text.contains("the answer is 42"))
    }

    @Test
    fun `a failed notice carries the error and no result section when absent`() {
        val text = SubAgentCompletionNotice.format(
            runWith(SubAgentStatus.FAILED, result = null, error = "boom"),
            null,
        )
        assertTrue(text.contains("FAILED"))
        assertTrue(text.contains("Error: boom"))
        assertFalse(text.contains("Result:"))
    }

    @Test
    fun `a noResult run never leaks its result into the notice`() {
        val text = SubAgentCompletionNotice.format(
            runWith(SubAgentStatus.SUCCEEDED, noResult = true),
            null,
        )
        assertFalse(text.contains("the answer is 42"))
        assertFalse(text.contains("Result:"))
    }

    @Test
    fun `a null usage line is omitted`() {
        val text = SubAgentCompletionNotice.format(runWith(SubAgentStatus.SUCCEEDED), null)
        assertFalse(text.contains("Usage:"))
    }
}
