package com.clma.app.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clma.app.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * "Warm sun" loading indicator (settings label 暖阳), inspired by the quiet Claude.ai thinking
 * row: a small warm star that slowly rotates and breathes, next to a grey "Thinking…" label
 * with a soft white light sweeping across the glyphs.
 *
 * Deliberately not a clone of Claude's trademark asterisk: six soft lobes instead of four,
 * and the star colour is Pure's warm coral accent rather than a logo lockup. One infinite
 * phase drives spin, breath and the text sweep; every motion completes a whole number of
 * cycles per loop so the wrap is seamless. The phase is only read inside draw lambdas.
 */

/** Whole loop. Every period below divides it. */
internal const val CLAUDE_LOOP_MS = 3200
internal const val CLAUDE_SPIN_CYCLES = 1     // 3.2 s per lap
internal const val CLAUDE_BREATH_CYCLES = 2   // 1.6 s
internal const val CLAUDE_SWEEP_CYCLES = 2    // white light across the label, 1.6 s per pass

/** Warm coral accent used by the 暖阳 layout (send button + this star). */
internal val ClaudeCoral = Color(0xFFD97757)
internal val ClaudeCoralDeep = Color(0xFFC96342)

private const val TWO_PI = (2.0 * PI).toFloat()

/** Smooth 0→1→0, [cycles] times per loop. */
internal fun claudeWave(phase: Float, cycles: Int): Float = 0.5f - 0.5f * cos(TWO_PI * phase * cycles)

/** Still frame used when animations are off. */
internal const val CLAUDE_STATIC_PHASE = 0.2f

@Composable
private fun rememberClaudePhase(): () -> Float =
    rememberLoopPhase(CLAUDE_LOOP_MS, staticPhase = CLAUDE_STATIC_PHASE, label = "warm-sun")

@Composable
private fun warmSunCoral(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.background.luminance() < 0.5f) ClaudeCoral else ClaudeCoralDeep
}

/**
 * Chat row: rotating warm star + grey "Thinking…" with a white shimmer band sweeping across.
 * Matches the quiet Claude thinking row the user pointed at (star left, static grey label,
 * white light sliding over the glyphs).
 */
@Composable
fun ClaudeThinkingRow(
    modifier: Modifier = Modifier,
    text: String? = null,
) {
    val coral = warmSunCoral()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val phase = rememberClaudePhase()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(
            modifier = Modifier
                .size(18.dp)
                .drawWithCache {
                    val star = Path()
                    onDrawBehind { drawWarmSunStar(coral, phase(), star) }
                },
        )
        Text(
            text = text ?: stringResource(R.string.chat_loading_shimmer_thinking),
            style = MaterialTheme.typography.labelMedium,
            // Base paint is the grey label; the white band is drawn on top via SrcAtop.
            color = labelColor,
            maxLines = 1,
            modifier = Modifier
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val t = phase()
                    // Narrow bright band so it reads as a single white stroke sliding across.
                    val band = size.width * 0.38f
                    val sweep = (t * CLAUDE_SWEEP_CYCLES) % 1f
                    val start = -band + sweep * (size.width + band * 2f)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.35f to Color.White.copy(alpha = 0.15f),
                                0.5f to Color.White.copy(alpha = 0.55f),
                                0.65f to Color.White.copy(alpha = 0.15f),
                                1f to Color.Transparent,
                            ),
                            startX = start,
                            endX = start + band,
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                },
        )
    }
}

/** The star alone (square slots such as dialogs). */
@Composable
fun ClaudeLoadingIndicator(modifier: Modifier = Modifier) {
    val coral = warmSunCoral()
    val phase = rememberClaudePhase()
    Spacer(
        modifier = modifier.drawWithCache {
            val star = Path()
            onDrawBehind { drawWarmSunStar(coral, phase(), star) }
        },
    )
}

/**
 * Soft six-lobe star (not Claude's four-point trademark asterisk). Polar radius peaks six
 * times per turn; lobes are rounded via a high even power so the silhouette stays gentle.
 * Drawn rotated by [t]'s spin and scaled by its breath.
 */
private fun DrawScope.drawWarmSunStar(coral: Color, t: Float, star: Path) {
    val c = center
    val half = min(size.width, size.height) / 2f
    val breath = 0.88f + 0.12f * claudeWave(t, CLAUDE_BREATH_CYCLES)
    val rMax = half * breath

    star.reset()
    val steps = 96
    for (i in 0..steps) {
        val theta = TWO_PI * i / steps
        // Six soft lobes: peaks at every 60°, floor ~0.42 between them.
        val lobe = cos(3f * theta)
        val r = rMax * (0.42f + 0.58f * (lobe * lobe * lobe * lobe))
        val x = c.x + r * cos(theta)
        val y = c.y + r * sin(theta)
        if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
    }
    star.close()

    rotate(degrees = 360f * t * CLAUDE_SPIN_CYCLES, pivot = c) {
        drawPath(star, coral)
    }
}
