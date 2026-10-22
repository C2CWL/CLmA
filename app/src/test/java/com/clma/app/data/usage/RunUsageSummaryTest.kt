package me.rerere.rikkahub.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunUsageSummaryTest {

    private fun row(
        id: String,
        runId: String?,
        input: Int = 0,
        output: Int = 0,
    ): UsageRecordEntity = UsageRecordEntity(
        id = id,
        createdAtMs = 0L,
        purpose = UsagePurpose.SUBAGENT.name,
        runId = runId,
        inputTokens = input,
        outputTokens = output,
        totalTokens = input + output,
        cachedTokens = 0,
        cachedTokensReported = false,
    )

    @Test fun `an empty ledger is zero calls`() {
        val summary = RunUsageSummaryFactory.from(emptyList(), runId = "run-1")
        assertEquals(0, summary.calls)
        assertEquals(0L, summary.inputTokens)
        assertEquals(0L, summary.outputTokens)
        assertEquals(0L, summary.totalTokens)
    }

    @Test fun `sums only the rows belonging to this run`() {
        val summary = RunUsageSummaryFactory.from(
            listOf(
                row("a", "run-1", input = 100, output = 20),
                row("b", "run-2", input = 999, output = 999),
                row("c", "run-1", input = 5, output = 7),
            ),
            runId = "run-1",
        )
        assertEquals(2, summary.calls)
        assertEquals(105L, summary.inputTokens)
        assertEquals(27L, summary.outputTokens)
        assertEquals(132L, summary.totalTokens)
    }

    @Test fun `rows with no run id are ignored`() {
        val summary = RunUsageSummaryFactory.from(
            listOf(row("a", runId = null, input = 500, output = 500)),
            runId = "run-1",
        )
        assertEquals(0, summary.calls)
        assertEquals(0L, summary.totalTokens)
    }

    @Test fun `a row that billed nothing still counts as a call`() {
        val summary = RunUsageSummaryFactory.from(listOf(row("a", "run-1")), runId = "run-1")
        assertEquals(1, summary.calls)
        assertEquals(0L, summary.totalTokens)
    }

    @Test fun `sums widen past the Int range`() {
        val summary = RunUsageSummaryFactory.from(
            listOf(
                row("a", "run-1", input = 2_000_000_000),
                row("b", "run-1", input = 2_000_000_000),
            ),
            runId = "run-1",
        )
        assertEquals(4_000_000_000L, summary.inputTokens)
    }

    @Test fun `wake line is null when nothing was billed`() {
        assertNull(
            RunUsageSummaryFactory.line(
                RunUsageSummary(calls = 0, inputTokens = 0, outputTokens = 0),
            ),
        )
    }

    @Test fun `wake line reports calls and both directions`() {
        val line = RunUsageSummaryFactory.line(
            RunUsageSummary(calls = 3, inputTokens = 1234, outputTokens = 56),
        )
        assertTrue(line!!, line.contains("3"))
        assertTrue(line, line.contains("1234"))
        assertTrue(line, line.contains("56"))
    }
}
