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
 *   with progressive rubber-band resistance.
 * - onPreScroll: when overscroll is active, consume drag to extend (same direction,
 *   with resistance) or reduce (opposite direction, releasing back to list at 0).
 * - onPreFling / onPostFling: animate offset back to 0 with a spring.
 * - graphicsLayer { translationY = offset } renders the bounce.
 */
@Composable
fun Modifier.bounceOverscroll(
    state: LazyListState,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this

    val overscroll = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var containerHeight by remember { mutableFloatStateOf(1f) }

    val connection = remember {
        object : NestedScrollConnection {
            private fun maxOverscroll() = containerHeight * 0.25f

            /** Progressive resistance: the further we are, the harder to pull. */
            private fun resistance(current: Float): Float =
                1f - (abs(current) / maxOverscroll()).coerceIn(0f, 0.75f)

            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val current = overscroll.value
                if (current == 0f) return Offset.Zero

                val delta = available.y
                if (delta == 0f) return Offset.Zero

                return if (current * delta > 0) {
                    // Same direction → extend overscroll with resistance
                    scope.launch {
                        val r = resistance(current)
                        val new = (current + delta * r).coerceIn(-maxOverscroll(), maxOverscroll())
                        overscroll.snapTo(new)
                    }
                    available.copy(x = 0f)
                } else {
                    // Opposite direction → reduce overscroll, release remainder to list
                    val consumed = if (abs(delta) >= abs(current)) -current else delta
                    scope.launch {
                        val newOffset = current + consumed
                        overscroll.snapTo(if (abs(newOffset) < 0.5f) 0f else newOffset)
                    }
                    Offset(0f, consumed)
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y == 0f) return Offset.Zero
                scope.launch {
                    val current = overscroll.value
                    val r = resistance(current)
                    val new = (current + available.y * r).coerceIn(-maxOverscroll(), maxOverscroll())
                    overscroll.snapTo(new)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll.value != 0f) {
                    scope.launch {
                        overscroll.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                if (overscroll.value != 0f) {
                    scope.launch {
                        overscroll.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }
                return Velocity.Zero
            }
        }
    }

    return this
        .onSizeChanged { containerHeight = it.height.toFloat().coerceAtLeast(1f) }
        .nestedScroll(connection)
        .graphicsLayer { translationY = overscroll.value }
}
