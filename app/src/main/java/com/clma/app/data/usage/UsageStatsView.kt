package me.rerere.rikkahub.data.usage

import java.time.Instant
import java.time.ZoneId

/**
 * P2-12c — the ledger read behind the statistics page.
 *
 * The page used to read `message.usage` out of the message JSON only, so it could not say which
 * assistant, day, purpose or model a token went to, and could not price a call at all. The ledger
 * (P2-11) records one row per model round trip carrying exactly those dimensions, so this file
 * turns a window of rows into five rankings plus the grand total they are a share of.
 *
 * Pure by construction: no Room runtime, no Android, no coroutines. [UsageRecordEntity] is a plain
 * data class — only its annotations need androidx.room on the compile classpath — and the zone is
 * injected so the day buckets are deterministic in a test.
 *
 * Two invariants come straight from P2-11a / P2-12b and are load-bearing:
 *  - a missing price stays missing: null is never summed as zero, and a bucket with no priced row
 *    reports null rather than "$0.00";
 *  - cache totals sum only the rows that actually reported cache fields, so a provider that does
 *    not report them cannot drag the hit rate toward zero.
 *
 * D6 adds a third: a throughput figure is built only from the rows that reported a latency, in
 * both the numerator and the denominator — an unmeasured call can neither inflate nor dilute it.
 */

/** One row of a dimension's ranking: the key plus everything summed under it. */
data class UsageStatBucket(
    val key: String,
    val callCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheHitTokens: Long,
    val cachePromptTokens: Long,
    val cacheReported: Boolean,
    val providerCostUsd: Double?,
    val costMicros: Long?,
    /**
     * D6 — output tokens written by the rows that reported a latency. The rate below is built
     * from this subset, so a call nobody measured cannot dilute it (P2-19's rule, applied to a
     * bucket instead of a single turn).
     */
    val measuredOutputTokens: Long,
    /**
     * D6 — summed `latency_ms` over the rows that reported one. A ledger row is one model round
     * trip and a tool runs *between* round trips, so this is generation time with tool
     * execution excluded by construction — the denominator a throughput figure should use.
     */
    val generationMs: Long,
) {
    val totalTokens: Long get() = inputTokens + outputTokens

    /** D6 — output throughput over [generationMs], or null when nothing reported a latency. */
    val tokensPerSecond: Double? get() =
        generationMs.takeIf { it > 0 }?.let { measuredOutputTokens * 1000.0 / it }

    /**
     * Cached share of the input, as a whole percent, over the rows that reported cache fields
     * only; null when none did — "unknown" must never read as "0%".
     */
    val cacheHitRate: Int? get() =
        if (cacheReported && cachePromptTokens > 0L) {
            (cacheHitTokens * 100L / cachePromptTokens).toInt()
        } else {
            null
        }

    /** The only price known was recomputed from the local price table, so the UI marks it. */
    val estimatedCostOnly: Boolean get() = providerCostUsd == null && costMicros != null
}

/**
 * The four rankings of one window, plus the grand total.
 *
 * [byDay] is newest first and its keys are ISO dates; the other four are largest first and their
 * keys are a purpose name, a provider name, a model id, and an assistant id. An empty window
 * yields a zeroed [total] and five empty lists — never null — so the UI has a single shape to
 * render.
 */
data class LedgerStatsView(
    val total: UsageStatBucket,
    val byDay: List<UsageStatBucket>,
    val byPurpose: List<UsageStatBucket>,
    val byProvider: List<UsageStatBucket>,
    val byModel: List<UsageStatBucket>,
    val byAssistant: List<UsageStatBucket>,
) {
    /** This bucket's share of the window's tokens, 0f when the window is empty. */
    fun shareOfTokens(bucket: UsageStatBucket): Float =
        if (total.totalTokens <= 0L) 0f else bucket.totalTokens.toFloat() / total.totalTokens

    companion object {
        val EMPTY: LedgerStatsView = LedgerStatsView(
            total = UsageStatBucket(
                key = "",
                callCount = 0,
                inputTokens = 0L,
                outputTokens = 0L,
                cacheHitTokens = 0L,
                cachePromptTokens = 0L,
                cacheReported = false,
                providerCostUsd = null,
                costMicros = null,
                measuredOutputTokens = 0L,
                generationMs = 0L,
            ),
            byDay = emptyList(),
            byPurpose = emptyList(),
            byProvider = emptyList(),
            byModel = emptyList(),
            byAssistant = emptyList(),
        )
    }
}

