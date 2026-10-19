package me.rerere.rikkahub.service

import me.rerere.rikkahub.service.HaloBallLook.HandleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleSignalTest {

    @Test
    fun `working wins over an unseen reply, idle otherwise`() {
        assertEquals(HandleSignal.WORKING, HaloBallLook.handleSignal(working = true, unseenDone = false))
        assertEquals(HandleSignal.WORKING, HaloBallLook.handleSignal(working = true, unseenDone = true))
        assertEquals(HandleSignal.DONE, HaloBallLook.handleSignal(working = false, unseenDone = true))
        assertEquals(HandleSignal.IDLE, HaloBallLook.handleSignal(working = false, unseenDone = false))
    }

    @Test
    fun `the working dot sweeps end to end, and rests in the middle otherwise`() {
        val sweep = HaloBallLook.HANDLE_SWEEP_MS
        assertEquals(0f, HaloBallLook.handleDotFraction(0L, working = true, animate = true), 1e-4f)
        assertEquals(1f, HaloBallLook.handleDotFraction(sweep, working = true, animate = true), 1e-4f)
        assertEquals(0f, HaloBallLook.handleDotFraction(2 * sweep, working = true, animate = true), 1e-4f)
        for (t in 0L..(3 * sweep) step 97L) {
            val f = HaloBallLook.handleDotFraction(t, working = true, animate = true)
            assertTrue(f in 0f..1f)
        }
        // Reduced motion, or the done dot: still, centred.
        assertEquals(0.5f, HaloBallLook.handleDotFraction(123L, working = true, animate = false), 0f)
        assertEquals(0.5f, HaloBallLook.handleDotFraction(123L, working = false, animate = true), 0f)
    }

    @Test
    fun `an unseen reply lifts a faint tucked ball, nothing else changes the opacity`() {
        assertEquals(HaloBallLook.HANDLE_DONE_MIN_ALPHA, HaloBallLook.tuckedAlpha(0.3f, HandleSignal.DONE), 0f)
        assertEquals(0.95f, HaloBallLook.tuckedAlpha(0.95f, HandleSignal.DONE), 0f)
        assertEquals(0.3f, HaloBallLook.tuckedAlpha(0.3f, HandleSignal.WORKING), 0f)
        assertEquals(0.3f, HaloBallLook.tuckedAlpha(0.3f, HandleSignal.IDLE), 0f)
    }

    @Test
    fun `the light dot fits inside the handle pill`() {
        assertTrue(HaloBallLook.HANDLE_DOT_DP < HaloBallLook.HANDLE_WIDTH_DP)
    }
}
