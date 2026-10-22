package me.rerere.rikkahub.data.usage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface UsageRecordDao {
    @Insert
    suspend fun insert(record: UsageRecordEntity)

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

    /** Retention sweep; returns the number of rows removed. */
    @Query("DELETE FROM usage_records WHERE created_at_ms < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}
