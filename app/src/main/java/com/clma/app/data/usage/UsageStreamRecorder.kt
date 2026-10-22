package me.rerere.rikkahub.data.usage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import kotlin.coroutines.cancellation.CancellationException

/**
 * P2-12a — the streaming half of the collection point.
 *
 * [UsageRecordingProvider] wraps `generateText` directly, because its result carries the
 * provider-reported usage. A streaming call reports usage as a `StreamChunk.Usage` in the
 * middle of the flow instead, and until P2-12a the decorator left `streamText` untouched
 * (interface delegation forwarded it verbatim) — so every streamed call, which is the default
 * chat path (`Assistant.streamOutput = true`), never reached the ledger at all.
 *
 * This wraps the flow so a streamed call produces the same one-row-per-round-trip accounting
 * without changing how the caller collects it: every element is passed through untouched, the
 * selected usage figures are merged with the same [TokenUsage.merge] rule that
 * `StreamChunkHandler` uses when it folds them into the message, and the row is written once,
 * after the upstream completes normally.
 *
 * Invariants (each is covered by a test):
 *  - the element stream is identical to the upstream: no buffering, no reordering;
 *  - nothing is written when no element ever reported usage ("unknown != 0");
 *  - nothing is written when the upstream fails or is cancelled — a partial figure would be a
 *    guess, and telemetry must never turn a torn-down turn into a recorded one;
 *  - re-collecting the returned flow (a retry chain re-subscribing) never writes a second row;
 *  - a throw from [record] is contained, and a cancellation is rethrown unchanged.
 */
internal fun <T> Flow<T>.recordUsageOnce(
    select: (T) -> TokenUsage?,
    record: suspend (TokenUsage) -> Unit,
): Flow<T> {
    val upstream = this
    // Shared by every collection of the returned flow, so a retry that re-subscribes cannot
    // double-count one logical call.
    var recorded = false
    return flow {
        var accumulated: TokenUsage? = null
        upstream.collect { element ->
            select(element)?.let { accumulated = accumulated?.merge(it) ?: it }
            emit(element)
        }
        val total = accumulated
        if (total != null && !recorded) {
            recorded = true
            try {
                record(total)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The stream already delivered its elements; telemetry must not fail the turn.
            }
        }
    }
}
