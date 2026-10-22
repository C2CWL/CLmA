package me.rerere.rikkahub.ui.components.ui

import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

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
): () -> Float {
    if (!rememberLoopAnimationsEnabled()) {
        return remember(staticPhase) { { staticPhase } }
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
