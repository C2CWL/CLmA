package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShimmerLoadingTest {

    @Test
    fun `band starts fully left and ends fully right`() {
        assertEquals(-45f, shimmerBandStart(0f, width = 100f, band = 45f), 0.001f)
        assertEquals(100f, shimmerBandStart(1f, width = 100f, band = 45f), 0.001f)
        assertEquals(27.5f, shimmerBandStart(0.5f, width = 100f, band = 45f), 0.001f)
    }

    @Test
    fun `band progress is clamped`() {
        assertEquals(-45f, shimmerBandStart(-1f, 100f, 45f), 0.001f)
        assertEquals(100f, shimmerBandStart(3f, 100f, 45f), 0.001f)
    }

    @Test
    fun `ease is a smooth 0 to 1`() {
        assertEquals(0f, shimmerEase(0f), 0f)
        assertEquals(0.5f, shimmerEase(0.5f), 0.0001f)
        assertEquals(1f, shimmerEase(1f), 0f)
        assertEquals(1f, shimmerEase(2f), 0f)
    }

    @Test
    fun `sweep easing runs then holds off-screen`() {
        val sweep = SHIMMER_SWEEP_MS.toFloat() / (SHIMMER_SWEEP_MS + SHIMMER_PAUSE_MS)
        assertEquals(0f, shimmerSweepEasing(0f), 0f)
        assertEquals(0.5f, shimmerSweepEasing(sweep / 2f), 0.0001f)
        assertEquals(1f, shimmerSweepEasing(sweep), 0f)
        assertEquals(1f, shimmerSweepEasing(0.99f), 0f)
    }

    @Test
    fun `bar keeps a minimum height in small slots`() {
        assertEquals(32f, shimmerBarHeightDp(100f), 0.001f)
        // 28dp slot: 0.32 * 28 = 8.96dp, above the floor.
        assertEquals(8.96f, shimmerBarHeightDp(28f), 0.001f)
        // Tiny slot: floor wins.
        assertEquals(SHIMMER_BAR_MIN_HEIGHT_DP, shimmerBarHeightDp(10f), 0.001f)
        // Never taller than the slot itself.
        assertEquals(4f, shimmerBarHeightDp(4f), 0.001f)
        assertEquals(0f, shimmerBarHeightDp(-3f), 0.001f)
    }
}
