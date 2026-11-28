// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/host/brief.ts, server/host/brief-cards.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0 (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.uuid.Uuid

internal data class BotBriefWindow(val day: LocalDate, val due: Instant, val since: Instant)

internal fun botBriefWindow(now: Instant, time: LocalTime, zone: ZoneId): BotBriefWindow {
    val today = now.atZone(zone).toLocalDate()
    val todayDue = today.atTime(time).atZone(zone).toInstant()
    val day = if (now < todayDue) today.minusDays(1) else today
    return BotBriefWindow(
        day = day,
        due = day.atTime(time).atZone(zone).toInstant(),
        since = day.minusDays(1).atTime(time).atZone(zone).toInstant(),
    )
}

internal fun briefExcerpt(text: String, limit: Int = 180): String {
    val compact = text.replace(Regex("\\s+"), " ").trim()
    val clipped = if (compact.length > limit) compact.take(limit - 1).trimEnd() + "…" else compact
    return buildString {
        for (character in clipped) {
            if (character in "\\`*_{}[]<>#!|&~") append('\\')
            append(character)
        }
    }
}

// A stable, app-specific UUID prefix distinguishes generated briefs from ordinary conversations.
private const val BRIEF_ID_PREFIX = "574139fa-b8fa-8236-8000-"

internal fun dailyBriefTopicId(day: LocalDate): Uuid =
    Uuid.parse(BRIEF_ID_PREFIX + (day.toEpochDay() and 0xffffffffffffL).toString(16).padStart(12, '0'))

internal fun isDailyBriefTopic(id: Uuid): Boolean = id.toString().startsWith(BRIEF_ID_PREFIX)
