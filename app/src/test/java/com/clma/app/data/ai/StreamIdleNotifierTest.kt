package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamIdleNotifierTest {

    private var now = 1_000_000L

    private fun notifier(
        first: Long = 60_000L,
        stall: Long = 90_000L,
    ) = StreamIdleNotifier(firstOutputNoticeMs = first, stallNoticeMs = stall, clock = { now })

    @Test
    fun `stays silent while the first output is still inside its notice window`() {
        val n = notifier()
        now += 59_000
        assertNull(n.tick())
    }

    @Test
    fun `waits rather than stalls when nothing has been produced yet`() {
        val n = notifier()
        now += 60_000
        val notice = n.tick()
        assertTrue("expected Waiting, got $notice", notice is StreamIdleNotice.Waiting)
        assertEquals(60_000L, (notice as StreamIdleNotice.Waiting).idleMs)
    }

    @Test
    fun `after output starts, a shorter silence is tolerated before it reads as stalled`() {
        val n = notifier()
        n.onChunk(meaningful = true)
        now += 60_000
        assertNull(n.tick())
        now += 30_000
        assertTrue(n.tick() is StreamIdleNotice.Stalled)
    }

    @Test
    fun `chunks that carry no content do not switch to the stall regime`() {
        val n = notifier()
        n.onChunk(meaningful = false)
        now += 60_000
        assertTrue(n.tick() is StreamIdleNotice.Waiting)
    }

    @Test
    fun `resumed activity clears a raised notice exactly once`() {
        val n = notifier()
        now += 60_000
        assertTrue(n.tick() is StreamIdleNotice.Waiting)
        n.onChunk(meaningful = true)
        assertEquals(StreamIdleNotice.Clear, n.tick())
        assertNull(n.tick())
    }

    @Test
    fun `a fresh attempt resets both the clock and the produced-output flag`() {
        val n = notifier()
        n.onChunk(meaningful = true)
        now += 90_000
        assertTrue(n.tick() is StreamIdleNotice.Stalled)
        n.onAttemptStart()
        now += 59_000
        // the notice raised before the retry is withdrawn on the next poll,
        assertEquals(StreamIdleNotice.Clear, n.tick())
        // and only then does the fresh attempt sit silent inside its own window.
        assertNull(n.tick())
    }
}
