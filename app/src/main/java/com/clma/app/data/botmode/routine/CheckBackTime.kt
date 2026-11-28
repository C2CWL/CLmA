// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/host/check-back-time.ts, server/drivers/agents-check-back-tool.ts, server/routes/check-backs.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.routine

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

fun parseCheckBackTime(text: String, now: ZonedDateTime): Instant? {
    val raw = text.trim()
    if (raw.length !in 1..200) return null
    val lower = raw.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    val relative = Regex("(?:in|after) (\\d{1,6})\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|weeks?)")
        .matchEntire(lower.removeSuffix(" from now"))
    val absolute = runCatching { ZonedDateTime.parse(raw).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(raw).atZone(now.zone).toInstant() }.getOrNull()
    if (absolute != null) return absolute.takeIf { it > now.toInstant() }
    if (relative != null) {
        val count = relative.groupValues[1].toLong()
        val seconds = when (relative.groupValues[2].take(1)) {
            "s" -> 1L
            "m" -> 60L
            "h" -> 3600L
            "d" -> 86_400L
            else -> 604_800L
        }
        return now.toInstant().plusSeconds(count * seconds).takeIf { count > 0 }
    }
    val nextWeekday = lower.startsWith("next ")
    val dayAndTime = lower.removePrefix("next ").removePrefix("this ").replace(" at ", " ")
    val namedDay = dayAndTime.substringBefore(' ')
    val clock = dayAndTime.substringAfter(' ', "")
    val time = parseWallClock(clock) ?: return null
    if (namedDay == "tomorrow") return now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone).toInstant()
    if (namedDay == "today") return now.toLocalDate().atTime(time).atZone(now.zone).toInstant()
        .takeIf { it > now.toInstant() }
    val weekday = DayOfWeek.entries.firstOrNull {
        val name = it.name.lowercase(Locale.ROOT)
        namedDay == name || namedDay == name.take(3)
    } ?: return null
    val day = now.toLocalDate().with(if (nextWeekday) TemporalAdjusters.next(weekday) else TemporalAdjusters.nextOrSame(weekday))
    val due = day.atTime(time).atZone(now.zone)
    return (if (due > now) due else day.plusWeeks(1).atTime(time).atZone(now.zone)).toInstant()
}

private fun parseWallClock(clock: String): LocalTime? {
    when (clock) {
        "noon", "midday" -> return LocalTime.NOON
        "midnight" -> return LocalTime.MIDNIGHT
        "morning", "" -> return LocalTime.of(9, 0)
        "afternoon" -> return LocalTime.of(14, 0)
        "evening" -> return LocalTime.of(18, 0)
    }
    val match = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?").matchEntire(clock) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].ifBlank { "0" }.toInt()
    val period = match.groupValues[3].replace(".", "")
    if (period.isNotEmpty() && hour !in 1..12) return null
    val hour24 = if (period.isEmpty()) hour else hour % 12 + if (period == "pm") 12 else 0
    return runCatching { LocalTime.of(hour24, minute) }.getOrNull()
}
