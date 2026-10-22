package me.rerere.rikkahub.data.ai

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Thresholds for [StreamIdleNotifier]. */
internal object StreamIdleThresholds {
    /**
     * No *meaningful* output yet, and this long has passed. A long conversation means a long
     * server-side prefill, so this is a notice ("still working"), never a verdict.
     */
    const val FIRST_OUTPUT_NOTICE_MS = 60_000L

    /**
     * Output had already started and then went quiet for this long. A stream that has begun
     * emitting and stops is the shape of a real stall.
     */
    const val STALL_NOTICE_MS = 90_000L

    /** How often the notifier is polled. */
    const val POLL_MS = 5_000L
}

/**
 * What a [StreamIdleNotifier.tick] wants done with the processing-status line.
 */
internal sealed interface StreamIdleNotice {
    /** Output resumed — drop the notice this notifier previously raised. */
    data object Clear : StreamIdleNotice

    /** Nothing meaningful has arrived yet: show "waiting for the model". */
    data class Waiting(val idleMs: Long) : StreamIdleNotice

    /** Output had started and then went quiet: show "looks stalled, retry". */
    data class Stalled(val idleMs: Long) : StreamIdleNotice
}

/**
 * Watches a streamed model reply for silence and decides when the UI should say something.
 *
 * Why this exists: a concurrency-strapped client is indistinguishable from a dead one. When the
 * provider queues a request, or the reply is slow to start on a long context, the chat bubble just
 * keeps counting seconds with no way to tell "working" from "wedged" — which is exactly the
 * "thinking is stuck" report.
 *
 * Two regimes, because the first token and the steady state mean different things:
 *
 *  - **Before any meaningful output** the silence may be a perfectly healthy prefill of a large
 *    conversation, so the bar is [firstOutputNoticeMs] and the message only says "still working".
 *  - **After output has started** a long silence is the actual stall shape, so the bar is
 *    [stallNoticeMs] and the message tells the user they can stop and retry.
 *
 * Deliberately notice-only: it never cancels the request. By the time output has begun the stream
 * retry policy refuses to re-run the turn (re-running would duplicate visible text), so cancelling
 * here would only turn a slow-but-alive reply into a failure. Telling the user is the improvement;
 * letting them decide is the safety.
 *
 * Not thread-safe by design; [tick] must be driven from a single coroutine. [onChunk] is called
 * from the collector and touches only atomics.
 */
internal class StreamIdleNotifier(
    private val firstOutputNoticeMs: Long = StreamIdleThresholds.FIRST_OUTPUT_NOTICE_MS,
    private val stallNoticeMs: Long = StreamIdleThresholds.STALL_NOTICE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastActivityAt = AtomicLong(clock())
    private val producedOutput = AtomicBoolean(false)
    private var noticeUp = false

    /** Every chunk the provider emits, meaningful or not. */
    fun onChunk(meaningful: Boolean) {
        lastActivityAt.set(clock())
        if (meaningful) producedOutput.set(true)
    }

    /**
     * A fresh transport attempt is starting (the retry policy re-subscribed): the clock and the
     * "has produced output" flag both describe one attempt, so reset them together.
     */
    fun onAttemptStart() {
        lastActivityAt.set(clock())
        producedOutput.set(false)
    }

    /**
     * Poll the stream's silence. Returns a notice to surface, [StreamIdleNotice.Clear] when a
     * previously raised notice should be withdrawn, or null when nothing needs to change.
     */
    fun tick(): StreamIdleNotice? {
        val idleMs = (clock() - lastActivityAt.get()).coerceAtLeast(0L)
        val threshold = if (producedOutput.get()) stallNoticeMs else firstOutputNoticeMs
        if (idleMs < threshold) {
            if (!noticeUp) return null
            noticeUp = false
            return StreamIdleNotice.Clear
        }
        noticeUp = true
        return if (producedOutput.get()) {
            StreamIdleNotice.Stalled(idleMs)
        } else {
            StreamIdleNotice.Waiting(idleMs)
        }
    }
}
