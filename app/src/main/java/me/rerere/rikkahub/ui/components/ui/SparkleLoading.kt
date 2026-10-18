package me.rerere.rikkahub.ui.components.ui

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/*
 * "Sparkle" loading indicator: a glossy rounded pentagon on a faint translucent disc, slowly
 * spinning and breathing, filled with a flowing five-colour gradient, inside a thin ring that a
 * comet highlight circles the other way, with twinkling four-point stars around it. Next to it a
 * "Replying" label carries the same gradient with a shimmer sweep and three wave-bouncing dots.
 *
 * One infinite transition drives everything: a 0..1 phase over SPARKLE_LOOP_MS. Every motion
 * completes a whole number of cycles per loop, so the wrap is seamless. The phase is only read
 * inside draw lambdas, so animating redraws without recomposing or re-laying out.
 */

/** Whole loop. Every period below divides it. */
internal const val SPARKLE_LOOP_MS = 4800
internal const val SPARKLE_SPIN_CYCLES = 1     // 4.8 s per lap, clockwise
internal const val SPARKLE_BREATH_CYCLES = 3   // 1.6 s
internal const val SPARKLE_COMET_CYCLES = 3    // 1.6 s per lap, counter-clockwise
internal const val SPARKLE_SWEEP_CYCLES = 3    // label shimmer, 1.6 s per pass
internal const val SPARKLE_DOT_CYCLES = 4      // dots wave, 1.2 s
internal const val SPARKLE_TWINKLE_CYCLES = 2  // stars, 2.4 s
internal const val SPARKLE_GLINT_CYCLES = 4    // glints, 1.2 s

/** Breathing scale range of the pentagon. */
internal const val SPARKLE_BREATH_MIN = 0.85f

/** Pentagon radius at full breath, as a fraction of the base disc radius. */
internal const val SPARKLE_SHAPE_FILL = 0.84f

/** Comet tail length, degrees. */
internal const val SPARKLE_COMET_TAIL_DEG = 230f

private const val TWO_PI = (2.0 * PI).toFloat()

/** Smooth 0→1→0, [cycles] times per loop. */
internal fun sparkleWave(phase: Float, cycles: Int): Float = 0.5f - 0.5f * cos(TWO_PI * phase * cycles)

/** Breathing scale for [phase]: eased between [SPARKLE_BREATH_MIN] and 1. */
internal fun sparkleBreath(phase: Float): Float =
    SPARKLE_BREATH_MIN + (1f - SPARKLE_BREATH_MIN) * sparkleWave(phase, SPARKLE_BREATH_CYCLES)

/** Twinkle brightness 0..1 for a star with [offset] (0..1); dark for half of each cycle. */
internal fun sparkleTwinkle(phase: Float, offset: Float, cycles: Int): Float {
    val s = sin(TWO_PI * (phase * cycles + offset))
    return if (s <= 0f) 0f else s * s
}

/**
 * Lift (0..1) of dot [index] (0..2) in the "…" wave: each dot rises in turn during the first half
 * of its cycle and rests for the second half.
 */
internal fun sparkleDotLift(phase: Float, index: Int): Float {
    val p = phase * SPARKLE_DOT_CYCLES - index * 0.18f
    val local = p - floor(p)
    if (local >= 0.5f) return 0f
    val s = sin(TWO_PI * local)
    return s * s
}

/** Radius multiplier of the rounded pentagon at polar angle [theta] (radians, shape frame). */
internal fun sparklePentagon(theta: Float): Float = 0.93f + 0.07f * cos(5f * theta)

/** Muted, eye-friendly palette for dark themes: cyan, blue, violet, pink, peach. */
private val DarkPalette = listOf(
    Color(0xFF6EA8B4),
    Color(0xFF708EBE),
    Color(0xFF8E80BA),
    Color(0xFFB27A9E),
    Color(0xFFBA9278),
)

/** Vivid, saturated palette (same hues) that stands out on light themes. */
private val LightPalette = listOf(
    Color(0xFF00C4E6),
    Color(0xFF286EFF),
    Color(0xFF8246FA),
    Color(0xFFF032A0),
    Color(0xFFFF823C),
)

