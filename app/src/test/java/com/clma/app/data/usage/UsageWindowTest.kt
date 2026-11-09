package com.clma.app.data.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageWindowTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 9)

    // ---- geometry ---------------------------------------------------------------------------

    @Test
    fun `a 30 day window starts 29 days before today`() {
        assertEquals(LocalDate.of(2026, 10, 11), UsageWindow.startDate(today, 30))
    }

    @Test
    fun `a one day window is today alone`() {
        assertEquals(today, UsageWindow.startDate(today, 1))
    }

    @Test
    fun `windowDays is coerced to at least one`() {
        assertEquals(today, UsageWindow.startDate(today, 0))
        assertEquals(today, UsageWindow.startDate(today, -5))
    }

    // ---- alignment --------------------------------------------------------------------------

    @Test
    fun `the cutoff is local midnight of the first plotted day`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val since = UsageWindow.startEpochMillis(today, 30, zone)

        val start = Instant.ofEpochMilli(since).atZone(zone)
        assertEquals(LocalDate.of(2026, 10, 11), start.toLocalDate())
        assertEquals(0, start.hour)
        assertEquals(0, start.minute)
        assertEquals(0, start.second)
        assertEquals(0, start.nano)
    }

    /**
     * The bug this replaces: `now - N * 24h` asked in the afternoon lands on the afternoon of day
     * N+1, so the ledger total included a slice of a day the chart dropped.
     */
    @Test
    fun `the cutoff does not depend on the time of day it is asked at`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val expected = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        for (hour in listOf(0, 6, 12, 16, 23)) {
            val asked = today.atTime(hour, 30).atZone(zone)
            assertEquals(expected, UsageWindow.startEpochMillis(asked.toLocalDate(), 7, zone))
        }
    }

    @Test
    fun `nothing before the cutoff belongs to a plotted day`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val days = 30
        val since = UsageWindow.startEpochMillis(today, days, zone)

        assertEquals(
            today.minusDays(days - 1L),
            Instant.ofEpochMilli(since).atZone(zone).toLocalDate(),
        )
        assertEquals(
            today.minusDays(days.toLong()),
            Instant.ofEpochMilli(since - 1000L).atZone(zone).toLocalDate(),
        )
    }

    @Test
    fun `daylight saving does not shift the cutoff off midnight`() {
        // US DST starts 2026-03-08, inside this window.
        val zone = ZoneId.of("America/New_York")
        val since = UsageWindow.startEpochMillis(LocalDate.of(2026, 3, 20), 14, zone)

        val start = Instant.ofEpochMilli(since).atZone(zone)
        assertEquals(LocalDate.of(2026, 3, 7), start.toLocalDate())
        assertEquals("00:00", start.toLocalTime().toString())
        assertTrue(start.toLocalDate().toEpochDay() < LocalDate.of(2026, 3, 8).toEpochDay())
    }

    @Test
    fun `one extra day of window moves the cutoff back exactly one day`() {
        val zone = ZoneOffset.UTC
        val seven = UsageWindow.startEpochMillis(today, 7, zone)
        val eight = UsageWindow.startEpochMillis(today, 8, zone)

        assertEquals(
            today.minusDays(6),
            Instant.ofEpochMilli(seven).atZone(zone).toLocalDate(),
        )
        assertEquals(seven - 24L * 60L * 60L * 1000L, eight)
    }
}
