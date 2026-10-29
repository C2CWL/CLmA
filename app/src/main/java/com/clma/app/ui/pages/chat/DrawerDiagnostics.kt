package com.clma.app.ui.pages.chat

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.ViewTreeObserver
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clma.app.diagnostics.FunctionalDiagnostics
import com.clma.app.ui.hooks.rememberSharedPreferenceBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

/** Frames still sampled after the drawer settles, to catch a one-frame vanish right at settle. */
private const val SETTLED_TAIL_FRAMES = 10

/** Safety cap per episode (a long finger drag); the episode is cut and flushed here. */
private const val MAX_EPISODE_FRAMES = 600

/** Upper bound on FrameMetrics rows kept per episode. */
private const val MAX_METRIC_ROWS = 800

/**
 * Process-wide state of the cold-start drawer flicker probe (diagnostic build only).
 *
 * Everything here is written only while `drawer_animation_diagnostics` is on, except
 * [chatPages] (one increment per ChatPage, needed to label "first page since cold start").
 */
internal object DrawerFlickerProbe {
    /**
     * Index of the frame being sampled. Written once per frame, and only while an episode
     * runs. [drawerFlickerPlacementMarker] reads it during placement, so the sheet re-places
     * (and records the index) in every sampled frame in which the drawer actually places it.
     */
    val frame = mutableLongStateOf(0L)

    /** [frame] value seen by the last placement of the sheet, -1 = never placed. Main thread. */
    @Volatile var placedFrame = -1L

    @Volatile var placedCount = 0L

    /** Sheet width as measured by the marker; 0 until the sheet was placed once. */
    @Volatile var sheetWidthPx = 0

    @Volatile var stoppedSinceLaunch = false

    val chatPages = AtomicInteger()
    val globalLayouts = AtomicLong()
    val viewDraws = AtomicLong()

    /** FrameMetrics rows are only collected while an episode (plus a short grace) runs. */
    @Volatile var metricsActive = false
    val metrics = ConcurrentLinkedQueue<String>()

    private var metricsThread: HandlerThread? = null

    @Synchronized
    fun metricsHandler(): Handler {
        val thread = metricsThread ?: HandlerThread("DrawerFlickerMetrics").also {
            it.start()
            metricsThread = it
        }
        return Handler(thread.looper)
    }
}

/**
 * Records, on the ModalDrawerSheet, which sampled frame last placed the sheet. Apply it only
 * while the diagnostics switch is on. A sampled frame whose layout did not place the sheet
 * (lag > 1 right at settle) means Material3 decided not to place it (state path); a frame that
 * placed it but still flashed on screen points at rendering (render path).
 */
