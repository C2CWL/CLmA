package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloBallLookTest {

    @Test
    fun `alpha fraction replaces only the alpha byte`() {
        assertEquals(0xD9123456.toInt(), HaloBallLook.withAlphaFraction(0xFF123456.toInt(), 0.85f))
        assertEquals(0x00123456, HaloBallLook.withAlphaFraction(0xFF123456.toInt(), 0f))
        assertEquals(0xFF123456.toInt(), HaloBallLook.withAlphaFraction(0x00123456, 1f))
        // Out-of-range fractions clamp.
        assertEquals(0xFF123456.toInt(), HaloBallLook.withAlphaFraction(0x00123456, 2f))
        assertEquals(0x00123456, HaloBallLook.withAlphaFraction(0xFF123456.toInt(), -1f))
    }

    @Test
    fun `tucked window leaves exactly the strip on screen`() {
        // 1080px screen, 154px ball, 44px strip.
        val right = HaloBallLook.tuckedX(onRight = true, screenWidth = 1080, size = 154, strip = 44)
        assertEquals(1080 - 44, right)
        val left = HaloBallLook.tuckedX(onRight = false, screenWidth = 1080, size = 154, strip = 44)
        assertEquals(44 - 154, left)
        assertEquals(44, left + 154) // right edge of the window is 44px into the screen
    }

    @Test
    fun `strip is clamped to the ball size`() {
        assertEquals(1080 - 100, HaloBallLook.tuckedX(true, 1080, size = 100, strip = 500))
        assertEquals(1080 - 1, HaloBallLook.tuckedX(true, 1080, size = 100, strip = 0))
    }

    @Test
    fun `handle sits flush against the screen edge`() {
        val size = 154
        val strip = 44
        val handle = 16
        // Right edge: the screen edge is at view x = strip.
        val rightLeft = HaloBallLook.handleLeft(onRight = true, size = size, strip = strip, handleWidth = handle)
        assertEquals(strip, rightLeft + handle)
        val windowX = HaloBallLook.tuckedX(true, 1080, size, strip)
        assertEquals(1080, windowX + rightLeft + handle)
        // Left edge: the screen edge is at view x = size - strip.
        val leftLeft = HaloBallLook.handleLeft(onRight = false, size = size, strip = strip, handleWidth = handle)
        val leftWindowX = HaloBallLook.tuckedX(false, 1080, size, strip)
        assertEquals(0, leftWindowX + leftLeft)
    }

    @Test
    fun `handle fits inside the tucked strip`() {
        assertTrue(HaloBallLook.HANDLE_WIDTH_DP < HaloBallLook.HANDLE_STRIP_DP)
        // A thumb-sized strip to grab and drag out, but not more than half the 56dp ball.
        assertTrue(HaloBallLook.HANDLE_STRIP_DP in 28f..32f)
        assertTrue(HaloBallLook.HANDLE_WIDTH_DP in 9f..10f)
        assertEquals(HaloBallLook.HANDLE_WIDTH_DP, CapsuleBallView.HANDLE_WIDTH_DP)
        assertEquals(HaloBallLook.HANDLE_STRIP_DP, CapsuleBallView.HANDLE_TOUCH_DP)
        assertTrue(HaloBallLook.BACKING_RADIUS_FRACTION < 1f)
        assertTrue(HaloBallLook.BACKING_RADIUS_FRACTION > HaloBallView.RING_RADIUS_FRACTION + HaloBallView.STROKE_FRACTION / 2f)
    }
}
