package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * iOS-style bounce overscroll modifier for LazyColumn.
 *
 * Disables the default Android stretch effect at the call site with
 * `CompositionLocalProvider(LocalOverscrollFactory provides null)`, then applies
 * this modifier to the LazyColumn.
 *
 * How it works:
 * - onPostScroll: leftover delta at list boundary → accumulate overscroll offset
 *   with progressive rubber-band resistance.  Only triggers when the list is
 *   actually at a boundary (canScrollBackward/canScrollForward == false), so
 *   spurious leftover from lazy composition does not cause false bounces.
 * - onPreScroll: when overscroll is active, consume drag to extend (same direction,
 *   with resistance) or reduce (opposite direction, releasing back to list at 0).
 * - onPreFling / onPostFling: animate offset back to 0 with a spring.
 * - graphicsLayer { translationY = offset } renders the bounce.
 *
 * Drag offset is tracked via a synchronous mutableFloatStateOf (not Animatable)
 * so that onPreScroll/onPostScroll see the exact current value without a
 * one-frame coroutine dispatch delay.
 */
@Composable
fun Modifier.bounceOverscroll(
    state: LazyListState,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this

    // Synchronous source of truth for the drag offset — read/written directly
    // in scroll callbacks without coroutine dispatch delay.
    var overscroll by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    // Animatable is only used as the spring-back animation engine.
    val animatable = remember { Animatable(0f) }
    var containerHeight by remember { mutableFloatStateOf(1f) }

    val connection = remember {
        object : NestedScrollConnection {
            private var animJob: Job? = null

            private fun maxOverscroll() = containerHeight * 0.25f

            /** Progressive resistance: the further we are, the harder to pull. */
            private fun resistance(current: Float): Float =
                1f - (abs(current) / maxOverscroll()).coerceIn(0f, 0.75f)

            /** Synchronously update the offset and cancel any running spring-back. */
            private fun snapOffset(value: Float) {
                animJob?.cancel()
                overscroll = value.coerceIn(-maxOverscroll(), maxOverscroll())
            }

            /** Launch a spring-back animation from the current offset to 0. */
            private fun animateBackToZero() {
                animJob?.cancel()
                val start = overscroll
                if (start == 0f) return
                animJob = scope.launch {
                    animatable.snapTo(start)
                    animatable.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ) {
                        overscroll = value
                    }
                }
            }

            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val current = overscroll
                if (current == 0f) return Offset.Zero

                val delta = available.y
                if (delta == 0f) return Offset.Zero

                return if (current * delta > 0) {
                    // Same direction → extend overscroll with resistance
                    val r = resistance(current)
                    snapOffset(current + delta * r)
                    available.copy(x = 0f)
                } else {
                    // Opposite direction → reduce overscroll, release remainder to list
                    val consumed = if (abs(delta) >= abs(current)) -current else delta
                    val newOffset = current + consumed
                    snapOffset(if (abs(newOffset) < 0.5f) 0f else newOffset)
                    Offset(0f, consumed)
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y == 0f) return Offset.Zero

                // Guard: only trigger overscroll when the list is actually at a
                // boundary.  LazyColumn may report non-zero leftover during lazy
                // composition even when content exists above/below — that would
                // cause a false bounce without this check.
                if (available.y > 0 && state.canScrollBackward) return Offset.Zero
                if (available.y < 0 && state.canScrollForward) return Offset.Zero

                val current = overscroll
                val r = resistance(current)
                snapOffset(current + available.y * r)
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll != 0f) animateBackToZero()
                return Velocity.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                if (overscroll != 0f) animateBackToZero()
                return Velocity.Zero
            }
        }
    }

    return this
        .onSizeChanged { containerHeight = it.height.toFloat().coerceAtLeast(1f) }
        .nestedScroll(connection)
        .graphicsLayer { translationY = overscroll }
}