private class SparkleLook(
    val dark: Boolean,
    val palette: List<Color>,
    val disc: Color,
    val star: Color,
)

@Composable
private fun rememberSparkleLook(): SparkleLook {
    val scheme = MaterialTheme.colorScheme
    // Pick by how dark the theme actually is, not by the dark-mode setting alone.
    val dark = scheme.background.luminance() < 0.5f
    return SparkleLook(
        dark = dark,
        palette = if (dark) DarkPalette else LightPalette,
        disc = scheme.surface.copy(alpha = if (dark) 0.30f else 0.45f),
        star = if (dark) Color(0xFFC8CEDE) else Color(0xFF8246FA),
    )
}

/** Still frame used when animations are off: pentagon at full size, comet half-way round. */
internal const val SPARKLE_STATIC_PHASE = 1f / 6f

@Composable
private fun rememberSparklePhase(): () -> Float =
    rememberLoopPhase(SPARKLE_LOOP_MS, staticPhase = SPARKLE_STATIC_PHASE, label = "sparkle")

private fun paletteAt(stops: List<Color>, u: Float): Color {
    val x = u.coerceIn(0f, 1f) * (stops.size - 1)
    val i = min(x.toInt(), stops.size - 2)
    return lerp(stops[i], stops[i + 1], x - i)
}

/** The icon on its own (e.g. square slots such as the compress dialog). */
@Composable
fun SparkleLoadingIndicator(modifier: Modifier = Modifier) {
    val look = rememberSparkleLook()
    val phase = rememberSparklePhase()
    SparkleIcon(modifier, look, phase)
}

/**
 * The full chat row: icon, then [text] (defaults to "Replying") with the gradient shimmer and the
 * three bouncing dots. Icon and label share one transition.
 */
@Composable
fun SparkleThinkingRow(
    modifier: Modifier = Modifier,
    text: String? = null,
) {
    val look = rememberSparkleLook()
    val phase = rememberSparklePhase()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SparkleIcon(Modifier.size(28.dp), look, phase)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text ?: stringResource(R.string.chat_loading_sparkle_replying),
                style = MaterialTheme.typography.labelMedium,
                // Replaced by the gradient below (SrcIn); only the glyph coverage matters.
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        val t = phase()
                        drawRect(flowBrush(look, t, size.width), blendMode = BlendMode.SrcIn)
                        val band = size.width * 0.45f
                        val sweep = (t * SPARKLE_SWEEP_CYCLES) % 1f
                        val start = -band + sweep * (size.width + band)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = if (look.dark) 0.30f else 0.25f),
                                    Color.Transparent,
                                ),
                                startX = start,
                                endX = start + band,
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    },
            )
            Spacer(
                modifier = Modifier
                    .size(width = 18.dp, height = 14.dp)
                    .drawWithCache {
                        onDrawBehind { drawDots(look, phase()) }
                    },
            )
        }
    }
}

/** Flowing five-colour gradient across [width] px. */
private fun flowBrush(look: SparkleLook, t: Float, width: Float): Brush {
    val base = 0.25f * sparkleWave(t, 1)
    return Brush.horizontalGradient(
        colors = List(5) { paletteAt(look.palette, base + it * 0.1875f) },
        startX = 0f,
        endX = width.coerceAtLeast(1f),
    )
}

private fun DrawScope.drawDots(look: SparkleLook, t: Float) {
    val r = 1.3.dp.toPx()
    val gap = 4.2.dp.toPx()
    val baseY = size.height / 2f + 2.dp.toPx()
    val brush = flowBrush(look, t, size.width)
    for (i in 0 until 3) {
        val lift = sparkleDotLift(t, i)
        val c = Offset(3.dp.toPx() + i * gap + r, baseY - lift * 2.6.dp.toPx())
        drawCircle(brush, radius = r, center = c)
        if (lift > 0.02f) {
            drawCircle(Color.White.copy(alpha = lift * if (look.dark) 0.35f else 0.25f), radius = r, center = c)
        }
    }
}

