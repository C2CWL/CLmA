package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopPhaseTest {

    @Test
    fun `loops run unless the animator scale is zero`() {
        assertTrue(loopAnimationsEnabled(1f))
        assertTrue(loopAnimationsEnabled(0.5f))
        assertTrue(loopAnimationsEnabled(10f))
        assertFalse(loopAnimationsEnabled(0f))
        assertFalse(loopAnimationsEnabled(-1f))
    }

    @Test
    fun `an unreadable scale keeps animations on`() {
        assertTrue(loopAnimationsEnabled(Float.NaN))
    }

    @Test
    fun `static frames are meaningful`() {
        // Shimmer: band parked off the right edge, so the label reads plainly.
        assertEquals(100f, shimmerBandStart(SHIMMER_STATIC_PHASE, width = 100f, band = 45f), 0.001f)
        // Sparkle: pentagon at full size.
        assertEquals(1f, sparkleBreath(SPARKLE_STATIC_PHASE), 1e-5f)
    }
}
