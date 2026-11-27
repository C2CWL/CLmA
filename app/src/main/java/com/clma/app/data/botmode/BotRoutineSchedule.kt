package com.clma.app.data.botmode

import com.clma.app.data.db.entity.ScheduledJobEntity
import com.clma.app.service.CronExpressionParser
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.uuid.Uuid

/**
 * Bot routines ("自动任务") are ordinary [ScheduledJobEntity] rows owned by a Bot-library
 * assistant (`assistantId`). This file holds the pure pieces the Bot UI needs on top of the
 * existing scheduler: reading a schedule into a small set of shapes the UI can localise, the
 * editor form, and turning that form back into a job row. No Android, no Room, so all of it
 * is unit-tested.
 */
sealed interface BotRoutineSchedule {
    data class Daily(val hour: Int, val minute: Int) : BotRoutineSchedule
    data class Weekdays(val hour: Int, val minute: Int) : BotRoutineSchedule
    data class Weekly(val days: List<DayOfWeek>, val hour: Int, val minute: Int) : BotRoutineSchedule
    data class EveryMinutes(val minutes: Int) : BotRoutineSchedule
    data class EveryHours(val hours: Int) : BotRoutineSchedule
    data class Once(val atMs: Long) : BotRoutineSchedule
    data class Custom(val expression: String) : BotRoutineSchedule
}

private val WORKDAYS: Set<DayOfWeek> = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

private val DOW_TOKENS: Map<String, DayOfWeek> = mapOf(
    "0" to DayOfWeek.SUNDAY, "7" to DayOfWeek.SUNDAY, "1" to DayOfWeek.MONDAY, "2" to DayOfWeek.TUESDAY,
    "3" to DayOfWeek.WEDNESDAY, "4" to DayOfWeek.THURSDAY, "5" to DayOfWeek.FRIDAY, "6" to DayOfWeek.SATURDAY,
    "SUN" to DayOfWeek.SUNDAY, "MON" to DayOfWeek.MONDAY, "TUE" to DayOfWeek.TUESDAY,
    "WED" to DayOfWeek.WEDNESDAY, "THU" to DayOfWeek.THURSDAY, "FRI" to DayOfWeek.FRIDAY,
    "SAT" to DayOfWeek.SATURDAY,
)

/** Cron day-of-week token for [day] (the parser accepts MON..SUN names). */
private fun DayOfWeek.cronToken(): String = name.take(3)

/** Days named by a cron day-of-week field: `*`, `MON-FRI`, `1-5`, or a comma list. Null when too complex. */
internal fun parseCronDays(field: String): Set<DayOfWeek>? {
    val f = field.trim().uppercase()
    if (f == "*" || f == "?") return DayOfWeek.values().toSet()
    if (f.contains('/') || f.contains('#') || f.contains('L')) return null
    val out = mutableSetOf<DayOfWeek>()
    for (part in f.split(',')) {
        val p = part.trim()
        if (p.contains('-')) {
            val (a, b) = p.split('-', limit = 2).map { DOW_TOKENS[it.trim()] ?: return null }
            // DayOfWeek values run MONDAY(1)..SUNDAY(7); cron 0/SUN maps to SUNDAY(7).
            var v = a.value
            val end = b.value
            if (v > end) return null
            while (v <= end) { out += DayOfWeek.of(v); v++ }
        } else {
            out += DOW_TOKENS[p] ?: return null
        }
    }
    return out.ifEmpty { null }
}

/** Reads a job's schedule into one of the shapes the Bot UI can show and edit. */
fun parseBotRoutineSchedule(job: ScheduledJobEntity): BotRoutineSchedule {
    if (job.scheduleType == "once") return BotRoutineSchedule.Once(job.atUnixMs ?: 0L)
    val expr = job.cronExpression?.trim().orEmpty()
    return parseBotRoutineCron(expr)
}

