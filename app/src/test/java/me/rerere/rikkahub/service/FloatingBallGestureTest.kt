package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingBallGestureTest {

    @Test
    fun `settle keeps start side inside the dead zone`() {
        // Screen 1000px; centre at 500 is exactly mid — must not flip.
        assertTrue(FloatingBallGesture.settleOnRight(500f, 1000, startedOnRight = true))
        assertFalse(FloatingBallGesture.settleOnRight(500f, 1000, startedOnRight = false))
        // Just inside the band on either side of mid still sticks.
        assertTrue(FloatingBallGesture.settleOnRight(610f, 1000, startedOnRight = true))
        assertFalse(FloatingBallGesture.settleOnRight(390f, 1000, startedOnRight = false))
    }

    @Test
    fun `settle flips only past the hysteresis thresholds`() {
        assertFalse(FloatingBallGesture.settleOnRight(379f, 1000, startedOnRight = true))
        assertTrue(FloatingBallGesture.settleOnRight(621f, 1000, startedOnRight = false))
        // Wide capsule pulled inward from the right but not past 38%: stays right.
        // centre 400 on 1080 ≈ 0.37 — left; 420 ≈ 0.39 — keep right.
        assertTrue(FloatingBallGesture.settleOnRight(420f, 1080, startedOnRight = true))
        assertFalse(FloatingBallGesture.settleOnRight(400f, 1080, startedOnRight = true))
    }

    @Test
    fun `topPreservingCenter keeps the midpoint stable`() {
        // Full capsule (120) → handle (60): top moves down so centre stays at 200+60=260.
        assertEquals(230, FloatingBallGesture.topPreservingCenter(oldTop = 200, oldHeight = 120, newHeight = 60))
        // Handle → full: top moves up so centre stays put (no downward expand jerk).
        assertEquals(200, FloatingBallGesture.topPreservingCenter(oldTop = 230, oldHeight = 60, newHeight = 120))
    }

    @Test
    fun `topPreservingCenter is a no-op when height is unknown or unchanged`() {
        assertEquals(40, FloatingBallGesture.topPreservingCenter(40, oldHeight = 0, newHeight = 80))
        assertEquals(40, FloatingBallGesture.topPreservingCenter(40, oldHeight = 80, newHeight = 0))
        assertEquals(40, FloatingBallGesture.topPreservingCenter(40, oldHeight = 80, newHeight = 80))
    }
}
