package me.rerere.rikkahub.data.usage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * P2-11c — one row per **model round trip** (not per turn, not per session).
 *
 * Why this granularity: a single turn can issue several API calls (tool loop iterations,
 * compaction, title generation), and the per-message footer in the UI only ever showed the
 * last one. Rows are therefore the raw material for the turn view, the orchestration tree
 * (`parent_run_id`) and the budget gate.
 *
 * Metadata only — never message content. Retention is [UsageLedgerDefaults.RETENTION_DAYS]
 * days, swept by [UsageLedger.prune]. Cost is frozen at write time: the price table is
 * versioned separately, so editing prices later cannot rewrite history.
 */
@Entity(
    tableName = "usage_records",
    indices = [
        Index(name = "idx_usage_created", value = ["created_at_ms"]),
        Index(name = "idx_usage_conversation", value = ["conversation_id"]),
        Index(name = "idx_usage_run", value = ["run_id"]),
        Index(name = "idx_usage_parent_run", value = ["parent_run_id"]),
        Index(name = "idx_usage_purpose", value = ["purpose"]),
    ],
)
data class UsageRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    /** [UsagePurpose] name. */
    @ColumnInfo(name = "purpose")
    val purpose: String,

    @ColumnInfo(name = "provider_id")
    val providerId: String? = null,

    @ColumnInfo(name = "model_id")
    val modelId: String? = null,

    @ColumnInfo(name = "assistant_id")
    val assistantId: String? = null,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String? = null,

    /** Set once a run ledger (agent_runs) exists for this call; null for interactive turns. */
    @ColumnInfo(name = "run_id")
    val runId: String? = null,

    /** Orchestration root, so a parent can sum everything one dispatch fan-out cost. */
    @ColumnInfo(name = "parent_run_id")
    val parentRunId: String? = null,

    @ColumnInfo(name = "input_tokens")
    val inputTokens: Int,

    @ColumnInfo(name = "output_tokens")
    val outputTokens: Int,

    @ColumnInfo(name = "total_tokens")
    val totalTokens: Int,

    /** Provider-reported cache hits. Only meaningful when [cachedTokensReported] is true. */
    @ColumnInfo(name = "cached_tokens")
    val cachedTokens: Int,

    /**
     * False means the provider does not report cache fields at all, in which case
     * [cachedTokens] being 0 must NOT be read as a cache miss. This flag is the whole point
     * of P2-11a; statistics that ignore it are wrong.
     */
    @ColumnInfo(name = "cached_tokens_reported")
    val cachedTokensReported: Boolean,

    @ColumnInfo(name = "cache_miss_tokens")
    val cacheMissTokens: Int? = null,

    /** Anthropic cache writes, billed at their own rate. */
    @ColumnInfo(name = "cache_write_tokens")
    val cacheWriteTokens: Int? = null,

    @ColumnInfo(name = "reasoning_tokens")
    val reasoningTokens: Int? = null,

    /** Whatever the provider itself reported (OpenRouter `usage.cost`, USD). */
    @ColumnInfo(name = "provider_cost_usd")
    val providerCostUsd: Double? = null,

    /** Locally computed cost in millionths of the configured currency; frozen at write time. */
    @ColumnInfo(name = "cost_micros")
    val costMicros: Long? = null,

    /** Which revision of the price table produced [costMicros]. */
    @ColumnInfo(name = "price_version_id")
    val priceVersionId: String? = null,

    @ColumnInfo(name = "streaming")
    val streaming: Boolean = false,

    @ColumnInfo(name = "latency_ms")
    val latencyMs: Long? = null,
)