@Composable
private fun SparkleIcon(modifier: Modifier, look: SparkleLook, phase: () -> Float) {
    Spacer(
        modifier = modifier.drawWithCache {
            val shape = Path()
            val star = Path()
            onDrawBehind { drawSparkleIcon(look, phase(), shape, star) }
        },
    )
}

private fun DrawScope.drawSparkleIcon(look: SparkleLook, t: Float, shape: Path, star: Path) {
    val c = center
    val half = min(size.width, size.height) / 2f
    val ringStroke = 2.5.dp.toPx()
    val ringRadius = half - ringStroke / 2f - 0.75.dp.toPx()
    val discRadius = ringRadius - ringStroke / 2f - 1.dp.toPx()
    val drift = 0.25f * sparkleWave(t, 1)

    // 1. faint translucent base disc
    drawCircle(look.disc, radius = discRadius, center = c)

    // 2. soft same-colour glow behind the shape
    val r = discRadius * SPARKLE_SHAPE_FILL * sparkleBreath(t)
    val glow = paletteAt(look.palette, 0.35f + drift)
    drawCircle(
        brush = Brush.radialGradient(
            0f to glow.copy(alpha = if (look.dark) 0.30f else 0.45f),
            0.62f to glow.copy(alpha = if (look.dark) 0.22f else 0.32f),
            1f to Color.Transparent,
            center = c,
            radius = r * 1.55f,
        ),
        radius = r * 1.55f,
        center = c,
    )

    // 3. the pentagon, rebuilt each frame with spin and breath baked in, so the light stays fixed
    val spin = TWO_PI * SPARKLE_SPIN_CYCLES * t
    shape.reset()
    val steps = 72
    for (k in 0..steps) {
        val a = TWO_PI * k / steps
        val rr = r * sparklePentagon(a - spin)
        val x = c.x + cos(a) * rr
        val y = c.y + sin(a) * rr
        if (k == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
    }
    shape.close()
    val axis = 0.6f - spin * 0.5f
    val dx = cos(axis) * r
    val dy = sin(axis) * r
    drawPath(
        path = shape,
        brush = Brush.linearGradient(
            colors = List(5) { paletteAt(look.palette, drift + it * 0.1875f) },
            start = Offset(c.x - dx, c.y - dy),
            end = Offset(c.x + dx, c.y + dy),
        ),
    )
    // volume: darker towards the bottom-right, highlight top-left, small gloss spot
    drawPath(
        path = shape,
        brush = Brush.radialGradient(
            0f to Color.Transparent,
            0.55f to Color.Transparent,
            1f to Color.Black.copy(alpha = if (look.dark) 0.22f else 0.16f),
            center = Offset(c.x - r * 0.35f, c.y - r * 0.35f),
            radius = r * 1.9f,
        ),
    )
    drawPath(
        path = shape,
        brush = Brush.radialGradient(
            0f to Color.White.copy(alpha = if (look.dark) 0.28f else 0.45f),
            1f to Color.Transparent,
            center = Offset(c.x - r * 0.33f, c.y - r * 0.38f),
            radius = r * 0.55f,
        ),
    )
    val gloss = Offset(c.x - r * 0.20f, c.y - r * 0.28f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.White.copy(alpha = if (look.dark) 0.30f else 0.55f),
            1f to Color.Transparent,
            center = gloss,
            radius = r * 0.20f,
        ),
        radius = r * 0.20f,
        center = gloss,
    )

    // 4. tiny glints inside, turning with the shape
    val glintR = 0.55.dp.toPx()
    for ((angle, dist, offset) in Glints) {
        val tw = sparkleTwinkle(t, offset, SPARKLE_GLINT_CYCLES)
        if (tw <= 0.05f) continue
        val a = angle + spin
        drawCircle(
            Color.White.copy(alpha = tw * if (look.dark) 0.6f else 0.95f),
            radius = glintR,
            center = Offset(c.x + cos(a) * r * dist, c.y + sin(a) * r * dist),
        )
    }

    // 5. ring: faint palette base + a comet running the other way, its colour walking the palette
    drawCircle(
        brush = Brush.sweepGradient(look.palette + look.palette.first(), center = c),
        radius = ringRadius,
        center = c,
        alpha = 0.22f,
        style = Stroke(width = ringStroke),
    )
    val headDeg = -360f * SPARKLE_COMET_CYCLES * t
    val cometPhase = (t * SPARKLE_COMET_CYCLES) % 1f
    val head = lerp(paletteAt(look.palette, cometPhase), Color.White, if (look.dark) 0.12f else 0.25f)
    val topLeft = Offset(c.x - ringRadius, c.y - ringRadius)
    val arc = Size(ringRadius * 2f, ringRadius * 2f)
    rotate(degrees = headDeg, pivot = c) {
        // Sweep angles run clockwise from 3 o'clock: head at 0°, the tail fades out by 230°.
        val tail = Brush.sweepGradient(
            0f to head,
            (SPARKLE_COMET_TAIL_DEG / 360f) to head.copy(alpha = 0f),
            1f to head.copy(alpha = 0f),
            center = c,
        )
        drawArc(
            brush = tail, startAngle = 0f, sweepAngle = SPARKLE_COMET_TAIL_DEG * 0.55f, useCenter = false,
            topLeft = topLeft, size = arc, alpha = if (look.dark) 0.18f else 0.28f,
            style = Stroke(width = ringStroke * 3.4f, cap = StrokeCap.Round),
        )
        drawArc(
            brush = tail, startAngle = 0f, sweepAngle = SPARKLE_COMET_TAIL_DEG * 0.75f, useCenter = false,
            topLeft = topLeft, size = arc, alpha = if (look.dark) 0.30f else 0.45f,
            style = Stroke(width = ringStroke * 2f, cap = StrokeCap.Round),
        )
        drawArc(
            brush = tail, startAngle = 0f, sweepAngle = SPARKLE_COMET_TAIL_DEG, useCenter = false,
            topLeft = topLeft, size = arc,
            style = Stroke(width = ringStroke, cap = StrokeCap.Round),
        )
    }

    // 6. four-point stars twinkling around the ring
    val unit = 1.dp.toPx()
    val sway = 0.35f * sin(TWO_PI * t * SPARKLE_TWINKLE_CYCLES)
    val starAlpha = if (look.dark) 0.8f else 1f
    for (s in Stars) {
        val tw = sparkleTwinkle(t, s.offset, SPARKLE_TWINKLE_CYCLES)
        if (tw <= 0.03f) continue
        val a = s.angleDeg * (PI.toFloat() / 180f) + sway
        val p = Offset(c.x + cos(a) * half * s.radius, c.y + sin(a) * half * s.radius)
        val armLen = unit * 2.8f * s.size * (0.65f + 0.35f * tw)
        val waist = armLen * 0.26f
        drawCircle(look.star.copy(alpha = 0.22f * tw), radius = armLen * 0.9f, center = p)
        star.reset()
        star.moveTo(p.x, p.y - armLen)
        star.lineTo(p.x + waist, p.y - waist)
        star.lineTo(p.x + armLen, p.y)
        star.lineTo(p.x + waist, p.y + waist)
        star.lineTo(p.x, p.y + armLen)
        star.lineTo(p.x - waist, p.y + waist)
        star.lineTo(p.x - armLen, p.y)
        star.lineTo(p.x - waist, p.y - waist)
        star.close()
        drawPath(star, look.star.copy(alpha = starAlpha * tw))
    }
}

private class Star(val angleDeg: Float, val radius: Float, val offset: Float, val size: Float)

// angle (deg), distance (in half-sizes of the icon box), twinkle offset, relative size
private val Stars = listOf(
    Star(-50f, 1.32f, 0.00f, 1.00f),
    Star(25f, 1.24f, 0.35f, 0.75f),
    Star(125f, 1.34f, 0.62f, 0.90f),
    Star(205f, 1.26f, 0.15f, 0.70f),
    Star(290f, 1.38f, 0.82f, 0.85f),
)

// angle (rad), distance (fraction of shape radius), twinkle offset
private val Glints = listOf(
    Triple(0.5f, 0.45f, 0.10f),
    Triple(2.6f, 0.30f, 0.55f),
    Triple(4.4f, 0.50f, 0.30f),
    Triple(5.8f, 0.20f, 0.80f),
)
