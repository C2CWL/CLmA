package com.clma.app.data.botmode

import com.clma.ai.util.HttpException
import com.clma.ai.util.withHttpFailureMetadata
import java.time.Instant
import kotlinx.coroutines.CancellationException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BotQuotaPolicyTest {
    private val now = Instant.parse("2026-11-28T08:00:00Z").toEpochMilli()

    @Test
    fun http429KeepsTheProviderMessageAndWaitsForTheLatestExhaustedWindow() {
        val reset = now + 120_000
        val error = IllegalStateException("Provider error")
        val failure = error.withHttpFailureMetadata(
            response(
                429,
                "retry-after" to "30",
                "x-ratelimit-reset-requests" to (reset / 1_000).toString(),
                "x-ratelimit-reset-tokens" to "1h30m",
                "x-ratelimit-remaining-tokens" to "10",
            ),
        )
        val quota = failure?.botQuotaLimit(now)
        assertEquals("Provider error", failure?.message)
        assertEquals(reset, quota?.resetAt)
        assertEquals(reset + 5_000, quota?.automaticRetryAt(now))
    }

    @Test
    fun supportsHttpDateCompositeDurationAndStructuredReset() {
        val absolute = Exception("Busy").withHttpFailureMetadata(
            response(503, "Retry-After" to "Sat, 28 Nov 2026 08:02:00 GMT"),
        )
        assertEquals(now + 120_000, absolute?.botQuotaLimit(now)?.resetAt)

        val relative = Exception("Rate limit exceeded").withHttpFailureMetadata(
            response(429, "x-ratelimit-reset" to "1m2.5s"),
        )
        assertEquals(now + 62_500, relative?.botQuotaLimit(now)?.resetAt)

        val structured = HttpException("Provider rejected the request").withHttpFailureMetadata(
            response(400),
            """{"error":{"code":"insufficient_quota","resets_at":${(now + 300_000) / 1_000}}}""",
        )
        assertEquals(now + 300_000, structured?.botQuotaLimit(now)?.resetAt)
    }

    @Test
    fun retriesAutomaticallyOnlyForKnownResetsWithinTwelveHours() {
        val horizon = now + 43_200_000
        val noReset = Exception("Quota exceeded").botQuotaLimit(now)
        assertNotNull(noReset)
        assertNull(noReset?.automaticRetryAt(now))
        assertNull(BotQuotaLimit(now - 1).automaticRetryAt(now))
        assertEquals(horizon + 5_000, BotQuotaLimit(horizon).automaticRetryAt(now))
        assertNull(BotQuotaLimit(horizon + 1).automaticRetryAt(now))
    }

    @Test
    fun refusalAfterProviderOutputHasNoAutomaticRetry() {
        val failure = Exception("HTTP 429").withHttpFailureMetadata(
            response(429, "Retry-After" to "60"),
            outputStarted = true,
        )
        val quota = failure?.botQuotaLimit(now)
        assertEquals(true, quota?.outputStarted)
        assertNull(quota?.automaticRetryAt(now))
    }

    @Test
    fun ignoresAuthErrorsAndCancellation() {
        val auth = HttpException("Unauthorized", 401)
            .withHttpFailureMetadata(response(401, "x-ratelimit-reset" to "30"))
        assertNull(auth?.botQuotaLimit(now))
        val cancelled = CancellationException().apply { initCause(HttpException("HTTP 429", 429)) }
        assertNull(cancelled.botQuotaLimit(now))
    }

    private fun response(code: Int, vararg headers: Pair<String, String>): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.invalid/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Test response")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
}
