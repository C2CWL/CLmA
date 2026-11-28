package com.clma.app.data.botmode.routine

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutineRulesTest {
    private fun time(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun intervalKeepsItsAnchorAcrossHoursAndIncludesTheCurrentSlot() {
        val interval = RoutineInterval(47, time("2026-11-27T08:50:00Z"))
        assertEquals(time("2026-11-27T09:37:00Z"), interval.nextAt(time("2026-11-27T09:37:00Z"), ZoneOffset.UTC, null))
        assertEquals(time("2026-11-27T10:24:00Z"), interval.nextAt(time("2026-11-27T09:37:00.001Z"), ZoneOffset.UTC, null))
        assertEquals(interval.anchorMs, interval.nextAt(interval.anchorMs - 1, ZoneOffset.UTC, null))
    }

    @Test fun intervalSkipsNightAndWeekendAndStopsAfterItsEnd() {
        val zone = ZoneId.of("Asia/Shanghai")
        val interval = RoutineInterval(30, time("2026-11-27T01:00:00Z"), (1..5).toSet(), 540, 1080)
        val fridayClosing = time("2026-11-27T10:00:00Z")
        val mondayOpening = time("2026-11-30T01:00:00Z")
        assertEquals(mondayOpening, interval.nextAt(fridayClosing, zone, null))
        assertEquals(mondayOpening, interval.nextAt(fridayClosing, zone, mondayOpening))
        assertNull(interval.nextAt(fridayClosing, zone, mondayOpening - 1))
    }

    @Test fun intervalSkipsAWindowRemovedByAClockChange() {
        val zone = ZoneId.of("America/New_York")
        val interval = RoutineInterval(30, time("2026-03-08T07:00:00Z"), setOf(7), 120, 180)
        assertEquals(time("2026-03-15T06:00:00Z"), interval.nextAt(interval.anchorMs, zone, null))
    }

    @Test fun overlapAdmitsOnlyOneQueuedOccurrence() {
        assertEquals(RoutineAdmission.RUN, routineAdmission(false, RoutineOverlap.SKIP, false))
        assertEquals(RoutineAdmission.SKIP, routineAdmission(true, RoutineOverlap.SKIP, false))
        assertEquals(RoutineAdmission.QUEUE, routineAdmission(true, RoutineOverlap.QUEUE, false))
        assertEquals(RoutineAdmission.SKIP, routineAdmission(true, RoutineOverlap.QUEUE, true))
    }

    @Test fun quotaResetAcceptsSecondsHttpDateAndEpochButExpiresAfter12Hours() {
        val now = time("2026-11-27T08:00:00Z")
        assertEquals(now + 31_000, quotaRetryAt(now, "30", null))
        assertEquals(now + 31_000, quotaRetryAt(now, "Fri, 27 Nov 2026 08:00:30 GMT", null))
        assertEquals(now + 31_000, quotaRetryAt(now, null, ((now + 30_000) / 1000).toString()))
        assertEquals(now + 31_000, quotaRetryAt(now, null, (now + 30_000).toString()))
        assertEquals(now + 31_000, quotaRetryAt(now, null, "2026-11-27T08:00:30Z"))
        assertEquals(now + 3_724_500, quotaRetryAt(now, null, "1h2m3.5s"))
        assertEquals(450L, providerResetDelayMs("450ms"))
        assertNull(providerResetDelayMs("wait 3s"))
        assertNull(quotaRetryAt(now, "43201", null))
        assertNull(quotaRetryAt(now, null, "2026-11-27T07:59:30Z"))
    }
}