internal fun Modifier.drawerFlickerPlacementMarker(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            // State read in placement: re-runs this block each time the probe advances the
            // frame, but only if the parent places the sheet in that pass.
            DrawerFlickerProbe.placedFrame = DrawerFlickerProbe.frame.longValue
            DrawerFlickerProbe.placedCount++
            DrawerFlickerProbe.sheetWidthPx = placeable.width
            placeable.place(0, 0)
        }
    }

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ObserveDrawerDiagnostics(
    drawer: DrawerState,
    vm: ChatVM,
    assistantId: Uuid,
) {
    val functions by rememberSharedPreferenceBoolean(FunctionalDiagnostics.FUNCTION_KEY)
    val animation by rememberSharedPreferenceBoolean(FunctionalDiagnostics.ANIMATION_KEY)
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalContext.current as? ComponentActivity
    val instance = remember(drawer) { System.identityHashCode(drawer) }
    // 1-based ChatPage number since process start; 1 = the page created at cold start.
    val page = remember(drawer) { DrawerFlickerProbe.chatPages.incrementAndGet() }
    // ChatDrawer.kt sizes the sheet with Modifier.width(300.dp); used until the marker measured it.
    val estimatedSheetWidthPx = with(LocalDensity.current) { 300.dp.roundToPx() }
    DisposableEffect(drawer) {
        FunctionalDiagnostics.event("chat.attach", instance)
        FunctionalDiagnostics.animation(instance, "attached vm=${System.identityHashCode(vm)}")
        if (FunctionalDiagnostics.animationEnabled) {
            FunctionalDiagnostics.flicker("page.attach instance=$instance page=$page ${runLabel(page)}")
        }
        onDispose {
            FunctionalDiagnostics.event("chat.detach", instance)
            FunctionalDiagnostics.animation(instance, "detached")
            if (FunctionalDiagnostics.animationEnabled) {
                FunctionalDiagnostics.flicker("page.detach instance=$instance page=$page")
            }
        }
    }
    DisposableEffect(lifecycleOwner, functions) {
        val observer =
            LifecycleEventObserver { _, event ->
                FunctionalDiagnostics.event("chat.lifecycle.${event.name}", instance)
            }
        if (functions) lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(assistantId, functions) {
        FunctionalDiagnostics.event("chat.assistantObserved", instance)
    }
    LaunchedEffect(vm, functions) {
        if (functions) {
            vm.conversationJob.collect { job ->
                FunctionalDiagnostics.event("chat.busy", instance, if (job == null) 0 else 1)
            }
        }
    }
    // Window-level counters: activity stop flag, global layouts, view draws, FrameMetrics.
    DisposableEffect(activity, animation) {
        if (!animation || activity == null) return@DisposableEffect onDispose {}
        val stopObserver =
            LifecycleEventObserver { _, event ->
                // Adding the observer replays ON_CREATE/ON_START/ON_RESUME; only log a start
                // that follows a real stop (background -> foreground).
                if (event == Lifecycle.Event.ON_STOP) {
                    DrawerFlickerProbe.stoppedSinceLaunch = true
                    FunctionalDiagnostics.flicker("activity.ON_STOP page=$page")
                } else if (event == Lifecycle.Event.ON_START && DrawerFlickerProbe.stoppedSinceLaunch) {
                    FunctionalDiagnostics.flicker("activity.ON_START page=$page")
                }
            }
        activity.lifecycle.addObserver(stopObserver)
        val window = activity.window
        val decorView = window.decorView
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            DrawerFlickerProbe.globalLayouts.incrementAndGet()
        }
        val drawListener = ViewTreeObserver.OnDrawListener {
            DrawerFlickerProbe.viewDraws.incrementAndGet()
        }
        decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        decorView.viewTreeObserver.addOnDrawListener(drawListener)
        val metricsListener =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFrameMetricsListener(window)
            } else {
                null
            }
        onDispose {
            activity.lifecycle.removeObserver(stopObserver)
            val observer = decorView.viewTreeObserver
            if (observer.isAlive) {
                observer.removeOnGlobalLayoutListener(layoutListener)
                observer.removeOnDrawListener(drawListener)
            }
            if (metricsListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { window.removeOnFrameMetricsAvailableListener(metricsListener) }
            }
        }
    }
    // Per-frame episodes: from the first frame the drawer moves until SETTLED_TAIL_FRAMES
    // frames after it settles. Idle otherwise (suspended on a snapshotFlow, no frame callbacks).
    LaunchedEffect(drawer, animation) {
        if (!animation) return@LaunchedEffect
        var episode = 0
        while (true) {
            snapshotFlow { drawer.isMoving(sheetWidth(estimatedSheetWidthPx)) }.first { it }
            episode++
            recordEpisode(drawer, instance, page, episode, estimatedSheetWidthPx)
        }
    }
}

private fun sheetWidth(estimated: Int): Int = DrawerFlickerProbe.sheetWidthPx.takeIf { it > 0 } ?: estimated

private fun DrawerState.isMoving(widthPx: Int): Boolean {
    if (isAnimationRunning || currentValue != targetValue) return true
    val offset = currentOffset
    if (offset.isNaN() || widthPx <= 0) return false
    return when (currentValue) {
        DrawerValue.Open -> offset < -0.5f
        DrawerValue.Closed -> offset > -widthPx + 0.5f
    }
}

private fun runLabel(page: Int): String {
    val uptime =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        } else {
            -1L
        }
    return "firstPageSinceColdStart=${page == 1} stoppedSinceLaunch=${DrawerFlickerProbe.stoppedSinceLaunch} " +
        "processUptimeMs=$uptime sdk=${Build.VERSION.SDK_INT}"
}

