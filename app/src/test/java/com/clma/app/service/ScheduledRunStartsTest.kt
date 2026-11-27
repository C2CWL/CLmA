package com.clma.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledRunStartsTest {
    @Test
    fun lastCompletionStopsServiceUsingLatestStartId() {
        val runs = ScheduledRunStarts()
        runs.start(1)
        runs.start(2)

        // The second start may finish before the first.
        assertEquals(2, runs.activeCount)
        assertNull(runs.finish())
        assertEquals(2, runs.finish())
        assertEquals(0, runs.activeCount)
    }

    @Test
    fun newStartDuringOverlappingRunsUpdatesStopId() {
        val runs = ScheduledRunStarts()
        runs.start(1)
        runs.start(2)
        assertNull(runs.finish())

        runs.start(3)
        assertNull(runs.finish())
        assertEquals(3, runs.finish())
    }
}
