package me.rerere.ai.provider.providers.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shared task-status vocabulary absorbed from the retired `videogen` module: every wired
 * vendor's words land on one state, and an unseen word stays **non-terminal** so a new interim
 * state cannot break polling — the failure mode this consolidation exists to prevent.
 */
class VideoTaskStatusTest {

    @Test
    fun everyWiredVendorsSuccessWordLandsOnSucceeded() {
        // DashScope SUCCEEDED, Ark succeeded, MiniMax classic Success + H3 succeeded,
        // Zhipu SUCCESS, SiliconFlow Succeed.
        listOf("SUCCEEDED", "succeeded", "Success", "success", "SUCCESS", "Succeed", "succeed")
            .forEach { assertEquals(it, VideoTaskState.SUCCEEDED, videoTaskState(it)) }
    }

    @Test
    fun everyWiredVendorsFailureWordLandsOnFailed() {
        listOf("FAILED", "Fail", "fail", "failed", "FAIL")
            .forEach { assertEquals(it, VideoTaskState.FAILED, videoTaskState(it)) }
    }

    @Test
    fun cancellationAndExpiryAreDistinctAndTerminal() {
        listOf("CANCELED", "cancelled", "Canceled")
            .forEach { assertEquals(it, VideoTaskState.CANCELLED, videoTaskState(it)) }
        listOf("expired", "EXPIRED", "timeout")
            .forEach { assertEquals(it, VideoTaskState.EXPIRED, videoTaskState(it)) }
        assertTrue(videoTaskState("cancelled").isTerminal)
        assertTrue(videoTaskState("expired").isTerminal)
        assertTrue(videoTaskState("Fail").isTerminal)
    }

    @Test
    fun interimStatesStayRunning() {
        // MiniMax classic Preparing/Queueing/Processing, H3 queued/running, Zhipu PROCESSING,
        // SiliconFlow InQueue/InProgress.
        listOf(
            "Preparing", "Queueing", "Processing", "queued", "running", "PROCESSING",
            "InQueue", "InProgress",
        ).forEach {
            assertEquals(it, VideoTaskState.RUNNING, videoTaskState(it))
            assertFalse(it, videoTaskState(it).isTerminal)
        }
    }

    @Test
    fun anUnseenWordIsNotTerminalSoPollingContinues() {
        assertEquals(VideoTaskState.UNKNOWN, videoTaskState("melting"))
        assertFalse(videoTaskState("melting").isTerminal)
        assertEquals(VideoTaskState.UNKNOWN, videoTaskState(null))
        assertEquals(VideoTaskState.UNKNOWN, videoTaskState("   "))
    }

    @Test
    fun dashScopesLiteralUnknownIsDeliberatelyNotTerminalHere() {
        // DashScope's own loop treats the word `UNKNOWN` as a failure on purpose; that rule lives
        // there, not here, so a later migration cannot silently change it.
        assertEquals(VideoTaskState.UNKNOWN, videoTaskState("UNKNOWN"))
    }

    @Test
    fun whitespaceAndCaseAreIgnored() {
        assertEquals(VideoTaskState.SUCCEEDED, videoTaskState("  SuCcEeDeD\n"))
        assertEquals(VideoTaskState.FAILED, videoTaskState("\tFaIlEd "))
    }

    @Test
    fun failurePhraseSaysWhichThingHappenedAndQuotesTheWord() {
        assertTrue(videoTaskFailurePhrase("Cancelled").contains("cancelled"))
        assertTrue(videoTaskFailurePhrase("expired").contains("expired"))
        assertTrue(videoTaskFailurePhrase("Fail").contains("failure"))
        assertTrue(videoTaskFailurePhrase("melting").contains("melting"))
        assertTrue(videoTaskFailurePhrase(null).contains("none reported"))
    }
}
