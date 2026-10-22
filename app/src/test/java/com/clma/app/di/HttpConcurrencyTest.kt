package me.rerere.rikkahub.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpConcurrencyTest {

    /**
     * The whole point of this knob: streaming replies hold a per-host slot for a whole turn, so
     * OkHttp's default of 5 concurrent requests to one provider host silently queues the surplus
     * (zero bytes sent, UI stuck on "thinking..."). Pin the ceiling above the default so a future
     * refactor cannot quietly drop back to it.
     */
    @Test
    fun `per-host ceiling is lifted above okhttp's default of five`() {
        assertTrue(
            "expected a per-host ceiling above OkHttp's default of 5, got ${HttpConcurrency.MAX_REQUESTS_PER_HOST}",
            HttpConcurrency.MAX_REQUESTS_PER_HOST > 5,
        )
    }

    @Test
    fun `total ceiling is at least the per-host ceiling`() {
        assertTrue(HttpConcurrency.MAX_REQUESTS >= HttpConcurrency.MAX_REQUESTS_PER_HOST)
    }

    @Test
    fun `the shared dispatcher actually carries the configured ceilings`() {
        val dispatcher = HttpConcurrency.dispatcher()
        assertEquals(HttpConcurrency.MAX_REQUESTS, dispatcher.maxRequests)
        assertEquals(HttpConcurrency.MAX_REQUESTS_PER_HOST, dispatcher.maxRequestsPerHost)
    }
}
