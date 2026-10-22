package me.rerere.rikkahub.data.usage

/**
 * D10 — the coarse buckets the statistics page shows a purpose ranking under.
 *
 * The ledger's purpose is deliberately fine-grained (P2-11a): one bucket per caller class is right
 * for the message footer, where the reader wants to know which call site a token went to. On the
 * statistics page that becomes a list of one-row categories — a single chat turn is split across
 * MAIN and TOOL_LOOP even though the user thinks of it as one conversation — so this maps the fine
 * keys onto five groups and merges their numbers. The question the ranking answers changes from
 * "which call site ran" to "what is the spend going to".
 *
 * [UsageStatsFactory] keeps emitting the raw purposes; this is applied by the page only, so the
 * ledger's own contract (and its tests) are untouched.
 *
 * Pure: no Room, no Android, no coroutines.
 */
object UsagePurposeGroups {

    /** The user-facing turn plus its follow-up calls — what "a conversation" costs. */
    const val CONVERSATION = "conversation"

    /** Dispatched sub-agent runs. */
    const val SUBAGENT = "subagent"

    /** Cron jobs and workflow steps: work that ran without the user watching. */
    const val AUTOMATION = "automation"

    /** The assistant's own auxiliary calls: title, suggestions, compaction, memory, translation. */
    const val ASSISTANT = "assistant"

    /** Everything unclassified. */
    const val OTHER = "other"

    /** The group a raw ledger purpose key belongs to. */
    fun groupOf(purposeKey: String): String = when (purposeKey) {
        UsagePurpose.MAIN.name, UsagePurpose.TOOL_LOOP.name -> CONVERSATION
        UsagePurpose.SUBAGENT.name -> SUBAGENT
        UsagePurpose.CRON.name, UsagePurpose.WORKFLOW.name -> AUTOMATION
        UsagePurpose.TITLE.name,
        UsagePurpose.SUGGESTION.name,
        UsagePurpose.COMPACTION.name,
        UsagePurpose.MEMORY_EXTRACT.name,
        UsagePurpose.TRANSLATION.name,
        UsagePurpose.SKILL_TEST.name,
        -> ASSISTANT
        else -> OTHER
    }

    /**
     * Merge a purpose ranking into its groups, largest first.
     *
     * Every field is summed, with the ledger's two invariants preserved: `cacheReported` is the
     * OR of the rows, so a group only claims a hit rate when at least one of its rows reported
     * cache fields; and a price stays null when none of the merged rows had one — a missing price
     * is never summed as zero, exactly as in [UsageStatsFactory].
     */
    fun merge(buckets: List<UsageStatBucket>): List<UsageStatBucket> =
        buckets
            .groupBy { groupOf(it.key) }
            .map { (group, rows) -> sum(group, rows) }
            .sortedWith(
                compareByDescending<UsageStatBucket> { it.totalTokens }
                    .thenByDescending { it.callCount }
                    .thenBy { it.key },
            )

    private fun sum(key: String, rows: List<UsageStatBucket>): UsageStatBucket = UsageStatBucket(
        key = key,
        callCount = rows.sumOf { it.callCount },
        inputTokens = rows.sumOf { it.inputTokens },
        outputTokens = rows.sumOf { it.outputTokens },
        cacheHitTokens = rows.sumOf { it.cacheHitTokens },
        cachePromptTokens = rows.sumOf { it.cachePromptTokens },
        cacheReported = rows.any { it.cacheReported },
        providerCostUsd = rows.mapNotNull { it.providerCostUsd }.takeIf { it.isNotEmpty() }?.sum(),
        costMicros = rows.mapNotNull { it.costMicros }.takeIf { it.isNotEmpty() }?.sum(),
        // D6 — summing both terms keeps the group's rate the true aggregate (total measured
        // output over total generation time), not an average of the rows' rates.
        measuredOutputTokens = rows.sumOf { it.measuredOutputTokens },
        generationMs = rows.sumOf { it.generationMs },
    )
}
