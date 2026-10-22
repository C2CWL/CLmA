package me.rerere.rikkahub.data.usage

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ModelPricing
import me.rerere.ai.provider.OffPeakRates
import me.rerere.ai.provider.PeakWindow

/** One peak interval as an agent or a text field writes it: minutes from local midnight. */
@Serializable
data class PriceWindowSpec(
    val startMinute: Int,
    val endMinute: Int,
    val zoneOffsetMinutes: Int = 0,
    val daysOfWeek: List<Int> = emptyList(),
)

/** One model price as written in a payload. Names, not UUIDs - a human has to type these. */
@Serializable
data class PriceEntrySpec(
    val providerName: String,
    val modelId: String,
    val inputPerMillion: Double? = null,
    val outputPerMillion: Double? = null,
    val cacheHitPerMillion: Double? = null,
    val cacheWritePerMillion: Double? = null,
    val offPeakInputPerMillion: Double? = null,
    val offPeakOutputPerMillion: Double? = null,
    val offPeakCacheHitPerMillion: Double? = null,
    val offPeakCacheWritePerMillion: Double? = null,
    val peakWindows: List<PriceWindowSpec> = emptyList(),
)

/** The whole price table, the unit of replacement. */
@Serializable
data class PriceTableSpec(
    val version: String? = null,
    val note: String? = null,
    val entries: List<PriceEntrySpec> = emptyList(),
)

data class RejectedEntrySpec(
    val index: Int,
    val providerName: String,
    val modelId: String,
    val reason: String,
)

data class PriceTableValidation(
    val accepted: List<PriceEntrySpec>,
    val rejected: List<RejectedEntrySpec>,
) {
    val isClean: Boolean get() = rejected.isEmpty()
}

/**
 * P2-11d-3 - the pure half of the price-table write path.
 *
 * Validating here (rather than inside the tool) means every rejection reason is a unit test,
 * and the agent gets the same sentence a test asserts on. Nothing in this file touches
 * Settings, disk or Android.
 */
object UsagePriceTable {

    /** A table bigger than this is a runaway payload, not a price list. */
    const val MAX_ENTRIES = 200

    fun validate(spec: PriceTableSpec): PriceTableValidation {
        val accepted = mutableListOf<PriceEntrySpec>()
        val rejected = mutableListOf<RejectedEntrySpec>()
        val seen = mutableSetOf<String>()

        spec.entries.forEachIndexed { index, entry ->
            val provider = entry.providerName.trim()
            val model = entry.modelId.trim()
            val reason = when {
                index >= MAX_ENTRIES -> "table exceeds $MAX_ENTRIES entries"
                provider.isEmpty() -> "blank provider name"
                model.isEmpty() -> "blank model id"
                !seen.add("$provider\u0000$model") -> "duplicate entry for $provider / $model"
                else -> priceProblem(entry) ?: windowProblem(entry.peakWindows)
            }
            if (reason == null) {
                accepted += entry.copy(
                    providerName = provider,
                    modelId = model,
                    peakWindows = normalize(entry.peakWindows),
                )
            } else {
                rejected += RejectedEntrySpec(index, provider, model, reason)
            }
        }
        return PriceTableValidation(accepted, rejected)
    }

    /**
     * Null when the entry carries no rate at all - the resolver treats that as unpaid, so a
     * price-less row must never be stored as a free one.
     */
    fun toModelPricing(entry: PriceEntrySpec): ModelPricing? {
        val offPeak = if (
            entry.offPeakInputPerMillion != null ||
            entry.offPeakOutputPerMillion != null ||
            entry.offPeakCacheHitPerMillion != null ||
            entry.offPeakCacheWritePerMillion != null
        ) {
            OffPeakRates(
                inputPerMillion = entry.offPeakInputPerMillion,
                outputPerMillion = entry.offPeakOutputPerMillion,
                cacheHitPerMillion = entry.offPeakCacheHitPerMillion,
                cacheWritePerMillion = entry.offPeakCacheWritePerMillion,
            )
        } else {
            null
        }
        if (
            entry.inputPerMillion == null && entry.outputPerMillion == null &&
            entry.cacheHitPerMillion == null && entry.cacheWritePerMillion == null && offPeak == null
        ) {
            return null
        }
        return ModelPricing(
            inputPerMillion = entry.inputPerMillion,
            outputPerMillion = entry.outputPerMillion,
            cacheHitPerMillion = entry.cacheHitPerMillion,
            cacheWritePerMillion = entry.cacheWritePerMillion,
            offPeak = offPeak,
            peakWindows = entry.peakWindows.map {
                PeakWindow(
                    startMinute = it.startMinute,
                    endMinute = it.endMinute,
                    zoneOffsetMinutes = it.zoneOffsetMinutes,
                    daysOfWeek = it.daysOfWeek.toSet(),
                )
            },
        )
    }