fun parseBotRoutineCron(expression: String): BotRoutineSchedule {
    val expr = expression.trim()
    val custom = BotRoutineSchedule.Custom(expr)
    when (expr.lowercase()) {
        "@daily", "@midnight" -> return BotRoutineSchedule.Daily(0, 0)
        "@hourly" -> return BotRoutineSchedule.EveryHours(1)
    }
    if (expr.startsWith("@every ", ignoreCase = true)) {
        val rest = expr.substring("@every ".length).trim().lowercase()
        val n = rest.dropLast(1).toIntOrNull() ?: return custom
        return when (rest.lastOrNull()) {
            'm' -> if (n in 1..59) BotRoutineSchedule.EveryMinutes(n) else custom
            'h' -> if (n in 1..23) BotRoutineSchedule.EveryHours(n) else custom
            else -> custom
        }
    }
    val parts = expr.split(Regex("\\s+"))
    if (parts.size != 5) return custom
    val (minute, hour, dom, month, dow) = parts
    if (dom != "*" || month != "*") return custom
    if (hour == "*" && dow == "*" && minute.startsWith("*/")) {
        val n = minute.removePrefix("*/").toIntOrNull() ?: return custom
        return if (n in 1..59) BotRoutineSchedule.EveryMinutes(n) else custom
    }
    if (minute == "0" && dow == "*" && hour.startsWith("*/")) {
        val n = hour.removePrefix("*/").toIntOrNull() ?: return custom
        return if (n in 1..23) BotRoutineSchedule.EveryHours(n) else custom
    }
    val m = minute.toIntOrNull()?.takeIf { it in 0..59 } ?: return custom
    val h = hour.toIntOrNull()?.takeIf { it in 0..23 } ?: return custom
    val days = parseCronDays(dow) ?: return custom
    return when {
        days.size == 7 -> BotRoutineSchedule.Daily(h, m)
        days == WORKDAYS -> BotRoutineSchedule.Weekdays(h, m)
        else -> BotRoutineSchedule.Weekly(days.sortedBy { it.value }, h, m)
    }
}

/** "9:00" style clock text used in routine labels. */
fun botRoutineClock(hour: Int, minute: Int): String = "%d:%02d".format(hour, minute)

/** This bot's routines, newest first. */
fun routinesOfBot(jobs: List<ScheduledJobEntity>, assistantId: Uuid): List<ScheduledJobEntity> {
    val id = assistantId.toString()
    return jobs.filter { it.assistantId == id }
}

/**
 * The routine to show on the bot's list row: the enabled one that fires soonest, plus how many
 * other enabled routines the bot has. Null when the bot has none enabled.
 */
data class BotRoutineRowSummary(val next: ScheduledJobEntity, val others: Int)

fun botRoutineRowSummary(jobs: List<ScheduledJobEntity>, assistantId: Uuid): BotRoutineRowSummary? {
    val enabled = routinesOfBot(jobs, assistantId).filter { it.enabled }
    if (enabled.isEmpty()) return null
    val next = enabled.minWith(compareBy<ScheduledJobEntity> { it.nextRunAtMs ?: Long.MAX_VALUE }.thenBy { it.createdAtMs })
    return BotRoutineRowSummary(next, enabled.size - 1)
}

// ---------------------------------------------------------------- editor form

enum class BotRoutineKind { DAILY, WEEKDAYS, WEEKLY, EVERY_MINUTES, EVERY_HOURS, ONCE, CUSTOM }

enum class BotRoutineFormError { NAME, PROMPT, DAYS, INTERVAL, CRON }

data class BotRoutineForm(
    val name: String = "",
    val prompt: String = "",
    val kind: BotRoutineKind = BotRoutineKind.WEEKDAYS,
    val hour: Int = 9,
    val minute: Int = 0,
    val days: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY),
    val interval: Int = 30,
    val cron: String = "",
) {
    companion object {
        const val NAME_MAX = 80
        const val PROMPT_MAX = 4000
    }
}

/** Form pre-filled from an existing job (edit), using [zone] for a one-time job's clock time. */
fun botRoutineFormFrom(job: ScheduledJobEntity, zone: ZoneId = ZoneId.systemDefault()): BotRoutineForm {
    val base = BotRoutineForm(name = job.name, prompt = job.prompt.orEmpty())
    return when (val s = parseBotRoutineSchedule(job)) {
        is BotRoutineSchedule.Daily -> base.copy(kind = BotRoutineKind.DAILY, hour = s.hour, minute = s.minute)
        is BotRoutineSchedule.Weekdays -> base.copy(kind = BotRoutineKind.WEEKDAYS, hour = s.hour, minute = s.minute)
        is BotRoutineSchedule.Weekly -> base.copy(kind = BotRoutineKind.WEEKLY, hour = s.hour, minute = s.minute, days = s.days.toSet())
        is BotRoutineSchedule.EveryMinutes -> base.copy(kind = BotRoutineKind.EVERY_MINUTES, interval = s.minutes)
        is BotRoutineSchedule.EveryHours -> base.copy(kind = BotRoutineKind.EVERY_HOURS, interval = s.hours)
        is BotRoutineSchedule.Once -> {
            val at = Instant.ofEpochMilli(s.atMs).atZone(zone)
            base.copy(kind = BotRoutineKind.ONCE, hour = at.hour, minute = at.minute)
        }
        is BotRoutineSchedule.Custom -> base.copy(kind = BotRoutineKind.CUSTOM, cron = s.expression)
    }
}

