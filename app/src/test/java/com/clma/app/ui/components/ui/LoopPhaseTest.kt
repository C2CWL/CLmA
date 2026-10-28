package com.clma.app.ui.components.ui

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
    fun `rising dots rest in the static frame`() {
        for (i in 0..2) assertEquals(0f, risingDotLift(ATOM_DOTS_STATIC_PHASE, i), 1e-5f)
    }

    @Test
    fun `rising dots lift one after another`() {
        // Early in the loop the first dot is up while the later ones still rest.
        assertTrue(risingDotLift(0.1f, 0) > 0.5f)
        assertEquals(0f, risingDotLift(0.1f, 2), 1e-5f)
        for (i in 0..2) {
            for (step in 0..100) {
                val lift = risingDotLift(step / 100f, i)
                assertTrue(lift in 0f..1f)
            }
        }
    }

    @Test
    fun `a coarse phase is the wall-clock position in the loop`() {
        assertEquals(0f, loopPhaseAt(0, 3200, 0.25f), 1e-6f)
        assertEquals(0.5f, loopPhaseAt(1600, 3200, 0.25f), 1e-6f)
        assertEquals(0.25f, loopPhaseAt(4000, 3200, 0f), 1e-6f)
        assertEquals(0.75f, loopPhaseAt(-800, 3200, 0f), 1e-6f)
        assertEquals(0.25f, loopPhaseAt(100, 0, 0.25f), 1e-6f)
    }
}
