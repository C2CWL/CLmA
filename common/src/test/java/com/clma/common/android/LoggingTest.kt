package com.clma.common.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    @get:Rule
    val tempFolder = TemporaryFolder()

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

    @Test
    fun `version changes on every new log and on clear`() {
        try {
            Logging.clear()
            val start = Logging.version.value

            Logging.log("Test", "hello")
            val afterLog = Logging.version.value
            assertTrue(afterLog > start)

            Logging.logRequest(request("https://example.com/v"), force = true)
            val afterRequest = Logging.version.value
            assertTrue(afterRequest > afterLog)

            Logging.clear()
            assertTrue(Logging.version.value > afterRequest)
            assertTrue(Logging.getRecentLogs().isEmpty())
        } finally {
            Logging.clear()
        }
    }

    @Test
    fun `earlier session lines are returned newest first and current session is not repeated`() {
        val dir = tempFolder.newFolder("logs")
        try {
            File(dir, "app.log").writeText("old line 1\nold line 2\n")
            File(dir, "app.log.1").writeText("older line\n")

            Logging.init(dir, LogLevel.INFO)
            Logging.log("Test", "current session line")

            // The new line really went to disk (that is why it must be filtered out here).
            assertTrue(File(dir, "app.log").readText().contains("current session line"))
            assertEquals(
                listOf("old line 2", "old line 1", "older line"),
                Logging.readEarlierSessionLogs(),
            )
        } finally {
            Logging.clear()
            Logging.resetFileLogForTest()
        }
    }

    @Test
    fun `earlier session lines survive a rotation during the current session`() {
        val dir = tempFolder.newFolder("logs-rotate")
        try {
            // Already over the 1 MB limit, so the first write of this session rotates it.
            val filler = "x".repeat(99) + "\n"
            File(dir, "app.log").writeText("first old line\n" + filler.repeat(10_001) + "last old line\n")

            Logging.init(dir, LogLevel.INFO)
            Logging.log("Test", "current session line")

            assertTrue(File(dir, "app.log.1").exists())
            val earlier = Logging.readEarlierSessionLogs()
            assertEquals("last old line", earlier.first())
            assertEquals("first old line", earlier.last())
            assertFalse(earlier.any { it.contains("current session line") })
        } finally {
            Logging.clear()
            Logging.resetFileLogForTest()
        }
    }

    @Test
    fun `clear removes earlier session logs too`() {
        val dir = tempFolder.newFolder("logs-clear")
        try {
            File(dir, "app.log").writeText("old line\n")
            File(dir, "app.log.1").writeText("older line\n")
            Logging.init(dir, LogLevel.INFO)
            assertEquals(listOf("old line", "older line"), Logging.readEarlierSessionLogs())

            Logging.clear()

            assertTrue(Logging.readEarlierSessionLogs().isEmpty())
            assertFalse(File(dir, "app.log").exists())
            assertFalse(File(dir, "app.log.1").exists())
        } finally {
            Logging.clear()
            Logging.resetFileLogForTest()
        }
    }

    @Test
    fun `without init there are no earlier session logs`() {
        Logging.resetFileLogForTest()
        assertTrue(Logging.readEarlierSessionLogs().isEmpty())
    }

    private fun request(url: String) = LogEntry.RequestLog(tag = "HTTP", url = url, method = "GET")
}
