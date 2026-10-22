package me.rerere.rikkahub.data.usage

/**
 * P2-13 — what one sub-agent run cost, read back from the ledger once the run is over.
 *
 * [me.rerere.rikkahub.subagent.SubAgentRun] has carried `tokensIn` / `tokensOut` since Phase
 * 11, and the dispatch tool has always encoded them, but nothing ever wrote them: every
 * dispatch answered "0 in / 0 out". P2-12d gave each sub-agent call a `run_id` in the ledger;
 * this object turns those rows into the numbers the parent should actually see.
 *
 * Pure: the filter and the arithmetic are the whole thing, so they unit-test without Room.
 */
data class RunUsageSummary(
    /** Model round trips — the same unit [UsageRecordEntity] is one row of. */
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

object RunUsageSummaryFactory {

    /**
     * Sum the rows of [records] that belong to [runId].
     *
     * Filtering by `run_id` rather than trusting the whole conversation is deliberate: the
     * sub-agent's conversation also carries rows the run did not pay for (title generation and
     * the like have no run id — P2-12d left those call sites untouched), and counting them
     * would inflate every run. Rows the provider never reported usage for are not written at
     * all (see [UsageCallRecorder]), so an empty result means "nothing was billed", not
     * "unknown".
     */
    fun from(records: List<UsageRecordEntity>, runId: String): RunUsageSummary {
        var calls = 0
        var input = 0L
        var output = 0L
        for (row in records) {
            if (row.runId != runId) continue
            calls++
            // The entity stores Int columns; the running sums are widened on purpose so a long
            // orchestration cannot wrap.
            input += row.inputTokens.toLong()
            output += row.outputTokens.toLong()
        }
        return RunUsageSummary(calls = calls, inputTokens = input, outputTokens = output)
    }

    /**
     * One short line for the parent conversation's wake-up message, or null when the run billed
     * nothing (a dispatch that failed before it could call a model, typically) — an empty
     * "0 calls" line would be noise, not information.
     */
    fun line(summary: RunUsageSummary): String? {
        if (summary.calls == 0) return null
        return "Usage: ${summary.calls} model calls, ${summary.inputTokens} input / " +
            "${summary.outputTokens} output tokens."
    }
}
