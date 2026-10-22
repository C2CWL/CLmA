package com.clma.app.service

import com.clma.app.service.EdgeBarLook.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleSignalTest {

    @Test
    fun `working wins over an unseen reply, idle otherwise`() {
        assertEquals(Signal.WORKING, EdgeBarLook.signal(working = true, unseenDone = false))
        assertEquals(Signal.WORKING, EdgeBarLook.signal(working = true, unseenDone = true))
        assertEquals(Signal.DONE, EdgeBarLook.signal(working = false, unseenDone = true))
        assertEquals(Signal.IDLE, EdgeBarLook.signal(working = false, unseenDone = false))
    }

    @Test
    fun `the light sweep glides end to end, and rests in the middle with reduced motion`() {
        val sweep = EdgeBarLook.SWEEP_MS
        assertEquals(0f, EdgeBarLook.sweepFraction(0L, animate = true), 1e-4f)
        assertEquals(1f, EdgeBarLook.sweepFraction(sweep, animate = true), 1e-4f)
        assertEquals(0f, EdgeBarLook.sweepFraction(2 * sweep, animate = true), 1e-4f)
        for (t in 0L..(3 * sweep) step 97L) {
            val f = EdgeBarLook.sweepFraction(t, animate = true)
            assertTrue(f in 0f..1f)
        }
        assertEquals(0.5f, EdgeBarLook.sweepFraction(123L, animate = false), 0f)
    }

    @Test
    fun `the pill fits inside its touch area`() {
        assertTrue(EdgeBarLook.BAR_WIDTH_DP <= EdgeBarLook.TOUCH_WIDTH_DP)
        assertTrue(EdgeBarLook.BAR_HEIGHT_DP <= EdgeBarLook.TOUCH_HEIGHT_DP)
        assertTrue(EdgeBarLook.BAR_CORNER_DP * 2 <= EdgeBarLook.BAR_WIDTH_DP)
    }
}
