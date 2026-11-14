package com.clma.app.diagnostics

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.clma.app.ui.pages.chat.DrawerUpdateDiagnostics

/** Observes completed frames; never schedules a frame or writes a per-frame log. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ObserveRenderDiagnostics(
    window: Window,
    enabled: Boolean,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(window, lifecycleOwner, enabled) {
        if (!enabled) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var frames = 0L
            var slowFrames = 0L
            var maxFrameNs = 0L
            var droppedReports = 0L
            val refreshRate = window.decorView.display?.refreshRate ?: 60f
            val budgetNs = (1_000_000_000.0 / refreshRate.coerceAtLeast(1f)).toLong()
            val listener =
                Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                    val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    frames++
                    if (duration > budgetNs) slowFrames++
                    maxFrameNs = maxOf(maxFrameNs, duration)
                    droppedReports += dropped
                }
            // Callback and sampler share the main thread, so counters need no locks.
            window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
            try {
                var previousTime = SystemClock.elapsedRealtime()
                var previousCpu = Process.getElapsedCpuTime()
                var previousMainCpu = Debug.threadCpuTimeNanos()
                while (true) {
                    delay(10_000)
                    val now = SystemClock.elapsedRealtime()
                    val cpu = Process.getElapsedCpuTime()
                    val mainCpu = Debug.threadCpuTimeNanos()
                    val elapsed = now - previousTime
                    FunctionalDiagnostics.renderSample(
                        "window_ms=$elapsed cpu_ms=${cpu - previousCpu} " +
                            "main_cpu_ms=${(mainCpu - previousMainCpu) / 1_000_000} " +
                            "frames=$frames slow_frames=$slowFrames max_frame_ms=${maxFrameNs / 1_000_000} " +
                            "dropped_reports=$droppedReports refresh_hz=$refreshRate " +
                            DrawerUpdateDiagnostics.takeWindowSummary(),
                    )
                    previousTime = now
                    previousCpu = cpu
                    previousMainCpu = mainCpu
                    frames = 0
                    slowFrames = 0
                    maxFrameNs = 0
                    droppedReports = 0
                }
            } finally {
                window.removeOnFrameMetricsAvailableListener(listener)
            }
        }
    }
}