/** Cron expression for the form, or null for [BotRoutineKind.ONCE]. */
fun BotRoutineForm.cronExpression(): String? = when (kind) {
    BotRoutineKind.DAILY -> "$minute $hour * * *"
    BotRoutineKind.WEEKDAYS -> "$minute $hour * * MON-FRI"
    BotRoutineKind.WEEKLY -> "$minute $hour * * " + days.sortedBy { it.value }.joinToString(",") { it.cronToken() }
    BotRoutineKind.EVERY_MINUTES -> "@every ${interval}m"
    BotRoutineKind.EVERY_HOURS -> "@every ${interval}h"
    BotRoutineKind.ONCE -> null
    BotRoutineKind.CUSTOM -> cron.trim()
}

fun BotRoutineForm.validate(): BotRoutineFormError? = when {
    name.isBlank() || name.trim().length > BotRoutineForm.NAME_MAX -> BotRoutineFormError.NAME
    prompt.isBlank() || prompt.trim().length > BotRoutineForm.PROMPT_MAX -> BotRoutineFormError.PROMPT
    kind == BotRoutineKind.WEEKLY && days.isEmpty() -> BotRoutineFormError.DAYS
    kind == BotRoutineKind.EVERY_MINUTES && interval !in 1..59 -> BotRoutineFormError.INTERVAL
    kind == BotRoutineKind.EVERY_HOURS && interval !in 1..23 -> BotRoutineFormError.INTERVAL
    kind == BotRoutineKind.CUSTOM && (cron.isBlank() || CronExpressionParser.parse(cron.trim()).isFailure) ->
        BotRoutineFormError.CRON
    else -> null
}

/** Next wall-clock occurrence of [hour]:[minute] strictly after [nowMs] (today, else tomorrow). */
fun nextClockOccurrence(hour: Int, minute: Int, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    var at: ZonedDateTime = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    if (!at.isAfter(now)) at = at.plusDays(1)
    return at.toInstant().toEpochMilli()
}

/**
 * The job row for a valid [form]: a new LLM-mode job bound to [assistantId], or [existing]
 * edited in place (id, history counters and creation time kept). The scheduler fills
 * `nextRunAtMs` when the row is scheduled.
 */
fun BotRoutineForm.toJob(
    assistantId: Uuid,
    existing: ScheduledJobEntity?,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    newId: () -> String = { Uuid.random().toString() },
): ScheduledJobEntity {
    val base = existing ?: ScheduledJobEntity(
        id = newId(),
        name = name.trim(),
        assistantId = assistantId.toString(),
        scheduleType = "cron",
        createdAtMs = nowMs,
    )
    val named = base.copy(
        name = name.trim().take(BotRoutineForm.NAME_MAX),
        prompt = prompt.trim().take(BotRoutineForm.PROMPT_MAX),
        mode = "llm",
        actionsJson = null,
        // Editing keeps a paused routine paused; only a brand-new routine, or re-arming a
        // one-time routine that already fired, switches it on.
        enabled = when {
            existing == null -> true
            kind == BotRoutineKind.ONCE && existing.scheduleType == "once" && existing.lastRunAtMs != null -> true
            else -> existing.enabled
        },
        nextRunAtMs = null,
    )
    return if (kind == BotRoutineKind.ONCE) {
        // A one-time job fires only while lastRunAtMs is null, so a re-armed one starts fresh.
        named.copy(
            scheduleType = "once",
            atUnixMs = nextClockOccurrence(hour, minute, nowMs, zone),
            cronExpression = null,
            lastRunAtMs = null,
        )
    } else {
        named.copy(scheduleType = "cron", cronExpression = cronExpression(), atUnixMs = null)
    }
}
