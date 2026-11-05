package com.clma.app.ui.components.ai

/**
 * One labeled piece of a provider balance / quota string.
 *
 * Balance APIs return a single display string (often pipe-joined). The UI splits that into
 * segments so cards and chips can wrap instead of ellipsizing.
 */
data class ProviderBalanceSegment(
    /** Raw label from the string, or null when the piece had no `:` / `：`. */
    val rawLabel: String?,
    val value: String,
    /** Normalized kind used for friendly titles and compact chips. */
    val kind: ProviderBalanceSegmentKind = ProviderBalanceSegmentKind.Generic,
)

enum class ProviderBalanceSegmentKind {
    PoolType,
    SubscriptionRemaining,
    RefreshedAt,
    TodayTotal,
    Generic,
}

/**
 * Split a balance display string into labeled segments.
 *
 * Format: pieces joined by ` | `; each piece is `Label:Value` / `Label：Value`, or a bare value.
 * Known Chinese / English shapes (订阅 remaining + time in parentheses, 历史合计今日, unlabeled
 * pool names) are normalized into [ProviderBalanceSegmentKind]s. Parsing never drops text: on
 * failure the whole input becomes a single generic segment.
 */
fun parseProviderBalanceSegments(text: String): List<ProviderBalanceSegment> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()

    return try {
        val pieces = trimmed.split(" | ").map { it.trim() }.filter { it.isNotEmpty() }
        if (pieces.isEmpty()) {
            listOf(ProviderBalanceSegment(rawLabel = null, value = trimmed))
        } else {
            pieces.flatMap { parseBalancePiece(it) }.ifEmpty {
                listOf(ProviderBalanceSegment(rawLabel = null, value = trimmed))
            }
        }
    } catch (_: Exception) {
        listOf(ProviderBalanceSegment(rawLabel = null, value = trimmed))
    }
}

private val leadingDecorRegex = Regex("""^[\p{So}\p{Sk}\p{Cn}🔒🔐💵💰⚡⭐️⭐\s]+""")
private val subscriptionValueRegex = Regex(
    """^余?\s*(\d+(?:\.\d+)?\s*%)\s*(?:\(([^)]+)\))?\s*$""",
)
private val parentheticalTimeRegex = Regex("""^(.*?)[（(]([^）)]+)[）)]\s*$""")

private fun parseBalancePiece(piece: String): List<ProviderBalanceSegment> {
    val colonIndex = piece.indexOfFirst { it == ':' || it == '：' }
    if (colonIndex <= 0) {
        val value = stripLeadingDecor(piece)
        if (value.isEmpty()) return emptyList()
        return listOf(
            ProviderBalanceSegment(
                rawLabel = null,
                value = value,
                kind = inferKind(rawLabel = null, value = value),
            ),
        )
    }

    val rawLabel = piece.substring(0, colonIndex).trim()
    var value = piece.substring(colonIndex + 1).trim()
    if (rawLabel.isEmpty() && value.isEmpty()) return emptyList()
    if (value.isEmpty()) {
        return listOf(
            ProviderBalanceSegment(
                rawLabel = rawLabel.ifEmpty { null },
                value = rawLabel,
                kind = ProviderBalanceSegmentKind.Generic,
            ),
        )
    }

    val kind = inferKind(rawLabel = rawLabel, value = value)
    if (kind == ProviderBalanceSegmentKind.SubscriptionRemaining) {
        val match = subscriptionValueRegex.matchEntire(value)
        if (match != null) {
            val percent = match.groupValues[1].replace(" ", "")
            val time = match.groupValues.getOrNull(2)?.trim().orEmpty()
            val segments = mutableListOf(
                ProviderBalanceSegment(
                    rawLabel = rawLabel,
                    value = percent,
                    kind = ProviderBalanceSegmentKind.SubscriptionRemaining,
                ),
            )
            if (time.isNotEmpty()) {
                segments += ProviderBalanceSegment(
                    rawLabel = null,
                    value = time,
                    kind = ProviderBalanceSegmentKind.RefreshedAt,
                )
            }
            return segments
        }
        val paren = parentheticalTimeRegex.matchEntire(value)
        if (paren != null) {
            val main = paren.groupValues[1].trim().ifEmpty { value }
            val time = paren.groupValues[2].trim()
            val segments = mutableListOf(
                ProviderBalanceSegment(
                    rawLabel = rawLabel,
                    value = main.removePrefix("余").trim().ifEmpty { main },
                    kind = ProviderBalanceSegmentKind.SubscriptionRemaining,
                ),
            )
            if (time.isNotEmpty()) {
                segments += ProviderBalanceSegment(
                    rawLabel = null,
                    value = time,
                    kind = ProviderBalanceSegmentKind.RefreshedAt,
                )
            }
            return segments
        }
    }

    if (kind == ProviderBalanceSegmentKind.TodayTotal) {
        value = value.trim()
    }

    return listOf(
        ProviderBalanceSegment(
            rawLabel = rawLabel.ifEmpty { null },
            value = value,
            kind = kind,
        ),
    )
}

