package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-15 — the dispatch envelope's budget headroom (PHASE2.md §9.2 #7, second half).
 *
 * A parent could be told what a dispatch already cost (§9.2 #7, first half: P2-13's
 * `tokens_in` / `tokens_out` / `calls`) but never how much of its orchestration ceiling was left,
 * so the only way to find the wall was to hit it. [encodeRun] now takes the headroom the engine
 * measured at admission and reports it as `budget_remaining`.
 *
 * The field is deliberately absent whenever there is no ceiling — an install that never configures
 * an orchestration budget must keep serialising a run byte-for-byte as it did before this card
 * (red line 1), which is why the null case is pinned as hard as the populated one.
 */
class SubAgentRunEncoderBudgetTest {

    private fun makeRun(): SubAgentRun = SubAgentRun(
        id = "r1",
        parentChatId = "chat-1",
        parentAssistantId = "asst-1",
        label = "label",
        task = "task",
        modelId = null,
        tools = null,
        runInBackground = false,
        noResult = false,
        timeoutSeconds = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
        maxTrips = SubAgentDefaults.DEFAULT_MAX_TRIPS,
        status = SubAgentStatus.SUCCEEDED,
        result = "the answer",
        startedAtMs = System.currentTimeMillis(),
    )

    @Test fun `budget_remaining is absent when no ceiling was measured`() {
        val json = encodeRun(makeRun())
        assertFalse(json.containsKey("budget_remaining"))
    }

    @Test fun `budget_remaining is absent when the headroom is explicitly null`() {
        val json = encodeRun(makeRun(), budgetRemaining = null)
        assertFalse(json.containsKey("budget_remaining"))
    }

    @Test fun `budget_remaining carries the headroom when a ceiling is configured`() {
        val json = encodeRun(makeRun(), budgetRemaining = 1234L)
        assertEquals("1234", json["budget_remaining"]?.toString())
    }

    @Test fun `zero headroom is reported rather than omitted`() {
        // "nothing left" (0) and "unlimited" (absent) must not collapse into the same envelope —
        // the same distinction OrchestrationBudget.remaining draws between 0 and null.
        val json = encodeRun(makeRun(), budgetRemaining = 0L)
        assertTrue(json.containsKey("budget_remaining"))
        assertEquals("0", json["budget_remaining"]?.toString())
    }

    @Test fun `the headroom does not disturb the run's own counters`() {
        val json = encodeRun(makeRun(), budgetRemaining = 7L)
        assertEquals("7", json["budget_remaining"]?.toString())
        assertTrue(json.containsKey("tokens_in"))
        assertTrue(json.containsKey("tokens_out"))
        assertTrue(json.containsKey("calls"))
        assertTrue(json.containsKey("trip_count"))
    }

    @Test fun `encodeRuns (the list view) never carries a ceiling`() {
        // The list is a per-run view with no conversation ceiling to report; subagent_get uses the
        // parameterless encodeRun, so both stay exactly as P2-14 left them.
        val json = encodeRuns(listOf(makeRun()))
        assertFalse(json.toString().contains("budget_remaining"))
    }
}
