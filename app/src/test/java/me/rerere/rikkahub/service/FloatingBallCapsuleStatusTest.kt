package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.datastore.FloatingBallStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingBallCapsuleStatusTest {

    @Test
    fun `elapsed under an hour is m colon ss`() {
        assertEquals("0:00", formatCapsuleElapsed(0))
        assertEquals("0:07", formatCapsuleElapsed(7_400))
        assertEquals("1:05", formatCapsuleElapsed(65_000))
        assertEquals("59:59", formatCapsuleElapsed(3_599_999))
    }

    @Test
    fun `elapsed over an hour is h colon mm colon ss`() {
        assertEquals("1:00:00", formatCapsuleElapsed(3_600_000))
        assertEquals("1:02:03", formatCapsuleElapsed(3_723_000))
    }

    @Test
    fun `negative elapsed clamps to zero`() {
        assertEquals("0:00", formatCapsuleElapsed(-5_000))
    }

    @Test
    fun `working with a start time ticks`() {
        val s = capsuleStatus(working = true, workingSinceMs = 1_000, nowMs = 13_000, lastTurnMs = null)
        assertEquals(CapsuleStatus.Kind.WORKING, s.kind)
        assertEquals("0:12", s.elapsed)
    }

    @Test
    fun `working without a start time has no timer`() {
        val s = capsuleStatus(working = true, workingSinceMs = null, nowMs = 13_000, lastTurnMs = 5_000)
        assertEquals(CapsuleStatus.Kind.WORKING, s.kind)
        assertNull(s.elapsed)
    }

    @Test
    fun `finished turn shows its duration`() {
        val s = capsuleStatus(working = false, workingSinceMs = null, nowMs = 0, lastTurnMs = 95_000)
        assertEquals(CapsuleStatus.Kind.DONE, s.kind)
        assertEquals("1:35", s.elapsed)
    }

    @Test
    fun `idle is ready`() {
        val s = capsuleStatus(working = false, workingSinceMs = null, nowMs = 0, lastTurnMs = null)
        assertEquals(CapsuleStatus(CapsuleStatus.Kind.READY), s)
    }

    @Test
    fun `style parsing defaults to original`() {
        assertEquals(FloatingBallStyle.ORIGINAL, FloatingBallStyle.fromKey(null))
        assertEquals(FloatingBallStyle.ORIGINAL, FloatingBallStyle.fromKey(""))
        assertEquals(FloatingBallStyle.ORIGINAL, FloatingBallStyle.fromKey("pill"))
        assertEquals(FloatingBallStyle.ORIGINAL, FloatingBallStyle.fromKey("original"))
        assertEquals(FloatingBallStyle.CAPSULE, FloatingBallStyle.fromKey("capsule"))
    }
}
