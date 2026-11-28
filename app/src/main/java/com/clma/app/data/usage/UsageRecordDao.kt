package com.clma.app.data.usage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.clma.app.data.botmode.BotUsageTotal

@Dao
interface UsageRecordDao {
    @Insert
    suspend fun insert(record: UsageRecordEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureLifetimeRow(usage: LifetimeUsage)

    @Query("SELECT * FROM lifetime_usage WHERE id = 1")
    suspend fun lifetimeUsage(): LifetimeUsage?

    @Query(
        "UPDATE lifetime_usage SET input_tokens = input_tokens + MAX(:input, 0), " +
            "output_tokens = output_tokens + MAX(:output, 0), " +
            "cached_tokens = cached_tokens + MAX(:cached, 0) WHERE id = 1"
    )
    suspend fun addLifetimeTokens(input: Int, output: Int, cached: Int)

    @Transaction
    suspend fun insertAndAccumulate(record: UsageRecordEntity) {
        insert(record)
        addLifetimeTokens(record.inputTokens, record.outputTokens, record.cachedTokens)
    }

    // Legacy message totals and retained call rows overlap but have no shared call IDs.
    // Use the larger known total in each category rather than double-counting both sources.
    @Query(
        "UPDATE lifetime_usage SET input_tokens = MAX(input_tokens, :input), " +
            "output_tokens = MAX(output_tokens, :output), cached_tokens = MAX(cached_tokens, :cached), " +
            "legacy_baselined = 1 WHERE id = 1 AND legacy_baselined = 0"
    )
    suspend fun applyLegacyFloor(input: Long, output: Long, cached: Long)

    @Query("SELECT * FROM usage_records ORDER BY created_at_ms DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<UsageRecordEntity>

    @Query("SELECT * FROM usage_records WHERE created_at_ms >= :sinceMs ORDER BY created_at_ms DESC LIMIT :limit")
    suspend fun since(sinceMs: Long, limit: Int): List<UsageRecordEntity>

    @Query("SELECT COUNT(*) FROM usage_records")
    suspend fun count(): Int

    /**
     * Everything billed under one orchestration root, so that P2-13 can refuse a dispatch
     * before it happens instead of noticing afterwards.
     */
    @Query(
        "SELECT COALESCE(SUM(input_tokens), 0) + COALESCE(SUM(output_tokens), 0) " +
            "FROM usage_records WHERE parent_run_id = :parentRunId"
    )
    suspend fun tokensForParentRun(parentRunId: String): Long

    @Query(
        "SELECT COALESCE(SUM(input_tokens), 0) + COALESCE(SUM(output_tokens), 0) " +
            "FROM usage_records WHERE created_at_ms >= :sinceMs"
    )
    suspend fun tokensSince(sinceMs: Long): Long

    /**
     * P2-12b — every row for one conversation, oldest first, for the turn view. Bounded by
     * [UsageLedgerDefaults.QUERY_LIMIT] so a long conversation cannot load an unbounded list.
     */
    @Query(
        "SELECT * FROM usage_records WHERE conversation_id = :conversationId " +
            "ORDER BY created_at_ms ASC LIMIT :limit"
    )
    suspend fun forConversation(conversationId: String, limit: Int): List<UsageRecordEntity>

    @Query(
        "SELECT COALESCE(SUM(MAX(input_tokens - cached_tokens, 0) + MAX(output_tokens, 0)), 0) AS tokens, " +
            "COALESCE(SUM(COALESCE(provider_cost_usd, cost_micros / 1000000.0, 0)), 0.0) AS usd " +
            "FROM usage_records WHERE assistant_id = :botId AND created_at_ms >= :sinceMs"
    )
    suspend fun botUsageSince(botId: String, sinceMs: Long): BotUsageTotal

    /** Retention sweep; returns the number of rows removed. */
    @Query("DELETE FROM usage_records WHERE created_at_ms < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}
