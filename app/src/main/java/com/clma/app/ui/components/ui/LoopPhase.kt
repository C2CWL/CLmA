package com.clma.app.ui.components.ui

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Whether looping decorative animations should run for the system animator duration scale
 * ("Remove animations" / "Animator duration scale: off" set it to 0). Unreadable values count as
 * enabled, so the indicator never goes static by accident.
 */
internal fun loopAnimationsEnabled(animatorDurationScale: Float): Boolean =
    animatorDurationScale.isNaN() || animatorDurationScale > 0f

/** Reads the system animator duration scale once per entry into composition. */
@Composable
internal fun rememberLoopAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        loopAnimationsEnabled(scale)
    }
}

/** Work indicators have no active transition while their page is paused or disposed. */
@Composable
internal fun rememberWorkAnimationsEnabled(): Boolean {
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return rememberLoopAnimationsEnabled() && resumed
}

/**
 * Phase at [elapsedMs] into a loop of [durationMs], as a pure function so the coarse cadence
 * below is testable without a clock: negative elapsed wraps like a modular clock.
 */
internal fun loopPhaseAt(elapsedMs: Long, durationMs: Int, staticPhase: Float): Float {
    if (durationMs <= 0) return staticPhase
    val wrapped = ((elapsedMs % durationMs) + durationMs) % durationMs
    return wrapped.toFloat() / durationMs
}

/**
 * A 0..1 phase repeating every [durationMillis], returned as an accessor so callers read it only
 * inside draw lambdas: the animation then re-draws without recomposing or re-laying out.
 *
 * With animations turned off system-wide it returns the constant [staticPhase] and starts no
 * transition at all, so the indicator is a still frame that costs nothing per frame. This does not
 * rely on how a given Compose version treats infinite transitions under a zero duration scale.
 */
@Composable
internal fun rememberLoopPhase(
    durationMillis: Int,
    staticPhase: Float,
    label: String,
    easing: Easing = LinearEasing,
    frameIntervalMs: Long = 0L,
): () -> Float {
    if (!rememberWorkAnimationsEnabled()) {
        return remember(staticPhase) { { staticPhase } }
    }
    // Explicit coarse cadence remains available to opt-in callers. Reply/thinking effects use
    // the default display-frame transition; do not globally retime unrelated animation clocks.
    if (frameIntervalMs > 0L) {
        val phase = produceState(staticPhase, durationMillis, frameIntervalMs) {
            val start = SystemClock.uptimeMillis()
            while (true) {
                delay(frameIntervalMs)
                value = loopPhaseAt(SystemClock.uptimeMillis() - start, durationMillis, staticPhase)
            }
        }
        return remember(phase) { { phase.value } }
    }
    val state = rememberInfiniteTransition(label = label).animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = easing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "$label-phase",
    )
    return remember(state) { { state.value } }
}
