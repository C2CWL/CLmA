package me.rerere.rikkahub.data.usage

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Which files an export writes. */
enum class UsageExportFormat(val wire: String) {
    CSV("csv"),
    JSON("json"),
    BOTH("both");

    val wantsCsv: Boolean get() = this == CSV || this == BOTH
    val wantsJson: Boolean get() = this == JSON || this == BOTH

    companion object {
        fun fromWire(raw: String?): UsageExportFormat? =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
    }
}

/**
 * The wire shape of one exported row.
 *
 * Columns are named explicitly instead of reusing the entity, so that renaming a Kotlin
 * property can never silently rename a column in a file a script already parses.
 */
@Serializable
data class UsageExportRow(
    val id: String,
    val createdAtMs: Long,
    val purpose: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val assistantId: String? = null,
    val conversationId: String? = null,
    val runId: String? = null,
    val parentRunId: String? = null,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val cachedTokens: Int,
    val cachedTokensReported: Boolean,
    val cacheMissTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val providerCostUsd: Double? = null,
    val costMicros: Long? = null,
    val priceVersionId: String? = null,
    val streaming: Boolean = false,
    val latencyMs: Long? = null,
)

/**
 * P2-11d-2 - the export half of the accounting ledger: metadata only, never message content.
 *
 * Pure and deterministic (the timestamp is passed in), so the file a test checks is the file
 * a user gets.
 */
object UsageExport {

    val CSV_HEADER: List<String> = listOf(
        "id", "created_at_ms", "purpose", "provider_id", "model_id", "assistant_id",
        "conversation_id", "run_id", "parent_run_id", "input_tokens", "output_tokens",
        "total_tokens", "cached_tokens", "cached_tokens_reported", "cache_miss_tokens",
        "cache_write_tokens", "reasoning_tokens", "provider_cost_usd", "cost_micros",
        "price_version_id", "streaming", "latency_ms",
    )

    fun rowsOf(records: List<UsageRecordEntity>): List<UsageExportRow> = records.map { r ->
        UsageExportRow(
            id = r.id,
            createdAtMs = r.createdAtMs,
            purpose = r.purpose,
            providerId = r.providerId,
            modelId = r.modelId,
            assistantId = r.assistantId,
            conversationId = r.conversationId,
            runId = r.runId,
            parentRunId = r.parentRunId,
            inputTokens = r.inputTokens,
            outputTokens = r.outputTokens,
            totalTokens = r.totalTokens,
            cachedTokens = r.cachedTokens,
            cachedTokensReported = r.cachedTokensReported,
            cacheMissTokens = r.cacheMissTokens,
            cacheWriteTokens = r.cacheWriteTokens,
            reasoningTokens = r.reasoningTokens,
            providerCostUsd = r.providerCostUsd,
            costMicros = r.costMicros,
            priceVersionId = r.priceVersionId,
            streaming = r.streaming,
            latencyMs = r.latencyMs,
        )
    }

    fun toCsv(records: List<UsageRecordEntity>): String = buildString {
        append(CSV_HEADER.joinToString(","))
        append("\n")
        records.forEach { row ->
            append(csvFields(row).joinToString(",") { csvCell(it) })
            append("\n")
        }
    }

    fun toJson(records: List<UsageRecordEntity>): String =
        json.encodeToString(rowsOf(records))

    /**
     * RFC 4180 quoting: a field is quoted when it holds a comma, a quote or a line break,
     * and inner quotes are doubled. Without this a model id containing a comma would shift
     * every later column in every spreadsheet that opens the file.
     */
    fun csvCell(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /** Deterministic, locale-free name: usage-ledger-20261002T133000Z.csv */
    fun defaultFileName(format: UsageExportFormat, atEpochMs: Long): String {
        val stamp = Instant.ofEpochMilli(atEpochMs)
            .truncatedTo(ChronoUnit.SECONDS)
            .toString()
            .replace("-", "")
            .replace(":", "")
        return "usage-ledger-$stamp.${format.wire}"
    }

    private val json = Json {
        prettyPrint = true
        explicitNulls = true
    }

    private fun csvFields(row: UsageRecordEntity): List<String> = listOf(
        row.id,
        row.createdAtMs.toString(),
        row.purpose,
        row.providerId.orEmpty(),
        row.modelId.orEmpty(),
        row.assistantId.orEmpty(),
        row.conversationId.orEmpty(),
        row.runId.orEmpty(),
        row.parentRunId.orEmpty(),
        row.inputTokens.toString(),
        row.outputTokens.toString(),
        row.totalTokens.toString(),
        row.cachedTokens.toString(),
        row.cachedTokensReported.toString(),
        row.cacheMissTokens?.toString().orEmpty(),
        row.cacheWriteTokens?.toString().orEmpty(),
        row.reasoningTokens?.toString().orEmpty(),
        row.providerCostUsd?.toString().orEmpty(),
        row.costMicros?.toString().orEmpty(),
        row.priceVersionId.orEmpty(),
        row.streaming.toString(),
        row.latencyMs?.toString().orEmpty(),
    )
}
