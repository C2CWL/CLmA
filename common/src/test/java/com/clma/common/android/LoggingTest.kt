package com.clma.common.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for F6: modules below :app (e.g. :ai) have no BuildConfig of their own, so
 * provider code that wants to gate full request/response body logging behind "is this a
 * debug build" needs a runtime flag set once from :app's BuildConfig.DEBUG instead. Pins
 * that the setter actually flips what the getter reports.
 *
 * [Logging] is a singleton object, so its state persists across test methods within the
 * same JVM — this is a single test that sets both values explicitly rather than two
 * tests that would depend on run order to observe a "default" state.
 */
class LoggingTest {

    @Test
    fun `setDebugLoggingEnabled toggles what isDebugLoggingEnabled reports`() {
        Logging.setDebugLoggingEnabled(false)
        assertFalse(Logging.isDebugLoggingEnabled())

        Logging.setDebugLoggingEnabled(true)
        assertTrue(Logging.isDebugLoggingEnabled())

        Logging.setDebugLoggingEnabled(false)
        assertFalse(Logging.isDebugLoggingEnabled())
    }

    @Test
    fun `setRequestLoggingEnabled toggles what isRequestLoggingEnabled reports`() {
        Logging.setRequestLoggingEnabled(false)
        assertFalse(Logging.isRequestLoggingEnabled())

        Logging.setRequestLoggingEnabled(true)
        assertTrue(Logging.isRequestLoggingEnabled())
    }

    @Test
    fun `logRequest drops entries while recording is off unless forced`() {
        try {
            Logging.clear()
            Logging.setRequestLoggingEnabled(false)

            Logging.logRequest(request("https://example.com/ok"))
            assertTrue(Logging.getRequestLogs().isEmpty())

            Logging.logRequest(request("https://example.com/failed"), force = true)
            assertEquals(listOf("https://example.com/failed"), Logging.getRequestLogs().map { it.url })

            Logging.setRequestLoggingEnabled(true)
            Logging.logRequest(request("https://example.com/ok2"))
            assertEquals(
                listOf("https://example.com/ok2", "https://example.com/failed"),
                Logging.getRequestLogs().map { it.url },
            )
        } finally {
            Logging.setRequestLoggingEnabled(true)
            Logging.clear()
        }
    }

    private fun request(url: String) = LogEntry.RequestLog(tag = "HTTP", url = url, method = "GET")
}
