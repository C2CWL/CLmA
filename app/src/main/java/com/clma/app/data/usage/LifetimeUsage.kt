package com.clma.app.data.usage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** One durable row, independent of the retention window for individual calls. */
@Entity(tableName = "lifetime_usage")
data class LifetimeUsage(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "input_tokens") val inputTokens: Long = 0,
    @ColumnInfo(name = "output_tokens") val outputTokens: Long = 0,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Long = 0,
    @ColumnInfo(name = "legacy_baselined") val legacyBaselined: Boolean = false,
) {
    companion object {
        fun floor(ledger: LifetimeUsage, legacy: LifetimeUsage): LifetimeUsage = LifetimeUsage(
            inputTokens = maxOf(ledger.inputTokens, legacy.inputTokens.coerceAtLeast(0)),
            outputTokens = maxOf(ledger.outputTokens, legacy.outputTokens.coerceAtLeast(0)),
            cachedTokens = maxOf(ledger.cachedTokens, legacy.cachedTokens.coerceAtLeast(0)),
            legacyBaselined = true,
        )
    }
}
