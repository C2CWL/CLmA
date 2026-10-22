package me.rerere.rikkahub.data.usage

import me.rerere.ai.core.TokenUsage
import java.util.UUID

object UsageLedgerDefaults {
    /** How long a row survives before the retention sweep removes it. */
    const val RETENTION_DAYS = 90

    /** Upper bound for a single read, so an export can never load an unbounded list. */
    const val QUERY_LIMIT = 1000

    /** How often the retention sweep is allowed to run. */
    const val SWEEP_INTERVAL_MS = 24L * 60L * 60L * 1000L

    /**
     * P2-12c - upper bound for the statistics page's window read. Larger than [QUERY_LIMIT]
     * because a 90-day window of interactive rows can exceed that, but still finite, so a runaway
     * ledger can never load unbounded into memory.
     */
    const val STATS_QUERY_LIMIT = 20_000
}

/**
 * Pure mapping and arithmetic, extracted from [UsageLedger] so it can be unit tested without
 * a Room runtime.
 */
object UsageRecordMapper {
    fun retentionCutoffMs(
        nowMs: Long,
        retentionDays: Int = UsageLedgerDefaults.RETENTION_DAYS,
    ): Long = nowMs - retentionDays * 24L * 60L * 60L * 1000L

    fun from(
        id: String,
        purpose: UsagePurpose,
        usage: TokenUsage,
        createdAtMs: Long,
        providerId: String? = null,
        modelId: String? = null,
        assistantId: String? = null,
        conversationId: String? = null,
        runId: String? = null,
        parentRunId: String? = null,
        costMicros: Long? = null,
        priceVersionId: String? = null,
        streaming: Boolean = false,
        latencyMs: Long? = null,
    ): UsageRecordEntity = UsageRecordEntity(
        id = id,
        createdAtMs = createdAtMs,
        purpose = purpose.name,
        providerId = providerId,
        modelId = modelId,
        assistantId = assistantId,
        conversationId = conversationId,
        runId = runId,
        parentRunId = parentRunId,
        inputTokens = usage.promptTokens,
        outputTokens = usage.completionTokens,
        totalTokens = usage.totalTokens,
        cachedTokens = usage.cachedTokens,
        cachedTokensReported = usage.cachedTokensReported,
        cacheMissTokens = usage.cacheMissTokens,
        cacheWriteTokens = usage.cacheWriteTokens,
        reasoningTokens = usage.reasoningTokens,
        providerCostUsd = usage.cost,
        costMicros = costMicros,
        priceVersionId = priceVersionId,
        streaming = streaming,
        latencyMs = latencyMs,
    )
}

/**
 * P2-11c accounting ledger.
 *
 * Callers on the chat path must treat this as best-effort telemetry: it is not allowed to
 * break a turn. Wrap [record] in a try/catch (or dispatch it into a supervisor scope) rather
 * than letting a database hiccup surface as a failed message.
 */
class UsageLedger(
    private val dao: UsageRecordDao,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private var lastSweepMs: Long = 0

    suspend fun record(
        purpose: UsagePurpose,
        usage: TokenUsage,
        providerId: String? = null,
        modelId: String? = null,
        assistantId: String? = null,
        conversationId: String? = null,
        runId: String? = null,
        parentRunId: String? = null,
        costMicros: Long? = null,
        priceVersionId: String? = null,
        streaming: Boolean = false,
        latencyMs: Long? = null,
    ): UsageRecordEntity {
        val at = nowMs()
        val row = UsageRecordMapper.from(
            id = newId(),
            purpose = purpose,
            usage = usage,
            createdAtMs = at,
            providerId = providerId,
            modelId = modelId,
            assistantId = assistantId,
            conversationId = conversationId,
            runId = runId,
            parentRunId = parentRunId,
            costMicros = costMicros,
            priceVersionId = priceVersionId,
            streaming = streaming,
            latencyMs = latencyMs,
        )
        dao.insert(row)
        // Retention: at most one sweep per day, and only when something was written.
        if (at - lastSweepMs >= UsageLedgerDefaults.SWEEP_INTERVAL_MS) {
            lastSweepMs = at
            prune(at)
        }
        return row
    }

    /** Removes rows older than [UsageLedgerDefaults.RETENTION_DAYS]; returns rows removed. */
    suspend fun prune(at: Long = nowMs()): Int =
        dao.deleteOlderThan(UsageRecordMapper.retentionCutoffMs(at))

    suspend fun tokensForOrchestration(parentRunId: String): Long =
        dao.tokensForParentRun(parentRunId)

    /**
     * P2-12b — the rows a conversation's turn view aggregates. Read-only telemetry, so a
     * failure here must stay contained at the call site (the VM falls back to `message.usage`).
     */
    suspend fun recordsForConversation(
        conversationId: String,
        limit: Int = UsageLedgerDefaults.QUERY_LIMIT,
    ): List<UsageRecordEntity> = dao.forConversation(conversationId, limit)

    /**
     * P2-12c - the window the statistics page aggregates: every row since [sinceMs], newest first,
     * capped at [limit]. It reuses [UsageRecordDao.since] rather than adding a Dao method, so the
     * ledger's test double (`UsageCallRecorderTest.FakeDao`) needs no change.
     */
    suspend fun recordsSince(
        sinceMs: Long,
        limit: Int = UsageLedgerDefaults.STATS_QUERY_LIMIT,
    ): List<UsageRecordEntity> = dao.since(sinceMs, limit)
}
