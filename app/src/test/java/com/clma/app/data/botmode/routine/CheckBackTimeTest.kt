package com.clma.app.data.botmode.routine

import java.time.Instant
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckBackTimeTest {
    private val now = ZonedDateTime.parse("2026-11-27T17:00+08:00[Asia/Shanghai]")

    @Test fun relativeTimesUseTheCurrentInstant() {
        assertEquals(Instant.parse("2026-11-27T11:00:00Z"), parseCheckBackTime("in 2 hours", now))
        assertEquals(Instant.parse("2026-11-27T09:01:00Z"), parseCheckBackTime("in 1 minute", now))
        assertEquals(Instant.parse("2026-11-27T09:00:12Z"), parseCheckBackTime("after 12 secs", now))
    }

    @Test fun tomorrowAndWeekdaysUseTheAppTimeZone() {
        assertEquals(Instant.parse("2026-11-28T01:00:00Z"), parseCheckBackTime("tomorrow 9am", now))
        assertEquals(Instant.parse("2026-11-27T10:00:00Z"), parseCheckBackTime("Fri 18:00", now))
        assertEquals(Instant.parse("2026-12-04T01:00:00Z"), parseCheckBackTime("Friday 9am", now))
        assertEquals(Instant.parse("2026-12-04T04:00:00Z"), parseCheckBackTime("next Fri at noon", now))
    }

    @Test fun isoSupportsOffsetsAndLocalWallTime() {
        val due = Instant.parse("2026-11-27T13:30:00Z")
        assertEquals(due, parseCheckBackTime("2026-11-27T14:30+01:00", now))
        assertEquals(due, parseCheckBackTime("2026-11-27T21:30", now))
        assertEquals(due, parseCheckBackTime("2026-11-27T13:30:00Z", now))
    }

    @Test fun wallTimeMovesThroughAGapAndChoosesTheFirstRepeat() {
        val spring = ZonedDateTime.parse("2026-03-07T22:00-05:00[America/New_York]")
        val fall = ZonedDateTime.parse("2026-10-31T22:00-04:00[America/New_York]")
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), parseCheckBackTime("tomorrow 2:30am", spring))
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), parseCheckBackTime("tomorrow 1:30am", fall))
    }

    @Test fun invalidOrPastTimesAreRejected() {
        assertNull(parseCheckBackTime("in 0 hours", now))
        assertNull(parseCheckBackTime("tomorrow 26:00", now))
        assertNull(parseCheckBackTime("2026-11-26T10:00:00Z", now))
        assertNull(parseCheckBackTime("whenever", now))
    }
}
