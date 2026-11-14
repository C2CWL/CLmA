package androidx.compose.runtime

import kotlin.coroutines.Continuation

/** Test-only target: proves source-hook operands are preserved by actually loading transformed code. */
@Suppress("UNUSED_PARAMETER")
object MonotonicFrameClockKt {
    @JvmStatic fun withFrameNanos(callback: (Long) -> Any, continuation: Continuation<*>?): Any = callback
}
