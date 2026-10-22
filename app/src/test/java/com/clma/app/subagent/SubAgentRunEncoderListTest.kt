package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * P2-14b — `subagent_list` must report the same numbers as `subagent_get`.
 *
 * Before this card a list entry carried no tokens at all, so a caller that dispatched several runs
 * had no way to see their cost without one `subagent_get` per run.
 */
class SubAgentRunEncoderListTest {

    private fun run(
        id: String,
        tokensIn: Long = 0,
        tokensOut: Long = 0,
        calls: Int = 0,
        trips: Int = 0,
    ): SubAgentRun = SubAgentRun(
        id = id,
        parentChatId = "chat-1",
        parentAssistantId = "asst-1",
        label = "label-$id",
        task = "task",
        modelId = null,
        tools = null,
        runInBackground = false,
        timeoutSeconds = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
        maxTrips = SubAgentDefaults.DEFAULT_MAX_TRIPS,
        status = SubAgentStatus.SUCCEEDED,
        startedAtMs = 1_000L,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        usageCalls = calls,
        tripCount = trips,
    )

    @Test
    fun `empty list encodes to an empty array`() {
        assertEquals(0, encodeRuns(emptyList()).size)
    }

    @Test
    fun `a list entry carries the same counter keys as the single-run envelope`() {
        val r = run("r1", tokensIn = 120, tokensOut = 34, calls = 5, trips = 2)
        val entry = encodeRuns(listOf(r)).single().jsonObject

        assertEquals("120", entry.getValue("tokens_in").jsonPrimitive.content)
        assertEquals("34", entry.getValue("tokens_out").jsonPrimitive.content)
        assertEquals("5", entry.getValue("calls").jsonPrimitive.content)
        assertEquals("2", entry.getValue("trip_count").jsonPrimitive.content)

        // Parity with subagent_get: the same keys carry the same values in both views.
        val single = encodeRun(r)
        for (key in listOf("tokens_in", "tokens_out", "calls", "trip_count")) {
            assertEquals(
                single.getValue(key).jsonPrimitive.content,
                entry.getValue(key).jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `an unbilled run reports zeroes rather than omitting the keys`() {
        val entry = encodeRuns(listOf(run("r1"))).single().jsonObject
        assertEquals("0", entry.getValue("tokens_in").jsonPrimitive.content)
        assertEquals("0", entry.getValue("tokens_out").jsonPrimitive.content)
        assertEquals("0", entry.getValue("calls").jsonPrimitive.content)
    }

    @Test
    fun `every run in the list is encoded, in order`() {
        val out = encodeRuns(listOf(run("a"), run("b"), run("c")))
        assertEquals(3, out.size)
        assertEquals("a", out[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("c", out[2].jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun `model id is omitted when the run inherits the parent model`() {
        assertFalse(encodeRuns(listOf(run("r1"))).single().jsonObject.containsKey("model_id"))
    }
}
