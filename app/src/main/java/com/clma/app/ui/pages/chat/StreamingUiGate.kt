package com.clma.app.ui.pages.chat

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class StreamingUiGate<T : Any>(
    private val windowMs: Long,
    private val appendOnly: (T, T) -> Boolean,
) {
    private var published: T? = null
    private var pending: T? = null
    private var publishedAt = 0L

    fun onNext(value: T, now: Long): T? {
        val previous = pending ?: published
        if (previous == value) return null
        if (previous == null || !appendOnly(previous, value) || now - publishedAt >= windowMs) {
            pending = null
            published = value
            publishedAt = now
            return value
        }
        pending = value
        return null
    }

    fun millisUntilFlush(now: Long): Long? = pending?.let {
        (windowMs - (now - publishedAt)).coerceAtLeast(0)
    }

    fun flush(now: Long): T? = pending?.also {
        published = it
        publishedAt = now
        pending = null
    }
}

internal fun <T : Any> Flow<T>.throttleStreamingUpdates(
    windowMs: Long,
    appendOnly: (T, T) -> Boolean,
): Flow<T> = flow {
    require(windowMs > 0)
    coroutineScope {
        // 不使用 CONFLATED：结构变更不能在进入门控之前被后续 token 覆盖。
        val input = Channel<T>(Channel.RENDEZVOUS)
        val upstream = launch {
            try {
                collect { input.send(it) }
            } finally {
                input.close()
            }
        }
        val gate = StreamingUiGate(windowMs, appendOnly)
        fun now() = System.nanoTime() / 1_000_000
        try {
            while (true) {
                val wait = gate.millisUntilFlush(now())
                val next = if (wait == null) input.receiveCatching()
                else withTimeoutOrNull(wait) { input.receiveCatching() }
                if (next == null) {
                    gate.flush(now())?.let { emit(it) }
                } else if (next.isClosed) {
                    gate.flush(now())?.let { emit(it) }
                    break
                } else {
                    gate.onNext(next.getOrThrow(), now())?.let { emit(it) }
                }
            }
        } finally {
            upstream.cancel()
            input.cancel()
        }
    }
}
