package com.clma.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

class FrameWaiterCaptureTest {
    private val stack = { arrayOf(StackTraceElement("example.Producer", "awaitFrame", "SecretPath.kt", 42)) }

    @Test fun `counts actual entry separately from tagged site and bounds stacks to eight`() {
        val c = FrameWaiterCapture(0, stack)
        repeat(12) { n ->
            val callback = Any()
            c.site(callback, "site$n", "app_frame_site", 10)
            c.await(callback, 10)
        }
        val unconsumed = Any()
        c.site(unconsumed, "not_a_broadcast_clock", "haze_pre_draw_site", 10)
        val report = c.finish("expired", 10_000)
        assertTrue(report.first().contains("registrations=12 site_calls=13"))
        assertTrue(report.first().contains("stacks=8"))
        assertEquals(8, report.count { it.contains("first_registration_stack=") })
        assertFalse(report.joinToString().contains("SecretPath"))
    }
    @Test fun `deadline rejects events and does not capture stacks after ten seconds`() {
        var captures = 0
        val c = FrameWaiterCapture(100, { captures++; stack() })
        c.await(Any(), 10_099)
        c.await(Any(), 10_100)
        c.await(Any(), 20_000)
        assertFalse(c.accepting)
        assertEquals(1, captures)
        assertTrue(c.finish("page_exit", 20_001).first().contains("reason=expired window_ms=10000 registrations=1"))
    }
    @Test fun `source and identity tables are bounded and counters saturate`() {
        val c = FrameWaiterCapture(0, stack)
        repeat(100) { c.site(Any(), "site$it", "app_frame_site", 1) }
        val first = c.finish("stop", 2).first()
        assertTrue(first.contains("sources=32"))
        assertTrue(first.contains("evicted_tags=36"))
        assertEquals(Long.MAX_VALUE, FrameWaiterCapture.increment(Long.MAX_VALUE))
    }
    @Test fun `callback equality hashCode and toString are never invoked`() {
        val hostile = object {
            override fun hashCode(): Int = error("must not read values")
            override fun equals(other: Any?): Boolean = error("must use identity")
            override fun toString(): String = error("must not log values")
        }
        val c = FrameWaiterCapture(0, stack)
        c.site(hostile, "known_site", "app_frame_site", 1)
        c.await(hostile, 2)
        assertTrue(c.finish("done", 3).first().contains("registrations=1"))
    }
    @Test fun `default disabled and repeated resumes cannot auto restart`() {
        var now = 0L
        val c = FrameWaiterControl({ now }, stack)
        val token = Any()
        assertFalse(c.claim(token))
        c.configure(true)
        assertTrue(c.claim(token))
        assertTrue(c.begin(token))
        now = 10_000
        assertNotNull(c.stop(token, "expired"))
        assertNull(c.active)
        assertFalse(c.claim(Any()))
        c.configure(true)
        assertFalse(c.claim(Any()))
        c.configure(false); c.configure(true)
        assertTrue(c.claim(Any()))
    }
    @Test fun `page exit and disabling stop immediately including pending delayed start`() {
        val c = FrameWaiterControl({ 100 }, stack)
        c.configure(true)
        val first = Any(); assertTrue(c.claim(first))
        assertTrue(c.stop(first, "page_exit")!!.first().contains("cancelled_before_start"))
        assertFalse(c.begin(first))
        assertFalse(c.claim(Any()))
        c.configure(false); c.configure(true)
        val next = Any(); assertTrue(c.claim(next)); assertTrue(c.begin(next))
        val running = c.active!!
        assertNotNull(c.configure(false))
        assertFalse(running.accepting)
        assertNull(c.active)
        assertFalse(c.begin(next))
        assertNull(c.stop(next, "duplicate"))
    }
    @Test fun `closing a running page stops and emits exactly one report`() {
        val c = FrameWaiterControl({ 100 }, stack)
        c.configure(true)
        val token = Any(); c.claim(token); c.begin(token)
        val running = c.active!!
        assertNotNull(c.stop(token, "page_exit"))
        assertFalse(running.accepting)
        assertNull(c.active)
        assertNull(c.stop(token, "page_exit"))
    }
}
