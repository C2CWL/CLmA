package com.clma.app.ui.components.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Atom02
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * The three "assistant is working" styles picked from the 2026-10-22 mockups:
 *  - 原子三点  (ATOM_DOTS):     Atom02 spinning in a primary→tertiary gradient + label + three rising dots
 *  - 呼吸光球  (BREATHING_ORB): soft gradient orb with a glow, breathing, colour drifting primary↔tertiary
 *  - 双星绕轨  (TWIN_ORBIT):    faint ring, a primary and a tertiary dot with fading tails chasing round
 *
 * Colours come only from MaterialTheme.colorScheme (primary / tertiary / secondary), so they follow
 * the user's theme. One looping 0..1 phase per row drives everything (see [rememberLoopPhase]); it
 * is only read inside draw / layer lambdas, and with system animations off it is a fixed still frame.
 * Every motion completes a whole number of cycles per loop, so the wrap is seamless.
 */

/** Whole loop for all three styles. */
internal const val THINKING_LOOP_MS = 3200

private const val TWO_PI = (2.0 * PI).toFloat()

/** Still frames used when animations are off. */
internal const val ATOM_DOTS_STATIC_PHASE = 0f
internal const val ORB_STATIC_PHASE = 0.25f
internal const val ORBIT_STATIC_PHASE = 0.1f

private val IndicatorSize = 20.dp

@Composable
private fun rememberThinkingPhase(staticPhase: Float, label: String): () -> Float =
    rememberLoopPhase(THINKING_LOOP_MS, staticPhase = staticPhase, label = label)

/** Lift 0..1 of dot [index] (0..2): each rises in turn, twice per loop, resting half the time. */
internal fun risingDotLift(phase: Float, index: Int): Float {
    val p = ((phase * 2f - index * 0.18f) % 1f + 1f) % 1f
    return if (p < 0.5f) sin(PI.toFloat() * p * 2f) else 0f
}

// ── Label ────────────────────────────────────────────────────────────────────────────────────────

/**
 * The text next to an indicator: [processingStatus] when there is one, otherwise the phase label
 * (思考中 / 回复中 / 工作中), plus "…" when [ellipsis]. Phase changes cross-fade.
 *
 * Two lines instead of one: a status like "已 298 秒没有新内容，可能已卡住；可停止后重试" (and the
 * waiting / retrying ones) is longer than a single line next to the icon, and used to be cut off
 * mid-sentence. Callers pass a weight so the label wraps inside the row that also holds the dots.
 */
@Composable
private fun ThinkingLabel(
    processingStatus: String?,
    phase: GenerationPhase,
    ellipsis: Boolean,
    shimmerPhase: (() -> Float)?,
    modifier: Modifier = Modifier,
) {
    val phaseText = generationPhaseLabel(phase)
    val text = processingStatus ?: if (ellipsis) "$phaseText…" else phaseText
    val color = MaterialTheme.colorScheme.secondary
    val highlight = MaterialTheme.colorScheme.primary
    Crossfade(targetState = text, label = "thinking-label") { shown ->
        Text(
            text = shown,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = if (shimmerPhase == null) modifier else modifier
                // Offscreen so SrcAtop paints the band onto the glyphs only.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val band = size.width * 0.4f
                    val sweep = (shimmerPhase() * 2f) % 1f
                    val start = -band + sweep * (size.width + band * 2f)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.5f to highlight.copy(alpha = 0.85f),
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

// ── 原子三点 ─────────────────────────────────────────────────────────────────────────────────────

/** Atom02 turning once per loop, its strokes painted with a primary→tertiary gradient. */
@Composable
private fun GradientSpinningAtom(phase: () -> Float, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Icon(
        imageVector = HugeIcons.Atom02,
        contentDescription = null,
        tint = primary,
        modifier = modifier
            .graphicsLayer {
                rotationZ = 360f * phase()
                // Offscreen so SrcIn below recolours only the icon's strokes.
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val brush = Brush.linearGradient(
                    colors = listOf(primary, tertiary),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.SrcIn)
                }
            },
    )
}

@Composable
private fun RisingDots(phase: () -> Float, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val colors = listOf(primary, lerp(primary, tertiary, 0.5f), tertiary)
    Spacer(
        modifier = modifier
            .size(width = 20.dp, height = 14.dp)
            .drawBehind {
                val t = phase()
                val r = 2.dp.toPx()
                val gap = 7.dp.toPx()
                val lift = 3.5.dp.toPx()
                val baseY = size.height - r - 1.dp.toPx()
                for (i in 0..2) {
                    val l = risingDotLift(t, i)
                    drawCircle(
                        color = colors[i],
                        radius = r,
                        center = Offset(r + 1.dp.toPx() + i * gap, baseY - lift * l),
                        alpha = 0.4f + 0.6f * l,
                    )
                }
            },
    )
}

@Composable
fun AtomDotsThinkingRow(
    phase: GenerationPhase,
    processingStatus: String?,
    modifier: Modifier = Modifier,
) {
    val t = rememberThinkingPhase(ATOM_DOTS_STATIC_PHASE, "atom-dots")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GradientSpinningAtom(t, Modifier.size(IndicatorSize))
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThinkingLabel(
                processingStatus = processingStatus,
                phase = phase,
                ellipsis = false,
                shimmerPhase = null,
                modifier = Modifier.weight(1f, fill = false),
            )
            RisingDots(t, Modifier.padding(start = 2.dp))
        }
    }
}

