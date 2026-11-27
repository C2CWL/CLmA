package com.clma.app.data.botmode

import com.clma.app.data.db.entity.ScheduledJobEntity
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class BotRoutineScheduleTest {

    private val assistantId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val todayAtNine = Instant.parse("2026-11-09T01:00:00Z").toEpochMilli()
    private val tomorrowAtNine = Instant.parse("2026-11-10T01:00:00Z").toEpochMilli()
    private val form = BotRoutineForm(name = "Morning briefing", prompt = "Summarize today's tasks")

    @Test
    fun `cron parses daily and weekly schedules`() {
        val cases = listOf(
            "0 9 * * *" to BotRoutineSchedule.Daily(9, 0),
            "@daily" to BotRoutineSchedule.Daily(0, 0),
            "0 9 * * MON-FRI" to BotRoutineSchedule.Weekdays(9, 0),
            "30 8 * * 1-5" to BotRoutineSchedule.Weekdays(8, 30),
            "0 9 * * MON,TUE,WED,THU,FRI" to BotRoutineSchedule.Weekdays(9, 0),
            "0 9 * * MON,WED" to BotRoutineSchedule.Weekly(listOf(MONDAY, WEDNESDAY), 9, 0),
        )
        cases.forEach { (expression, expected) ->
            assertEquals(expression, expected, parseBotRoutineCron(expression))
        }
    }

    @Test
    fun `cron parses minute and hour intervals`() {
        val cases = listOf(
            "@every 30m" to BotRoutineSchedule.EveryMinutes(30),
            "*/15 * * * *" to BotRoutineSchedule.EveryMinutes(15),
            "@every 2h" to BotRoutineSchedule.EveryHours(2),
            "0 */3 * * *" to BotRoutineSchedule.EveryHours(3),
        )
        cases.forEach { (expression, expected) ->
            assertEquals(expression, expected, parseBotRoutineCron(expression))
        }
    }

    @Test
    fun `cron preserves unsupported expressions as custom schedules`() {
        listOf("0 9 1 * *", "@every 90m", "invalid cron").forEach { expression ->
            assertEquals(BotRoutineSchedule.Custom(expression), parseBotRoutineCron(expression))
        }
    }

    @Test
    fun `once schedule uses the job timestamp`() {
        val once = job().copy(scheduleType = "once", atUnixMs = todayAtNine)
        assertEquals(BotRoutineSchedule.Once(todayAtNine), parseBotRoutineSchedule(once))
    }

    @Test
    fun `clock pads minutes to two digits`() {
        val originalLocale = Locale.getDefault(Locale.Category.FORMAT)
        Locale.setDefault(Locale.Category.FORMAT, Locale.US)
        try {
            assertEquals("9:00", botRoutineClock(9, 0))
            assertEquals("18:05", botRoutineClock(18, 5))
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, originalLocale)
        }
    }

    @Test
    fun `row summary picks the soonest enabled routine and counts the others`() {
        val later = job("later").copy(nextRunAtMs = 300L)
        val soonest = job("soonest").copy(nextRunAtMs = 200L)
        val unscheduled = job("unscheduled")
        val disabled = job("disabled").copy(enabled = false, nextRunAtMs = 100L)
        val otherBot = job("other bot").copy(
            assistantId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            nextRunAtMs = 50L,
        )

        assertEquals(
            BotRoutineRowSummary(soonest, others = 2),
            botRoutineRowSummary(listOf(later, disabled, otherBot, unscheduled, soonest), assistantId),
        )
        assertNull(botRoutineRowSummary(listOf(disabled, otherBot), assistantId))
        assertNull(botRoutineRowSummary(emptyList(), assistantId))
    }

    @Test
    fun `cron jobs round trip through the editor form`() {
        val cases = listOf(
            Triple("5 18 * * *", BotRoutineKind.DAILY, "5 18 * * *"),
            Triple("30 8 * * 1-5", BotRoutineKind.WEEKDAYS, "30 8 * * MON-FRI"),
            Triple("15 10 * * WED,MON", BotRoutineKind.WEEKLY, "15 10 * * MON,WED"),
            Triple("*/15 * * * *", BotRoutineKind.EVERY_MINUTES, "@every 15m"),
            Triple("0 */3 * * *", BotRoutineKind.EVERY_HOURS, "@every 3h"),
            Triple("0 9 1 * *", BotRoutineKind.CUSTOM, "0 9 1 * *"),
        )
        cases.forEach { (expression, expectedKind, expectedCron) ->
            val existing = job().copy(cronExpression = expression)
            val edited = botRoutineFormFrom(existing, zone)

            assertEquals(expression, expectedKind, edited.kind)
            assertEquals(expression, expectedCron, edited.cronExpression())
            assertEquals(existing.name, edited.name)
            assertEquals(existing.prompt, edited.prompt)
        }
    }

    @Test
    fun `once job loads its local clock time and has no cron expression`() {
        val at = Instant.parse("2026-11-09T10:05:00Z").toEpochMilli()
        val edited = botRoutineFormFrom(job().copy(scheduleType = "once", atUnixMs = at), zone)

        assertEquals(BotRoutineKind.ONCE, edited.kind)
        assertEquals(18, edited.hour)
        assertEquals(5, edited.minute)
        assertNull(edited.cronExpression())
    }

    @Test
    fun `validation reports the invalid field`() {
        val cases = listOf(
            form.copy(name = " ") to BotRoutineFormError.NAME,
            form.copy(prompt = " ") to BotRoutineFormError.PROMPT,
            form.copy(kind = BotRoutineKind.WEEKLY, days = emptySet()) to BotRoutineFormError.DAYS,
            form.copy(kind = BotRoutineKind.EVERY_MINUTES, interval = 0) to BotRoutineFormError.INTERVAL,
            form.copy(kind = BotRoutineKind.EVERY_MINUTES, interval = 60) to BotRoutineFormError.INTERVAL,
            form.copy(kind = BotRoutineKind.EVERY_HOURS, interval = 24) to BotRoutineFormError.INTERVAL,
            form.copy(kind = BotRoutineKind.CUSTOM, cron = "invalid cron") to BotRoutineFormError.CRON,
        )
        cases.forEach { (invalid, expected) ->
            assertEquals(invalid.toString(), expected, invalid.validate())
        }
        assertNull(form.validate())
    }

    @Test
    fun `next clock occurrence must be strictly after now`() {
        assertEquals(todayAtNine, nextClockOccurrence(9, 0, todayAtNine - 1, zone))
        assertEquals(tomorrowAtNine, nextClockOccurrence(9, 0, todayAtNine, zone))
        assertEquals(tomorrowAtNine, nextClockOccurrence(9, 0, todayAtNine + 1, zone))
    }

    @Test
    fun `new cron job is bound to the bot and runs in llm mode`() {
        val created = form.copy(kind = BotRoutineKind.DAILY).toJob(
            assistantId, existing = null, nowMs = todayAtNine, zone = zone,
            newId = { "new-routine" },
        )

        assertEquals("new-routine", created.id)
        assertEquals(assistantId.toString(), created.assistantId)
        assertEquals("llm", created.mode)
        assertEquals("cron", created.scheduleType)
        assertEquals("0 9 * * *", created.cronExpression)
        assertEquals(todayAtNine, created.createdAtMs)
    }

    @Test
    fun `editing a completed once job re-arms it and preserves its identity and counters`() {
        val existing = job().copy(
            scheduleType = "once",
            atUnixMs = todayAtNine,
            lastRunAtMs = todayAtNine,
            runsSoFar = 1,
        )
        val updated = form.copy(kind = BotRoutineKind.ONCE).toJob(
            assistantId, existing = existing, nowMs = todayAtNine, zone = zone,
            newId = { error("Editing should reuse the existing ID") },
        )

        assertEquals("once", updated.scheduleType)
        assertEquals(tomorrowAtNine, updated.atUnixMs)
        assertNull(updated.cronExpression)
        assertNull(updated.lastRunAtMs)
        assertEquals(existing.id, updated.id)
        assertEquals(existing.createdAtMs, updated.createdAtMs)
        assertEquals(existing.runsSoFar, updated.runsSoFar)
    }

    private fun job(id: String = "routine") = ScheduledJobEntity(
        id = id,
        name = form.name,
        prompt = form.prompt,
        assistantId = assistantId.toString(),
        scheduleType = "cron",
        cronExpression = "0 9 * * *",
        createdAtMs = 10L,
    )
}