object UsageStatsFactory {
    /** Key for a dimension whose value is absent from the row (e.g. a null model id). */
    const val UNKNOWN_KEY = "unknown"

    /**
     * Buckets the window's rows along the five axes the stats page shows.
     *
     * @param records every ledger row in the window, any order.
     * @param zone device zone, so the day buckets line up with the rest of the page.
     * @param assistantOfConversation resolves a conversation id to its assistant id. It is consulted
     *        only when the row itself carries no `assistantId` — the auxiliary calls
     *        (TITLE / SUGGESTION / COMPACTION) run under a conversation but do not set the assistant
     *        field, so without this fallback they would all collapse into [UNKNOWN_KEY].
     */
    fun build(
        records: List<UsageRecordEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
        assistantOfConversation: (String) -> String? = { null },
    ): LedgerStatsView {
        if (records.isEmpty()) return LedgerStatsView.EMPTY

        val byDay = group(records) { row ->
            Instant.ofEpochMilli(row.createdAtMs).atZone(zone).toLocalDate().toString()
        }.sortedByDescending { it.key }

        val byPurpose = group(records) { it.purpose }.sortedWith(RANKING)

        // The column is named `providerId` but the recorder stores the provider's **display name**
        // (UsageCallRecorder passes `providerSetting.name`), so this ranking needs no lookup and
        // reads as the "which API key / account did this go to" axis.
        val byProvider = group(records) { it.providerId ?: UNKNOWN_KEY }.sortedWith(RANKING)

        val byModel = group(records) { it.modelId ?: UNKNOWN_KEY }.sortedWith(RANKING)

        val byAssistant = group(records) { row ->
            row.assistantId
                ?: row.conversationId?.let(assistantOfConversation)
                ?: UNKNOWN_KEY
        }.sortedWith(RANKING)

        return LedgerStatsView(
            total = aggregate(records, key = ""),
            byDay = byDay,
            byPurpose = byPurpose,
            byProvider = byProvider,
            byModel = byModel,
            byAssistant = byAssistant,
        )
    }

    /** Largest first, then most calls, then a stable key order for equal buckets. */
    private val RANKING: Comparator<UsageStatBucket> =
        compareByDescending<UsageStatBucket> { it.totalTokens }
            .thenByDescending { it.callCount }
            .thenBy { it.key }

    private fun group(
        records: List<UsageRecordEntity>,
        keyOf: (UsageRecordEntity) -> String,
    ): List<UsageStatBucket> {
        val accs = LinkedHashMap<String, Acc>()
        for (row in records) accs.getOrPut(keyOf(row)) { Acc() }.add(row)
        return accs.map { (key, acc) -> acc.toBucket(key) }
    }

    private fun aggregate(records: List<UsageRecordEntity>, key: String): UsageStatBucket {
        val acc = Acc()
        records.forEach(acc::add)
        return acc.toBucket(key)
    }

    private class Acc {
        private var calls = 0
        private var input = 0L
        private var output = 0L
        private var cacheHit = 0L
        private var cachePrompt = 0L
        private var cacheReported = false
        private var providerCost: Double? = null
        private var costMicros: Long? = null
        private var measuredOutputTokens = 0L
        private var generationMs = 0L

        fun add(row: UsageRecordEntity) {
            calls++
            input += row.inputTokens
            output += row.outputTokens
            if (row.cachedTokensReported) {
                cacheReported = true
                cacheHit += row.cachedTokens
                cachePrompt += row.inputTokens
            }
            // Missing stays missing: a null price is never summed as zero.
            row.providerCostUsd?.let { providerCost = (providerCost ?: 0.0) + it }
            row.costMicros?.let { costMicros = (costMicros ?: 0L) + it }
            // D6 — only the rows that reported a latency take part in the rate, in both the
            // numerator and the denominator, so an unmeasured call cannot skew it.
            row.latencyMs?.let { latency ->
                generationMs += latency
                measuredOutputTokens += row.outputTokens
            }
        }

        fun toBucket(key: String): UsageStatBucket = UsageStatBucket(
            key = key,
            callCount = calls,
            inputTokens = input,
            outputTokens = output,
            cacheHitTokens = cacheHit,
            cachePromptTokens = cachePrompt,
            cacheReported = cacheReported,
            providerCostUsd = providerCost,
            costMicros = costMicros,
            measuredOutputTokens = measuredOutputTokens,
            generationMs = generationMs,
        )
    }
}
