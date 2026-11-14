package com.clma.app.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Preview
@Composable
fun DotLoading(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    animationDuration: Int = 600,
    size: Dp = 16.dp,
) {
    val phase = rememberLoopPhase(
        durationMillis = animationDuration * 2,
        staticPhase = 0.5f,
        label = "dot_loading",
        frameIntervalMs = 66L,
    )

    Box(
        modifier = Modifier
            .size(size)
            .then(modifier)
            .graphicsLayer { alpha = dotLoadingAlpha(phase()) }
            .background(color = color, shape = CircleShape)
    )
}

/** A triangular pulse keeps the back-and-forth fade without a vsync clock. */
internal fun dotLoadingAlpha(phase: Float): Float =
    0.3f + 0.7f * (1f - kotlin.math.abs(2f * phase - 1f))
