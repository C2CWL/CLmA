package com.clma.app.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.clma.app.BuildConfig
import kotlinx.coroutines.delay

/** Bytecode calls only these no-throw bridges; never change the original continuation or callback. */
object FrameWaiterProbe {
    private fun nowMs(): Long = System.nanoTime() / 1_000_000
    private val control = FrameWaiterControl(::nowMs) { Thread.currentThread().stackTrace }

    @JvmStatic fun onRequestSite(callback: Any?, site: String, category: String) {
        val capture = control.active ?: return
        if (!capture.accepting) return
        try { capture.site(callback, site, category, nowMs()) }
        catch (_: Throwable) { capture.fail(nowMs()) }
    }
    @JvmStatic fun onAwait(callback: Any?) {
        val capture = control.active ?: return
        if (!capture.accepting) return
        try { capture.await(callback, nowMs()) }
        catch (_: Throwable) { capture.fail(nowMs()) }
    }
    internal fun configure(enabled: Boolean) {
        control.configure(enabled && BuildConfig.FRAME_WAITER_PROBE_ENABLED)?.let(::report)
    }
    internal fun claim(token: Any): Boolean = control.claim(token)
    internal fun begin(token: Any): Boolean = control.begin(token)
    internal fun stop(token: Any, reason: String) { control.stop(token, reason)?.let(::report) }
    internal fun event(message: String) { FunctionalDiagnostics.frameWaiterReport(message) }
    private fun report(lines: List<String>) { lines.forEach(FunctionalDiagnostics::frameWaiterReport) }
}

/** No settings-page timer. Start once, 3s after the first chat RESUMED; never auto-repeat. */
@Composable
internal fun ObserveFrameWaiterDiagnostics(enabled: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, enabled) {
        if (!enabled || !BuildConfig.FRAME_WAITER_PROBE_ENABLED) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val token = Any()
            if (!FrameWaiterProbe.claim(token)) return@repeatOnLifecycle
            FrameWaiterProbe.event("status=armed delay_ms=3000 duration_ms=10000 hook=broadcast_entry_v1")
            try {
                delay(3_000)
                if (FrameWaiterProbe.begin(token)) {
                    FrameWaiterProbe.event("status=started duration_ms=10000 hook=broadcast_entry_v1")
                    delay(FrameWaiterCapture.WINDOW_MS)
                    FrameWaiterProbe.stop(token, "expired")
                }
            } finally {
                FrameWaiterProbe.stop(token, "chat_not_resumed_or_disposed")
            }
        }
    }
}
