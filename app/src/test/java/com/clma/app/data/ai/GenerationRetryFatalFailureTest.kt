package com.clma.app.data.ai

import com.clma.ai.util.MediaTooLargeException
import com.clma.ai.util.RequestTooLargeException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "正在重试（第 5/5 次）… Failed to allocate" report: out of memory must never be retried. */
class GenerationRetryFatalFailureTest {
    private val oom = OutOfMemoryError(
        "Failed to allocate a 88571288 byte allocation with 25165824 free bytes and 53MB until OOM",
    )

    private val fatal = listOf(
        oom,
        // What RequestLoggingInterceptor turns an OOM on OkHttp's thread into.
        RequestLoggingInterceptor.asIOException(oom),
        IOException("canceled due to $oom", oom),
        RequestTooLargeException("pre-send size refusal"),
        MediaTooLargeException("clip.mp4", 30L * 1024 * 1024),
        StackOverflowError(),
    )

    @Test
    fun `out of memory and too large requests are not retried`() {
        fatal.forEach { failure ->
            assertFalse(
                "$failure",
                shouldRetryGenerationStreamFailure(
                    failure = failure,
                    retryAttempt = 0,
                    maxRetries = 5,
                    receivedMeaningfulOutput = false,
                ),
            )
        }
    }

    @Test
    fun `nor continued or restarted after partial output`() {
        fatal.forEach { failure ->
            assertFalse(
                "$failure",
                shouldContinueGenerationStream(
                    failure = failure,
                    receivedMeaningfulOutput = true,
                    continuationsUsed = 0,
                ),
            )
            assertFalse(
                "$failure",
                shouldRestartGenerationStreamAfterPartial(
                    failure = failure,
                    receivedMeaningfulOutput = true,
                    alreadyRestarted = false,
                ),
            )
        }
    }

    @Test
    fun `a plain transport failure is still retried`() {
        assertTrue(
            shouldRetryGenerationStreamFailure(
                failure = IOException("connection reset"),
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            ),
        )
    }

    @Test
    fun `an OOM on OkHttp's thread surfaces as a marked IOException, not a crash`() {
        val surfaced = RequestLoggingInterceptor.asIOException(oom)
        assertTrue(surfaced is RequestTooLargeException)
        assertTrue(surfaced.cause === oom)
        val plain = IOException("reset")
        assertTrue(RequestLoggingInterceptor.asIOException(plain) === plain)
    }
}
