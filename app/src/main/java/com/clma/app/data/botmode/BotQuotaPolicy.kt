// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/drivers/retry.ts, server/limit-resets.ts, server/routine-recovery.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode

import com.clma.ai.util.HttpException
import com.clma.ai.util.HttpFailureMetadata
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

private const val MAX_WAIT_MS = 12 * 60 * 60 * 1_000L
private const val RESET_GRACE_MS = 5_000L

internal data class BotQuotaLimit(val resetAt: Long?, val outputStarted: Boolean = false) {
    fun automaticRetryAt(now: Long): Long? = resetAt
        ?.takeIf { !outputStarted && it in now..(now + MAX_WAIT_MS) }
        ?.plus(RESET_GRACE_MS)
}

private val quotaWords = Regex(
    """\b(?:resource[ _]exhausted|insufficient[ _]quota|usage[ _]limit|usage_limit_(?:reached|exceeded)|""" +
        """rate[ _]limit|too many requests|quota(?:[ _]has[ _]been)?[ _]exceeded|exceeded.{0,35}quota|""" +
        """out of (?:usage credits|extra usage)|hit your (?:[\w-]+ ){0,3}(?:limit|budget))\b|超出配额|配额已用完|超过配额|达到用量上限""",
    RegexOption.IGNORE_CASE,
)
private val rateLimitStatuses = setOf(403, 503, 529)
private val status429 = Regex("""\b(?:HTTP[ :]+|(?:response|error)(?::| code:)\s*)429\b|^429$""", RegexOption.IGNORE_CASE)
private val resetField = Regex(
    """"(?:resets_at|reset_at|retryDelay|resets_in_seconds)"\s*:\s*"?([\w.:+-]+)""",
    RegexOption.IGNORE_CASE,
)
private val duration = Regex("""(\d+(?:\.\d+)?)(ms|s|m|h|d)""")

internal fun Throwable.botQuotaLimit(now: Long): BotQuotaLimit? {
    val causes = generateSequence(this) { it.cause }.take(16).toList()
    if (causes.any { it is CancellationException }) return null
    val transport = causes.flatMap { it.suppressed.filterIsInstance<HttpFailureMetadata>() }
    val messages = causes.mapNotNull { it.message } + transport.mapNotNull { it.errorBody }
    val identified = transport.any { it.status == 429 || it.retryAfter != null } ||
        causes.filterIsInstance<HttpException>().any { it.statusCode == 429 } ||
        messages.any { quotaWords.containsMatchIn(it) || status429.containsMatchIn(it) } ||
        transport.any { it.status in rateLimitStatuses && it.rateLimitResets.isNotEmpty() }
    if (!identified) return null
    val resets = transport.flatMap { metadata ->
        listOfNotNull(metadata.retryAfter?.let { parseReset(it, now, relativeSeconds = true) }) +
            metadata.rateLimitResets.mapNotNull { parseReset(it, now) }
    } + messages.flatMap { message ->
        resetField.findAll(message).mapNotNull { parseReset(it.groupValues[1], now) }.toList()
    }
    return BotQuotaLimit(resets.filter { it >= now }.maxOrNull(), transport.any { it.outputStarted })
}

private fun parseReset(raw: String, now: Long, relativeSeconds: Boolean = false): Long? {
    val value = raw.trim()
    value.toLongOrNull()?.takeIf { it >= 0 }?.let { number ->
        return when {
            relativeSeconds -> now.plusSeconds(number)
            number >= 1_000_000_000_000L -> number
            number >= 1_000_000_000L -> number * 1_000L
            else -> now.plusSeconds(number)
        }
    }
    val pieces = duration.findAll(value).toList()
    if (pieces.isNotEmpty() && pieces.joinToString("") { it.value } == value) {
        val millis = pieces.sumOf {
            it.groupValues[1].toDouble() * when (it.groupValues[2]) {
                "ms" -> 1L
                "s" -> 1_000L
                "m" -> 60_000L
                "h" -> 3_600_000L
                else -> 86_400_000L
            }
        }
        if (millis.isFinite() && millis < Long.MAX_VALUE - now) return now + millis.toLong()
        return null
    }
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull()
}

private fun Long.plusSeconds(seconds: Long): Long? =
    seconds.takeIf { it <= (Long.MAX_VALUE - this) / 1_000L }?.let { this + it * 1_000L }
