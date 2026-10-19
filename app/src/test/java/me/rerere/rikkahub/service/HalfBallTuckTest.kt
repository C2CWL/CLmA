package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.datastore.FloatingBallTuckStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HalfBallTuckTest {

    @Test
    fun `tuck style defaults to the slim handle`() {
        assertEquals(FloatingBallTuckStyle.HANDLE, FloatingBallTuckStyle.fromKey(null))
        assertEquals(FloatingBallTuckStyle.HANDLE, FloatingBallTuckStyle.fromKey(""))
        assertEquals(FloatingBallTuckStyle.HANDLE, FloatingBallTuckStyle.fromKey("strip"))
        assertEquals(FloatingBallTuckStyle.HANDLE, FloatingBallTuckStyle.fromKey("handle"))
        assertEquals(FloatingBallTuckStyle.HALF_BALL, FloatingBallTuckStyle.fromKey("half_ball"))
    }

    @Test
    fun `half ball leaves half of the ball on screen`() {
        val size = 154
        val strip = HaloBallLook.halfTuckStrip(size)
        assertEquals(77, strip)
        assertEquals(1080 - 77, HaloBallLook.tuckedX(onRight = true, screenWidth = 1080, size = size, strip = strip))
        assertEquals(77 - 154, HaloBallLook.tuckedX(onRight = false, screenWidth = 1080, size = size, strip = strip))
        assertEquals(1, HaloBallLook.halfTuckStrip(1))
    }

    @Test
    fun `visible span is exactly the on-screen part`() {
        val size = 154
        val strip = HaloBallLook.halfTuckStrip(size)
        for (onRight in listOf(true, false)) {
            val windowX = HaloBallLook.tuckedX(onRight, 1080, size, strip)
            val (l, r) = HaloBallLook.visibleSpan(onRight, size, strip)
            assertEquals(strip, r - l)
            // Screen x of the span sits inside [0, 1080) and touches the edge it hugs.
            val screenL = windowX + l
            val screenR = windowX + r
            assertTrue(screenL >= 0 && screenR <= 1080)
            if (onRight) assertEquals(1080, screenR) else assertEquals(0, screenL)
        }
    }

    @Test
    fun `done marker sits on the visible half`() {
        val (dxRight, dyRight) = HaloBallLook.doneMarkerDirection(onRight = true)
        val (dxLeft, dyLeft) = HaloBallLook.doneMarkerDirection(onRight = false)
        // Tucked right, the left half is on screen; tucked left, the right half.
        assertTrue(dxRight < 0f)
        assertTrue(dxLeft > 0f)
        assertTrue(dyRight < 0f && dyLeft < 0f)
        assertEquals(1f, dxRight * dxRight + dyRight * dyRight, 1e-5f)
    }
}