    /** Read side: the stored pricing rendered back into the payload shape. */
    fun fromModelPricing(providerName: String, modelId: String, pricing: ModelPricing): PriceEntrySpec =
        PriceEntrySpec(
            providerName = providerName,
            modelId = modelId,
            inputPerMillion = pricing.inputPerMillion,
            outputPerMillion = pricing.outputPerMillion,
            cacheHitPerMillion = pricing.cacheHitPerMillion,
            cacheWritePerMillion = pricing.cacheWritePerMillion,
            offPeakInputPerMillion = pricing.offPeak?.inputPerMillion,
            offPeakOutputPerMillion = pricing.offPeak?.outputPerMillion,
            offPeakCacheHitPerMillion = pricing.offPeak?.cacheHitPerMillion,
            offPeakCacheWritePerMillion = pricing.offPeak?.cacheWritePerMillion,
            peakWindows = pricing.peakWindows.map {
                PriceWindowSpec(
                    startMinute = it.startMinute,
                    endMinute = it.endMinute,
                    zoneOffsetMinutes = it.zoneOffsetMinutes,
                    daysOfWeek = it.daysOfWeek.sorted(),
                )
            },
        )

    /** Stable text of a table: order of the payload must not change the version id. */
    fun canonical(entries: List<PriceEntrySpec>): String =
        entries.sortedWith(compareBy({ it.providerName }, { it.modelId })).joinToString("\n") { e ->
            listOf(
                e.providerName,
                e.modelId,
                rate(e.inputPerMillion),
                rate(e.outputPerMillion),
                rate(e.cacheHitPerMillion),
                rate(e.cacheWritePerMillion),
                rate(e.offPeakInputPerMillion),
                rate(e.offPeakOutputPerMillion),
                rate(e.offPeakCacheHitPerMillion),
                rate(e.offPeakCacheWritePerMillion),
                e.peakWindows.sortedWith(compareBy({ it.zoneOffsetMinutes }, { it.startMinute }, { it.endMinute }))
                    .joinToString(";") { w ->
                        "w${w.startMinute}-${w.endMinute}@${w.zoneOffsetMinutes}[${w.daysOfWeek.distinct().sorted().joinToString(",")}]"
                    },
            ).joinToString("|")
        }

    /**
     * Content-derived version of the whole table, same 12 hex characters as the per-call
     * [UsagePriceResolver] ids, so an audit row and a ledger row can be compared by eye.
     */
    fun tableVersionId(entries: List<PriceEntrySpec>): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(canonical(entries).toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
    }

    private fun priceProblem(entry: PriceEntrySpec): String? {
        val named = listOf(
            "inputPerMillion" to entry.inputPerMillion,
            "outputPerMillion" to entry.outputPerMillion,
            "cacheHitPerMillion" to entry.cacheHitPerMillion,
            "cacheWritePerMillion" to entry.cacheWritePerMillion,
            "offPeakInputPerMillion" to entry.offPeakInputPerMillion,
            "offPeakOutputPerMillion" to entry.offPeakOutputPerMillion,
            "offPeakCacheHitPerMillion" to entry.offPeakCacheHitPerMillion,
            "offPeakCacheWritePerMillion" to entry.offPeakCacheWritePerMillion,
        )
        var given = false
        named.forEach { (name, value) ->
            if (value == null) return@forEach
            given = true
            if (!value.isFinite()) return "$name is not a finite number"
            if (value < 0) return "$name is negative"
        }
        return if (given) null else "no rate given"
    }

    private fun windowProblem(windows: List<PriceWindowSpec>): String? {
        windows.forEachIndexed { index, w ->
            if (w.startMinute !in 0..1439) return "window[$index] startMinute must be 0..1439"
            if (w.endMinute !in 0..1439) return "window[$index] endMinute must be 0..1439"
            if (w.startMinute == w.endMinute) return "window[$index] start and end are the same minute"
            if (w.zoneOffsetMinutes !in -720..840) return "window[$index] zoneOffsetMinutes must be -720..840"
            if (w.daysOfWeek.any { it !in 1..7 }) return "window[$index] daysOfWeek is ISO 1..7 (Mon..Sun)"
        }
        return null
    }

    private fun normalize(windows: List<PriceWindowSpec>): List<PriceWindowSpec> =
        windows.map { it.copy(daysOfWeek = it.daysOfWeek.distinct().sorted()) }
            .sortedWith(compareBy({ it.zoneOffsetMinutes }, { it.startMinute }, { it.endMinute }))

    private fun rate(value: Double?): String =
        if (value == null) "-" else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
