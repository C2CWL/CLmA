package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** How often the watchdog re-checks the silence budget. Bounds the overshoot, nothing else. */
private const val SILENCE_WATCHDOG_POLL_MS = 200L

/**
 * Aborts [this] when it goes quiet for too long, in two phases:
 *
 *  - **Before the first element**: [firstElementTimeoutMs] without a single element means the
 *    socket never answered (a silently-dead connection, a provider holding the request open with
 *    zero bytes). This is the "thinking bubble with no content" report.
 *  - **Between elements**: [interChunkTimeoutMs] with no element after the stream has started means
 *    it *stalled* mid-reply. Reasoning deltas count as elements, so a model that stops producing
 *    anything - even after it began writing reasoning - is caught here.
 *
 * Why both: a streamed reply rides one long-lived socket. When that socket dies silently - a
 * backgrounded app changing networks, an OEM freezing the process, a provider dropping the
 * connection without a FIN, or a gateway holding a request open with zero bytes - nothing is
 * emitted and nothing fails. The chat just counts seconds on an empty "thinking" bubble, and the
 * shared client's 10-minute `readTimeout` (which only bounds the gap between *bytes*) is the only
 * thing that would ever notice, while `pingInterval` only helps on HTTP/2. Users report exactly
 * this: "thinking" with no content for minutes, while re-sending the message immediately streams
 * fine. Both phases turn that silence into an ordinary stream failure, so the caller's existing
 * recovery re-issues the request on a fresh connection - the retry policy while nothing meaningful
 * has arrived, the continue / restart path once it has.
 *
 * Phase one deliberately disarms on the FIRST element of any kind, not the first *meaningful* one:
 * providers can open the stream with bookkeeping elements (usage, an empty or role-only delta)
 * before the model has produced any content, and a genuine long prefill must never be aborted.
 * Phase two is what covers the rest of the reply, and it is a *per-gap* budget, never a cap on the
 * total reply time: a slow-but-alive stream that keeps emitting resets it on every element and is
 * never touched.
 *
 * Both phases are disabled by a non-positive timeout (`<= 0`), and the operator returns [this]
 * unchanged when both are off, so a caller can turn either one off without branching. Elements
 * themselves are never delayed: the watchdog only decides whether to close the producer channel,
 * which surfaces the failure to the collector as a thrown exception.
 *
 * @param noFirstElement builds the failure thrown into the stream when phase one expires; called on
 *   the watchdog coroutine, so it must not capture anything mutable from the collecting coroutine.
 * @param stalled builds the failure for phase two, with the same restriction.
 */
internal fun <T> Flow<T>.abortIfSilent(
    firstElementTimeoutMs: Long,
    interChunkTimeoutMs: Long,
    noFirstElement: () -> Throwable,
    stalled: () -> Throwable,
): Flow<T> {
    if (firstElementTimeoutMs <= 0L && interChunkTimeoutMs <= 0L) return this
    return channelFlow {
        val producer = this
        val seenFirstElement = AtomicBoolean(false)
        val lastElementAt = AtomicLong(System.nanoTime())
        val watchdog = launch {
            while (true) {
                val firstPhase = !seenFirstElement.get()
                val budgetMs = if (firstPhase) firstElementTimeoutMs else interChunkTimeoutMs
                if (budgetMs <= 0L) {
                    // Phase one off: keep waiting for the stream to start so phase two can take
                    // over. Phase two off: nothing left to watch.
                    if (!firstPhase) return@launch
                    delay(SILENCE_WATCHDOG_POLL_MS)
                    continue
                }
                val idleMs = (System.nanoTime() - lastElementAt.get()) / 1_000_000L
                if (idleMs >= budgetMs) {
                    // Closing the producer channel with a cause surfaces it to the collector as a
                    // thrown exception, which is what the retry / continue logic downstream
                    // expects. Closing also lets the flow complete and take the still-suspended
                    // upstream collection - and with it the HTTP call - with it.
                    producer.close(if (firstPhase) noFirstElement() else stalled())
                    return@launch
                }
                delay(minOf(SILENCE_WATCHDOG_POLL_MS, budgetMs - idleMs))
            }
        }
        try {
            collect { value ->
                seenFirstElement.set(true)
                lastElementAt.set(System.nanoTime())
                send(value)
            }
        } finally {
            watchdog.cancel()
        }
    }
}

/**
 * Aborts [this] with [timeoutFailure] if it does not emit its FIRST element within [timeoutMs].
 *
 * The single-phase form of [abortIfSilent] (`interChunkTimeoutMs = 0`): it guards only the moment
 * before the stream says anything at all, and leaves a stream that dies *after* its first element
 * to the transport (`pingInterval` / `readTimeout`) and the post-output continue logic.
 *
 * @param timeoutMs how long to wait for the first element; **`<= 0` disables the watchdog**
 *   and returns [this] unchanged, so a caller can turn it off without branching.
 */
internal fun <T> Flow<T>.abortIfSilentBeforeFirstElement(
    timeoutMs: Long,
    timeoutFailure: () -> Throwable,
): Flow<T> = abortIfSilent(
    firstElementTimeoutMs = timeoutMs,
    interChunkTimeoutMs = 0L,
    noFirstElement = timeoutFailure,
    stalled = timeoutFailure,
)