private fun stripLeadingDecor(text: String): String =
    text.replace(leadingDecorRegex, "").trim().ifEmpty { text.trim() }

private fun inferKind(rawLabel: String?, value: String): ProviderBalanceSegmentKind {
    val label = rawLabel.orEmpty()
    val blob = "$label $value"
    return when {
        labelContainsAny(label, "订阅", "subscription", "quota remaining", "remaining") ||
            value.matches(subscriptionValueRegex) ->
            ProviderBalanceSegmentKind.SubscriptionRemaining

        labelContainsAny(label, "刷新", "refresh", "updated", "更新") ||
            looksLikeShortTimestamp(value) && label.isEmpty() ->
            ProviderBalanceSegmentKind.RefreshedAt

        labelContainsAny(
            label,
            "历史合计",
            "今日合计",
            "今日",
            "today",
            "daily",
            "usage today",
            "total today",
        ) || labelContainsAny(blob, "历史合计今日") ->
            ProviderBalanceSegmentKind.TodayTotal

        rawLabel == null && (
            value.contains("池") ||
                value.contains("pool", ignoreCase = true) ||
                value.contains("付费") ||
                value.contains("免费")
            ) ->
            ProviderBalanceSegmentKind.PoolType

        else -> ProviderBalanceSegmentKind.Generic
    }
}

private fun labelContainsAny(text: String, vararg needles: String): Boolean {
    if (text.isEmpty()) return false
    val lower = text.lowercase()
    return needles.any { needle ->
        if (needle.any { it.code > 127 }) text.contains(needle)
        else lower.contains(needle.lowercase())
    }
}

private fun looksLikeShortTimestamp(value: String): Boolean =
    Regex("""^\d{1,2}[-/]\d{1,2}(?:\s+\d{1,2}:\d{2})?$""").matches(value.trim())

/**
 * Compact 1–2 chips for the model picker: prefer pool+subscription and today's total.
 * Falls back to every segment so nothing is dropped when the shape is unfamiliar.
 */
fun compactProviderBalanceChips(
    segments: List<ProviderBalanceSegment>,
): List<ProviderBalanceSegment> {
    if (segments.isEmpty()) return segments
    val pool = segments.firstOrNull { it.kind == ProviderBalanceSegmentKind.PoolType }
    val subscription =
        segments.firstOrNull { it.kind == ProviderBalanceSegmentKind.SubscriptionRemaining }
    val today = segments.firstOrNull { it.kind == ProviderBalanceSegmentKind.TodayTotal }
    val compacted = mutableListOf<ProviderBalanceSegment>()

    when {
        pool != null && subscription != null -> {
            val poolShort = pool.value
                .removePrefix("仅")
                .trim()
                .ifEmpty { pool.value }
            compacted += ProviderBalanceSegment(
                rawLabel = pool.rawLabel,
                value = "$poolShort ${subscription.value}".trim(),
                kind = ProviderBalanceSegmentKind.SubscriptionRemaining,
            )
        }
        subscription != null -> compacted += subscription
        pool != null -> compacted += pool
    }
    if (today != null) {
        compacted += today.copy(
            // Chip label rendered as 「今日」; keep the numeric value.
            rawLabel = today.rawLabel ?: "今日",
        )
    }

    if (compacted.isEmpty()) return segments

    // Preserve any leftover kinds (generic / refreshed) so information is never dropped.
    val covered = buildSet {
        if (pool != null) add(pool)
        if (subscription != null) add(subscription)
        if (today != null) add(today)
    }
    segments.filterNot { it in covered || it.kind == ProviderBalanceSegmentKind.RefreshedAt }
        .forEach { leftover ->
            if (compacted.none { it.value == leftover.value && it.kind == leftover.kind }) {
                compacted += leftover
            }
        }
    return compacted
}
