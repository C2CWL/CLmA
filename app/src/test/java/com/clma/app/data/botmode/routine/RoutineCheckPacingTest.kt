package com.clma.app.data.botmode.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineCheckPacingTest {
    @Test fun capsAtTwentyFourAndExpiresEntriesAfterADay() {
        val start = 100_000L
        var pacing = RoutineCheckPacing()
        repeat(24) {
            assertTrue(pacing.allows(start + it))
            pacing = pacing.admit(start + it)
        }
        assertFalse(pacing.allows(start + 24))
        assertTrue(pacing.allows(start + 86_400_000L))
        assertEquals(24, pacing.admit(start + 86_400_000L).admitted.size)
    }

    @Test fun backsOffWithStableJitterAndClearsAfterSuccess() {
        val now = 100_000L
        val first = RoutineCheckPacing().settle(now, true, "same-run")
        val second = first.settle(now, true, "same-run")
        assertFalse(first.allows(first.backoffUntil - 1))
        assertTrue(first.allows(first.backoffUntil))
        assertTrue(first.backoffUntil - now in 720_000..1_080_000)
        assertTrue(second.backoffUntil - now in 1_440_000..2_160_000)
        assertEquals(RoutineCheckPacing(), second.settle(now, false, "same-run"))
    }
}