private suspend fun recordEpisode(
    drawer: DrawerState,
    instance: Int,
    page: Int,
    episode: Int,
    estimatedWidthPx: Int,
) {
    val out = StringBuilder(8 * 1024)
    out.append("episode=").append(episode).append(" instance=").append(instance)
        .append(" page=").append(page).append(' ').append(runLabel(page))
        .append(" start=").append(drawer.currentValue).append("->").append(drawer.targetValue)
        .append(" widthPx=").append(sheetWidth(estimatedWidthPx))
        .append(" estWidthPx=").append(estimatedWidthPx)
        .append('\n')
    out.append("cols: f frameUs dtMs offset cur tgt run frac placedF lag placedN gl draw\n")
    DrawerFlickerProbe.metrics.clear()
    DrawerFlickerProbe.metricsActive = true
    try {
        var frames = 0
        var settled = 0
        var previousNanos = 0L
        var previousPlaced = DrawerFlickerProbe.placedCount
        var previousLayouts = DrawerFlickerProbe.globalLayouts.get()
        var previousDraws = DrawerFlickerProbe.viewDraws.get()
        while (settled < SETTLED_TAIL_FRAMES && frames < MAX_EPISODE_FRAMES) {
            withFrameNanos { nanos ->
                // Everything below reflects the previous frame's layout/draw (this callback
                // runs before this frame's recomposition and layout).
                val index = DrawerFlickerProbe.frame.longValue + 1
                DrawerFlickerProbe.frame.longValue = index
                val offset = drawer.currentOffset
                val width = sheetWidth(estimatedWidthPx)
                val fraction = if (offset.isNaN() || width <= 0) Float.NaN else (offset + width) / width
                val placedFrame = DrawerFlickerProbe.placedFrame
                val placed = DrawerFlickerProbe.placedCount
                val layouts = DrawerFlickerProbe.globalLayouts.get()
                val draws = DrawerFlickerProbe.viewDraws.get()
                val running = drawer.isAnimationRunning
                out.append("f=").append(index)
                    .append(' ').append(nanos / 1_000)
                    .append(' ').append(if (previousNanos == 0L) "-" else fmt((nanos - previousNanos) / 1_000_000f))
                    .append(' ').append(fmt(offset))
                    .append(' ').append(drawer.currentValue)
                    .append(' ').append(drawer.targetValue)
                    .append(' ').append(if (running) 1 else 0)
                    .append(' ').append(fmt(fraction))
                    .append(' ').append(placedFrame)
                    .append(' ').append(if (placedFrame < 0) "-" else (index - placedFrame).toString())
                    .append(" +").append(placed - previousPlaced)
                    .append(" +").append(layouts - previousLayouts)
                    .append(" +").append(draws - previousDraws)
                    .append('\n')
                previousNanos = nanos
                previousPlaced = placed
                previousLayouts = layouts
                previousDraws = draws
                frames++
                val atRest = !running && drawer.currentValue == drawer.targetValue && !drawer.isMoving(width)
                settled = if (atRest) settled + 1 else 0
            }
        }
        if (frames >= MAX_EPISODE_FRAMES) out.append("truncated at ").append(MAX_EPISODE_FRAMES).append(" frames\n")
        out.append("end=").append(drawer.currentValue).append(" frames=").append(frames).append('\n')
        // FrameMetrics arrive a few ms after their frame; give the last ones time to land.
        delay(150)
    } finally {
        DrawerFlickerProbe.metricsActive = false
        out.append("fm cols: vsyncUs totalMs first layoutMs drawMs syncMs cmdMs swapMs gpuMs\n")
        while (true) {
            val row = DrawerFlickerProbe.metrics.poll() ?: break
            out.append(row).append('\n')
        }
        FunctionalDiagnostics.flicker(out.toString().trimEnd())
    }
}

private fun fmt(value: Float): String =
    if (value.isNaN()) "NaN" else String.format(Locale.US, "%.2f", value)

private fun Long.nsToMs(): String = String.format(Locale.US, "%.2f", this / 1_000_000f)

@RequiresApi(Build.VERSION_CODES.N)
private fun addFrameMetricsListener(window: Window): Window.OnFrameMetricsAvailableListener {
    val listener =
        Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (!DrawerFlickerProbe.metricsActive || DrawerFlickerProbe.metrics.size >= MAX_METRIC_ROWS) {
                return@OnFrameMetricsAvailableListener
            }
            val vsyncUs =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    (metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP) / 1_000).toString()
                } else {
                    "-"
                }
            val gpu =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    metrics.getMetric(FrameMetrics.GPU_DURATION).nsToMs()
                } else {
                    "-"
                }
            DrawerFlickerProbe.metrics.add(
                "fm " + vsyncUs +
                    " " + metrics.getMetric(FrameMetrics.TOTAL_DURATION).nsToMs() +
                    " " + metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) +
                    " " + metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION).nsToMs() +
                    " " + metrics.getMetric(FrameMetrics.DRAW_DURATION).nsToMs() +
                    " " + metrics.getMetric(FrameMetrics.SYNC_DURATION).nsToMs() +
                    " " + metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION).nsToMs() +
                    " " + metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION).nsToMs() +
                    " " + gpu,
            )
        }
    window.addOnFrameMetricsAvailableListener(listener, DrawerFlickerProbe.metricsHandler())
    return listener
}
