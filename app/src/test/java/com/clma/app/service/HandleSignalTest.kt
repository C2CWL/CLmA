package com.clma.app.service

import com.clma.app.data.datastore.EdgeBarColor
import com.clma.app.service.EdgeBarLook.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
    fun `the streak laps the outline once per loop, and rests in place with reduced motion`() {
        val loop = EdgeBarLook.RING_LOOP_MS
        assertEquals(1440L, loop)
        assertEquals(0f, EdgeBarLook.ringPhase(0L, animate = true), 1e-4f)
        assertEquals(0.5f, EdgeBarLook.ringPhase(loop / 2, animate = true), 1e-4f)
        assertEquals(0f, EdgeBarLook.ringPhase(loop, animate = true), 1e-4f)
        for (t in 0L..(3 * loop) step 97L) {
            assertTrue(EdgeBarLook.ringPhase(t, animate = true) in 0f..1f)
            assertTrue(EdgeBarLook.ringHueShift(t, animate = true) in 0f..1f)
        }
        assertEquals(EdgeBarLook.RING_STILL_PHASE, EdgeBarLook.ringPhase(123L, animate = false), 0f)
        assertEquals(EdgeBarLook.ringHueShift(5L, animate = false), EdgeBarLook.ringHueShift(9999L, animate = false), 0f)
    }

    @Test
    fun `hues shift slowly and never jump when the head wraps around`() {
        val loop = EdgeBarLook.RING_LOOP_MS
        // Just before and just after a lap ends the palette has barely moved.
        val before = EdgeBarLook.ringHueShift(loop - 1, animate = true)
        val after = EdgeBarLook.ringHueShift(loop + 1, animate = true)
        assertTrue(kotlin.math.abs(before - after) < 0.01f)
        // One full lap rotates the palette by the configured amount.
        assertEquals(EdgeBarLook.RING_HUE_SHIFT_PER_LOOP, EdgeBarLook.ringHueShift(loop, animate = true), 1e-4f)
        // The clock wrap (20 laps) is seamless too.
        // (Hue is circular: 0.999 and 0.001 are neighbours.)
        fun hueGap(a: Float, b: Float): Float {
            val d = kotlin.math.abs(a - b)
            return minOf(d, 1f - d)
        }
        val wrap = loop * 20
        assertTrue(
            hueGap(
                EdgeBarLook.ringHueShift(wrap - 1, animate = true),
                EdgeBarLook.ringHueShift(wrap + 1, animate = true),
            ) < 0.01f
        )
    }

    @Test
    fun `streak has a bright head and a tail fading out behind it, clockwise`() {
        val style = EdgeBarLook.RING_ON_DARK
        fun alpha(u: Float, phase: Float) = EdgeBarLook.ringColor(u, phase, 0f, style) ushr 24
        val head = 0.5f
        // Head is (nearly) opaque.
        assertTrue(alpha(head, head) >= 250)
        // Behind the head (smaller u: the head moves towards larger u) the tail fades out ...
        val nearTail = alpha(head - 0.05f, head)
        val midTail = alpha(head - 0.2f, head)
        assertTrue(nearTail > midTail)
        assertTrue(midTail > alpha(head - 0.4f, head))
        // ... and past the tail length only the faint track remains.
        val track = Math.round(style.baseAlpha * 255f)
        assertEquals(track, alpha(head - EdgeBarLook.RING_TAIL - 0.05f, head))
        // Just ahead of the head (beyond the short soft tip) is track too.
        assertEquals(track, alpha(head + 0.05f, head))
        // Wraps around the start point.
        assertTrue(alpha(0.98f, 0.02f) > alpha(0.02f + 0.05f, 0.02f))
    }

    @Test
    fun `light bars get the toned-down streak, dark bars the normal one`() {
        assertSame(EdgeBarLook.RING_ON_LIGHT, EdgeBarLook.ringStyle(EdgeBarColor.FOLLOW_THEME.argb(dark = false)))
        assertSame(EdgeBarLook.RING_ON_DARK, EdgeBarLook.ringStyle(EdgeBarColor.FOLLOW_THEME.argb(dark = true)))
        assertSame(EdgeBarLook.RING_ON_DARK, EdgeBarLook.ringStyle(EdgeBarColor.BLUE.argb(dark = false)))

        val light = EdgeBarLook.RING_ON_LIGHT
        val dark = EdgeBarLook.RING_ON_DARK
        assertEquals(1f, light.saturation, 0f)
        assertTrue(light.value < dark.value) // slightly darker rainbow
        assertTrue(light.hot < dark.hot) // toned-down white head
        assertEquals(0x968C80, light.base) // warm-grey track
        assertEquals(0xFFFFFF, dark.base)

        // Far from the head the light style draws its warm-grey track colour.
        val far = EdgeBarLook.ringColor(0.0f, 0.5f, 0f, light)
        assertEquals(0x968C80, far and 0xFFFFFF)
    }

    @Test
    fun `outside the streak the outline is exactly the faint track`() {
        for (style in listOf(EdgeBarLook.RING_ON_DARK, EdgeBarLook.RING_ON_LIGHT)) {
            val phase = 0.3f
            val track = EdgeBarLook.ringTrack(style)
            val start = EdgeBarLook.ringStreakStart(phase)
            val end = EdgeBarLook.ringStreakEnd(phase)
            assertEquals(EdgeBarLook.RING_TAIL, phase - start, 1e-6f)
            // Everywhere between the streak's tip and the end of its tail (going round) is track.
            for (i in 1 until 50) {
                val u = end + (start + 1f - end) * i / 50f
                assertEquals("at $u", track, EdgeBarLook.ringColor(u, phase, 0.1f, style))
            }
            // And the streak's own ends blend into it (no visible step at the junctions).
            fun alpha(c: Int) = c ushr 24
            assertTrue(kotlin.math.abs(alpha(EdgeBarLook.ringColor(start, phase, 0.1f, style)) - alpha(track)) <= 1)
            assertTrue(kotlin.math.abs(alpha(EdgeBarLook.ringColor(end, phase, 0.1f, style)) - alpha(track)) <= 1)
        }
    }

    @Test
    fun `hsv matches the mockup palette`() {
        fun rgb(h: Float) = EdgeBarLook.hsv(h, 1f, 1f).toList()
        assertEquals(listOf(1f, 0f, 0f), rgb(0f))
        assertEquals(listOf(0f, 1f, 0f), rgb(1f / 3f).map { Math.round(it).toFloat() })
        assertEquals(listOf(0f, 0f, 1f), rgb(2f / 3f).map { Math.round(it).toFloat() })
        assertEquals(rgb(0.25f), rgb(1.25f))
        assertEquals(rgb(0.75f), rgb(-0.25f))
    }

    @Test
    fun `the pill fits inside its touch area`() {
        assertTrue(EdgeBarLook.BAR_WIDTH_DP <= EdgeBarLook.TOUCH_WIDTH_DP)
        assertTrue(EdgeBarLook.BAR_HEIGHT_DP <= EdgeBarLook.TOUCH_HEIGHT_DP)
        assertTrue(EdgeBarLook.BAR_CORNER_DP * 2 <= EdgeBarLook.BAR_WIDTH_DP)
    }
}