/** Icon-only form for square slots (e.g. the compress dialog). */
@Composable
fun AtomDotsLoadingIndicator(modifier: Modifier = Modifier) {
    val t = rememberThinkingPhase(ATOM_DOTS_STATIC_PHASE, "atom-dots-icon")
    GradientSpinningAtom(t, modifier)
}

// ── 呼吸光球 ─────────────────────────────────────────────────────────────────────────────────────

private fun DrawScope.drawBreathingOrb(t: Float, primary: Color, tertiary: Color) {
    val unit = min(size.width, size.height) / 24f
    val breath = 0.5f - 0.5f * cos(TWO_PI * t * 2f)
    val hue = 0.5f + 0.5f * sin(TWO_PI * t)
    val r = (4.6f + 1.4f * breath) * unit
    val light = lerp(lerp(primary, tertiary, hue), Color.White, 0.3f)
    val deep = lerp(primary, tertiary, 1f - hue)
    val c = center
    // Glow.
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.4f to light.copy(alpha = 0.35f + 0.25f * breath),
                1f to light.copy(alpha = 0f),
            ),
            center = c,
            radius = r * 1.9f,
        ),
        radius = r * 1.9f,
        center = c,
    )
    // Orb, lit from the upper left.
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = 0.85f),
                0.35f to light,
                1f to deep,
            ),
            center = Offset(c.x - 0.24f * r, c.y - 0.3f * r),
            radius = r * 1.5f,
        ),
        radius = r,
        center = c,
    )
}

@Composable
private fun BreathingOrb(phase: () -> Float, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Spacer(modifier = modifier.drawBehind { drawBreathingOrb(phase(), primary, tertiary) })
}

@Composable
fun BreathingOrbThinkingRow(
    phase: GenerationPhase,
    processingStatus: String?,
    modifier: Modifier = Modifier,
) {
    val t = rememberThinkingPhase(ORB_STATIC_PHASE, "breathing-orb")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BreathingOrb(t, Modifier.size(IndicatorSize))
        ThinkingLabel(
            processingStatus = processingStatus,
            phase = phase,
            ellipsis = true,
            shimmerPhase = t,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
fun BreathingOrbLoadingIndicator(modifier: Modifier = Modifier) {
    val t = rememberThinkingPhase(ORB_STATIC_PHASE, "breathing-orb-icon")
    BreathingOrb(t, modifier)
}

// ── 双星绕轨 ─────────────────────────────────────────────────────────────────────────────────────

private fun DrawScope.drawTwinOrbit(t: Float, primary: Color, tertiary: Color) {
    val unit = min(size.width, size.height) / 24f
    val ring = 7.5f * unit
    val c = center
    drawCircle(
        color = primary.copy(alpha = 0.18f),
        radius = ring,
        center = c,
        style = Stroke(width = 1.5f * unit),
    )
    val dots = listOf(Triple(primary, 0f, 2.3f), Triple(tertiary, 0.5f, 1.7f))
    for ((color, offset, dotRadius) in dots) {
        // Draw the tail first so the head sits on top.
        for (j in 4 downTo 0) {
            val angle = TWO_PI * (t * 2f + offset) - j * 0.16f - (PI / 2).toFloat()
            drawCircle(
                color = color,
                radius = dotRadius * (1f - j * 0.17f) * unit,
                center = Offset(c.x + ring * cos(angle), c.y + ring * sin(angle)),
                alpha = 1f - j * 0.22f,
            )
        }
    }
}

@Composable
private fun TwinOrbit(phase: () -> Float, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Spacer(modifier = modifier.drawBehind { drawTwinOrbit(phase(), primary, tertiary) })
}

@Composable
fun TwinOrbitThinkingRow(
    phase: GenerationPhase,
    processingStatus: String?,
    modifier: Modifier = Modifier,
) {
    val t = rememberThinkingPhase(ORBIT_STATIC_PHASE, "twin-orbit")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TwinOrbit(t, Modifier.size(IndicatorSize))
        ThinkingLabel(
            processingStatus = processingStatus,
            phase = phase,
            ellipsis = true,
            shimmerPhase = t,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
fun TwinOrbitLoadingIndicator(modifier: Modifier = Modifier) {
    val t = rememberThinkingPhase(ORBIT_STATIC_PHASE, "twin-orbit-icon")
    TwinOrbit(t, modifier)
}
