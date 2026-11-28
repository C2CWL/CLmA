// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/routine-schedules.ts, server/routine-intervals.ts, shared/routine-schedule.ts, packages/phone-protocol/src/routine-schedule.ts, server/routines.ts, docs/routine-schedules.md,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.routine

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

internal const val INTERVAL_CRON_PLACEHOLDER = "0 0 30 2 *"

@Serializable
data class RoutineInterval(
    val minutes: Int,
    val anchorMs: Long,
    val weekdays: Set<Int> = emptySet(),
    val startMinute: Int = 0,
    val endMinute: Int = 1440,
)

fun RoutineInterval.nextAt(nowMs: Long, zone: ZoneId, endMs: Long?): Long? {
    require(minutes in 1..1440 && startMinute in 0 until endMinute && endMinute <= 1440)
    val step = minutes * 60_000L
    val distance = (nowMs - anchorMs).coerceAtLeast(0)
    var candidate = anchorMs + ((distance + step - 1) / step) * step
    // A DST transition can remove a whole window. Also cover a full weekly
    // alignment cycle, even when the interval does not divide a day.
    val weekMs = 7L * 24 * 60 * 60_000
    val alignmentCycleMs = weekMs / gcd(step, weekMs) * step
    val searchMs = maxOf(16L * 24 * 60 * 60_000, 2 * alignmentCycleMs)
    val ceiling = minOf(endMs ?: Long.MAX_VALUE, candidate + searchMs)
    while (candidate <= ceiling) {
        val local = Instant.ofEpochMilli(candidate).atZone(zone)
        val minute = local.hour * 60 + local.minute
        if ((weekdays.isEmpty() || local.dayOfWeek.value in weekdays) && minute in startMinute until endMinute) {
            return candidate
        }
        candidate += step
    }
    return null
}

private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

@Serializable
enum class RoutineOverlap { SKIP, QUEUE }

enum class RoutineAdmission { RUN, SKIP, QUEUE }

fun routineAdmission(blocked: Boolean, overlap: RoutineOverlap, queued: Boolean): RoutineAdmission = when {
    !blocked -> RoutineAdmission.RUN
    overlap == RoutineOverlap.QUEUE && !queued -> RoutineAdmission.QUEUE
    else -> RoutineAdmission.SKIP
}

fun quotaRetryAt(now: Long, retryAfter: String?, reset: String?): Long? {
    val fromRetry = retryAfter?.trim()?.let { value ->
        value.toLongOrNull()?.takeIf { it in 0..43_200 }?.let { now + it * 1000 }
            ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }
    val fromReset = reset?.trim()?.let { value ->
        value.toLongOrNull()?.let {
            when (it) {
                in 0..43_200 -> now + it * 1000
                in 1_000_000_000..9_999_999_999 -> it * 1000
                else -> it
            }
        } ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: providerResetDelayMs(value)?.let { now + it }
    }
    return sequenceOf(fromRetry, fromReset).filterNotNull()
        .firstOrNull { it in now..(now + 43_200_000L) }?.plus(1000)
}


/** OpenAI-compatible headers may report a duration such as 1h2m3.5s. */
internal fun providerResetDelayMs(text: String): Long? {
    val part = Regex("(\\d{1,6}(?:\\.\\d{1,3})?)(ms|h|m|s)")
    var offset = 0
    var total = 0.0
    for (match in part.findAll(text)) {
        if (match.range.first != offset) return null
        val unitMs = when (match.groupValues[2]) {
            "h" -> 3_600_000
            "m" -> 60_000
            "s" -> 1000
            else -> 1
        }
        total += match.groupValues[1].toDouble() * unitMs
        offset = match.range.last + 1
    }
    return total.toLong().takeIf { offset == text.length && offset > 0 }
}
