package com.clma.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Stable string values for [ContextOperationLogEntity.operationType]. */
object ContextOperationType {
    const val COMPACT = "COMPACT"
    const val CLEAR = "CLEAR"
}

/** Stable string values for [ContextOperationLogEntity.result]. */
object ContextOperationResult {
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

/**
 * Audit trail for context window operations (compression / clearing).
 *
 * Intentionally NOT linked with a foreign key to [ConversationEntity]: unlike a compaction
 * snapshot, a log entry must survive deleting the conversation it describes, so the history
 * page can still explain what happened.
 */
@Entity(tableName = "context_operation_log")
data class ContextOperationLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("id")
    val id: Long = 0L,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("conversation_title")
    val conversationTitle: String? = null,
    @ColumnInfo("operation_type")
    val operationType: String,
    @ColumnInfo("is_auto")
    val isAuto: Boolean = false,
    @ColumnInfo("source_token_estimate")
    val sourceTokenEstimate: Int = 0,
    @ColumnInfo("summary_token_estimate")
    val summaryTokenEstimate: Int = 0,
    @ColumnInfo("message_count")
    val messageCount: Int = 0,
    @ColumnInfo("result")
    val result: String = ContextOperationResult.SUCCESS,
    @ColumnInfo("details")
    val details: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
