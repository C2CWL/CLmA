package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SparkleLoadingTest {

    @Test
    fun `every motion closes within the loop`() {
        assertEquals(4800, SPARKLE_LOOP_MS / SPARKLE_SPIN_CYCLES)
        assertEquals(1600, SPARKLE_LOOP_MS / SPARKLE_BREATH_CYCLES)
        assertEquals(1600, SPARKLE_LOOP_MS / SPARKLE_COMET_CYCLES)
        assertEquals(1200, SPARKLE_LOOP_MS / SPARKLE_DOT_CYCLES)
        for (cycles in listOf(SPARKLE_SPIN_CYCLES, SPARKLE_BREATH_CYCLES, SPARKLE_COMET_CYCLES,
            SPARKLE_SWEEP_CYCLES, SPARKLE_DOT_CYCLES, SPARKLE_TWINKLE_CYCLES, SPARKLE_GLINT_CYCLES)) {
            assertEquals(0, SPARKLE_LOOP_MS % cycles)
        }
    }

    @Test
    fun `wave is seamless at the wrap`() {
        assertEquals(0f, sparkleWave(0f, 3), 1e-6f)
        assertEquals(sparkleWave(0f, 3), sparkleWave(1f, 3), 1e-5f)
        assertEquals(1f, sparkleWave(0.5f, 1), 1e-6f)
    }

    @Test
    fun `breathing stays between 0_85 and 1`() {
        assertEquals(SPARKLE_BREATH_MIN, sparkleBreath(0f), 1e-6f)
        var max = 0f
        for (k in 0..1000) {
            val b = sparkleBreath(k / 1000f)
            assertTrue(b in SPARKLE_BREATH_MIN..1f + 1e-6f)
            if (b > max) max = b
        }
        assertEquals(1f, max, 1e-3f)
    }

    @Test
    fun `twinkle stays in range and rests half the time`() {
        var dark = 0
        for (k in 0 until 1000) {
            val v = sparkleTwinkle(k / 1000f, 0.3f, cycles = 2)
            assertTrue(v in 0f..1f)
            if (v == 0f) dark++
        }
        assertTrue(dark in 450..550)
    }

    @Test
    fun `dots rise one after another`() {
        // Find each dot's peak within the first dot cycle; they must be ordered 0, 1, 2.
        val peaks = (0 until 3).map { i ->
            (0 until 1200).maxBy { k -> sparkleDotLift(k / 1200f / SPARKLE_DOT_CYCLES, i) }
        }
        assertTrue(peaks[0] < peaks[1] && peaks[1] < peaks[2])
        for (i in 0 until 3) {
            for (k in 0..100) assertTrue(sparkleDotLift(k / 100f, i) in 0f..1f)
        }
    }

    @Test
    fun `pentagon outline is a gentle rounded shape`() {
        assertEquals(1f, sparklePentagon(0f), 1e-6f)
        assertEquals(0.86f, sparklePentagon((Math.PI / 5).toFloat()), 1e-5f)
    }
}
