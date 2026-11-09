package com.clma.app.ui.components.ai

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.clma.ai.core.ReasoningLevel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * v9 energy bar — replaces the v8 tiled-gradient fill.
 *
 * v8 bug: the ultra fill was a 5-stop gradient (dark blue → light violet) tiled with
 * [TileMode.Repeated] and slid by `flowPhase`. The tile's first and last colours do not
 * match, so every tile boundary drew a hard vertical seam; as the phase wrapped the seam
 * snapped back a half-tile early — the "fills up, then restarts from the middle" jump.
 *
 * v9 rules:
 * - The fill gradient is anchored to the **track** (blue at 自动 → violet at 最大) and is
 *   never tiled, so it cannot produce a seam at any fill amount.
 * - Life comes from a **seamless, cyclic** sheen: its palette is sampled from a raised
 *   cosine (`0 → 1 → 0`), so both value and slope match at the tile boundary — no seam,
 *   no restart, no visible wrap.
 * - Stardust wraps between edges only while fully faded out, so grains never pop.
 * - Geometry is slot-based: level i owns slot i, its label is centred in that slot, the
 *   thumb parks over the label, ticks sit on the slot boundaries. Fill / thumb / ticks /
 *   labels all agree instead of drifting apart.
 * - No bouncy overshoot: the fill and thumb are clamped to the track at all times.
 */
private val EnergyBlue = Color(0xFF2B4CC2)
private val EnergyBlueSoft = Color(0xFF3A5FD4)
private val EnergyIndigo = Color(0xFF7166E6)
private val EnergyPurple = Color(0xFFA96EFF)
private val EnergyViolet = Color(0xFF9C74FA)
private val EnergyAccent = Color(0xFFB06DFF)
private val EnergyStar = Color(0xFFFFFFFF)

/** Bar-anchored ultra gradient. Anchored to the track width — never tiled, never seams. */
private val UltraFillStops = arrayOf(
    0f to Color(0xFF2B4CC2),
    0.26f to Color(0xFF3655CF),
    0.52f to EnergyIndigo,
    0.76f to EnergyPurple,
    1f to EnergyViolet,
)

/**
 * Cyclic sheen palette: alpha follows a raised cosine `0 → peak → 0`, so the last colour
 * equals the first in value *and* slope. Tiling it with [TileMode.Repeated] is invisible
 * at the wrap point — this is what lets the sweep loop forever without a snap.
 */
private fun cyclicSheen(steps: Int, peakAlpha: Float, color: Color, sharpen: Float): List<Color> =
    List(steps + 1) { i ->
        val t = i / steps.toFloat()
        val bell = 0.5f - 0.5f * cos(2f * PI.toFloat() * t)
        val shape = if (sharpen == 1f) bell else Math.pow(bell.toDouble(), sharpen.toDouble()).toFloat()
        color.copy(alpha = peakAlpha * shape)
    }

private val SheenWhite: List<Color> = cyclicSheen(steps = 48, peakAlpha = 0.16f, color = Color.White, sharpen = 3f)
private val SheenViolet: List<Color> = cyclicSheen(steps = 48, peakAlpha = 0.30f, color = EnergyAccent, sharpen = 2f)

/** Fixed-seed dust grain: drifts right with an optional trailing streak. */
private class Dust(
    val x0: Float,
    val y0: Float,
    val rDp: Float,
    val drift: Float,
    val twinkleHarmonic: Int,
    val phase: Float,
    val hasStreak: Boolean,
    val hasBloom: Boolean,
)

private val DustField: List<Dust> = run {
    val rnd = Random(0x8EED0088L)
    List(44) { i ->
        Dust(
            x0 = rnd.nextFloat(),
            y0 = 0.14f + 0.72f * rnd.nextFloat(),
            rDp = 0.55f + 1.7f * rnd.nextFloat() * rnd.nextFloat(),
            drift = 0.55f + 1.15f * rnd.nextFloat(),
            // Integer harmonics keep the twinkle periodic across the phase wrap.
            twinkleHarmonic = 1 + rnd.nextInt(3),
            phase = rnd.nextFloat() * (2f * PI.toFloat()),
            hasStreak = i % 2 == 0 || rnd.nextFloat() > 0.55f,
            hasBloom = i % 4 == 0,
        )
    }
}

/** Ultra visual (gradient + stardust + bloom) at 超高 / 最大, or 高 when it is the top stop. */
private fun isUltraStop(level: ReasoningLevel, levels: List<ReasoningLevel>): Boolean {
    if (level == ReasoningLevel.XHIGH || level == ReasoningLevel.MAX) return true
    return level == ReasoningLevel.HIGH && levels.lastOrNull() == ReasoningLevel.HIGH
}

private fun smoothStep(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

@Composable
internal fun ReasoningEnergyBar(
    currentLevel: ReasoningLevel,
    onSelectLevel: (ReasoningLevel) -> Unit,
    levels: List<ReasoningLevel> = ReasoningLevel.entries,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val latestLevel by rememberUpdatedState(currentLevel)
    val latestOnSelect by rememberUpdatedState(onSelectLevel)
    val latestHaptic by rememberUpdatedState(haptic)

    val levelCount = levels.size.coerceAtLeast(1)
    val currentIndex = levels.indexOf(currentLevel).let { if (it >= 0) it else 0 }
    // 0..1 across the level stops; kept continuous while a finger is down.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val denom = (levelCount - 1).toFloat().coerceAtLeast(1f)
    val displayFraction = dragFraction ?: (currentIndex / denom)
    val activeLevel = levels[(displayFraction * denom).roundToInt().coerceIn(0, levelCount - 1)]

    val animatedFraction by animateFloatAsState(
        targetValue = displayFraction.coerceIn(0f, 1f),
        animationSpec = if (dragFraction != null) {
            tween(0)
        } else {
            // Settles onto the stop without overshooting past the track ends.
            spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
        },
        label = "energyBarFraction",
    )

    val ultra = isUltraStop(activeLevel, levels)
    val ultraMix by animateFloatAsState(
        targetValue = if (ultra) 1f else 0f,
        animationSpec = tween(280),
        label = "energyUltraMix",
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            activeLevel == ReasoningLevel.MAX -> EnergyAccent
            ultra -> EnergyPurple
            activeLevel == ReasoningLevel.OFF -> Color(0xFF8B93A7)
            activeLevel == ReasoningLevel.AUTO -> EnergyBlueSoft
            else -> EnergyBlue
        },
        animationSpec = tween(250),
        label = "energyLabelColor",
    )

    val trackHeight = 32.dp
    val thumbSize = 32.dp

    val infinite = rememberInfiniteTransition(label = "energyBarLife")
    // Sheen sweep: one tile per pass. The palette is cyclic, so the wrap is invisible.
    val sheenPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_400, easing = LinearEasing)),
        label = "energySheen",
    )
    // Second, slower, counter-moving band: two bands never align into a visible beat.
    val counterPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6_100, easing = LinearEasing)),
        label = "energySheenCounter",
    )
    // ~one full horizontal drift every 12s — streaks make the motion readable.
    val drift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing)),
        label = "energyDustDrift",
    )
    // Twinkle uses integer harmonics of this phase, so it is perfectly periodic.
    val shimmer by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5_200, easing = LinearEasing)),
        label = "energyShimmer",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .pointerInput(levelCount) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        down.consume()
                        // The thumb travels between thumbR and width - thumbR, exactly like
                        // the drawn capsule, so a drag lands where the thumb will park.
                        val thumbPxLocal = thumbSize.toPx()
                        val travel = (size.width.toFloat() - thumbPxLocal).coerceAtLeast(1f)
                        fun fractionAt(x: Float): Float =
                            ((x - thumbPxLocal / 2f) / travel).coerceIn(0f, 1f)
                        fun indexAt(fraction: Float): Int =
                            (fraction * denom).roundToInt().coerceIn(0, levelCount - 1)

                        var fraction = fractionAt(down.position.x)
                        var committed = latestLevel
                        fun commit(index: Int) {
                            val selected = levels[index.coerceIn(0, levelCount - 1)]
                            if (selected == committed) return
                            committed = selected
                            latestHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnSelect(selected)
                        }
                        dragFraction = fraction
                        commit(indexAt(fraction))
                        val pointerId = down.id
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
                                change.consume()
                                if (!change.pressed) break
                                fraction = fractionAt(change.position.x)
                                dragFraction = fraction
                                commit(indexAt(fraction))
                            }
                        } finally {
                            commit(indexAt(fraction))
                            dragFraction = null
                        }
                    }
                },
        ) {
            val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            val trackBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            val thumbPx = with(LocalDensity.current) { thumbSize.toPx() }
            val trackShape = RoundedCornerShape(percent = 50)

            val fraction = animatedFraction.coerceIn(0f, 1f)
            // Stop i sits at thumbR + travel * i / denom, so the thumb caps the capsule flush
            // at both ends and the labels (see below) are laid out on the same stops.
            val trackWidthDp = maxWidth
            val thumbOffset = ((trackWidthDp - thumbSize) * fraction).coerceIn(
                0.dp,
                (trackWidthDp - thumbSize).coerceAtLeast(0.dp),
            )

            // Soft outer violet glow around the filled capsule (unclipped).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        if (ultraMix <= 0.01f || fraction <= 0f) return@drawBehind
                        val centerX = thumbPx / 2f + (size.width - thumbPx) * fraction
                        val activeWidth = (centerX + thumbPx / 2f).coerceAtMost(size.width)
                        val glow = Brush.horizontalGradient(
                            colors = listOf(
                                EnergyBlue.copy(alpha = 0.0f),
                                EnergyPurple.copy(alpha = 0.35f * ultraMix),
                                EnergyAccent.copy(alpha = 0.45f * ultraMix),
                            ),
                            endX = activeWidth,
                        )
                        drawRoundRect(
                            brush = glow,
                            size = Size(activeWidth, size.height),
                            cornerRadius = CornerRadius(size.height / 2f),
                            alpha = 0.55f,
                        )
                        val pad = 6.dp.toPx() * ultraMix
                        drawRoundRect(
                            color = EnergyPurple.copy(alpha = 0.22f * ultraMix),
                            topLeft = Offset(-pad * 0.2f, -pad),
                            size = Size(activeWidth + pad * 0.4f, size.height + pad * 2f),
                            cornerRadius = CornerRadius((size.height + pad * 2f) / 2f),
                        )
                    },
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(trackShape),
                ) {
                    val h = size.height
                    val w = size.width
                    val radius = CornerRadius(h / 2f)
                    drawRoundRect(color = trackColor, cornerRadius = radius)

                    val centerX = thumbPx / 2f + (w - thumbPx) * fraction
                    val off = activeLevel == ReasoningLevel.OFF && fraction <= 0.001f
                    // Fill runs under the thumb centre and a hair past it — never a hard cut.
                    val activeWidth = if (off) 0f else (centerX + thumbPx / 2f).coerceIn(0f, w)
                    val fillSize = Size(activeWidth, h)
                    val mix = ultraMix

                    if (activeWidth > 0f) {
                        if (mix < 1f) {
                            val solid = if (activeLevel == ReasoningLevel.AUTO) EnergyBlueSoft else EnergyBlue
                            drawRoundRect(
                                brush = Brush.horizontalGradient(
                                    listOf(solid.copy(alpha = 0.92f), solid),
                                    startX = 0f,
                                    endX = w,
                                ),
                                size = fillSize,
                                cornerRadius = radius,
                                alpha = 1f - mix,
                            )
                        }
                        if (mix > 0f) {
                            // Static, bar-anchored gradient: blue at 自动 → violet at 最大.
                            drawRoundRect(
                                brush = Brush.horizontalGradient(
                                    *UltraFillStops,
                                    startX = 0f,
                                    endX = w,
                                ),
                                size = fillSize,
                                cornerRadius = radius,
                                alpha = mix,
                            )

                            clipRect(right = activeWidth) {
                                // Two cyclic sheen bands. Palette is C1 at the tile seam,
                                // so the loop point is invisible.
                                val p1 = 240.dp.toPx()
                                val s1 = sheenPhase * p1
                                drawRoundRect(
                                    brush = Brush.horizontalGradient(
                                        colors = SheenWhite,
                                        startX = -s1,
                                        endX = -s1 + p1,
                                        tileMode = TileMode.Repeated,
                                    ),
                                    size = fillSize,
                                    cornerRadius = radius,
                                    alpha = mix,
                                )
                                val p2 = 320.dp.toPx()
                                val s2 = counterPhase * p2
                                drawRoundRect(
                                    brush = Brush.horizontalGradient(
                                        colors = SheenViolet,
                                        startX = s2,
                                        endX = s2 + p2,
                                        tileMode = TileMode.Repeated,
                                    ),
                                    size = fillSize,
                                    cornerRadius = radius,
                                    alpha = mix * 0.9f,
                                )

                                // Drifting stardust + streaks; wraps only while faded out.
                                val twoPi = 2f * PI.toFloat()
                                for (p in DustField) {
                                    val u = (p.x0 + drift * p.drift) % 1f
                                    val x = u * w
                                    val y = (p.y0 + 0.04f * sin(drift * twoPi + p.phase))
                                        .coerceIn(0.08f, 0.92f) * h
                                    val tw = (sin(shimmer * twoPi * p.twinkleHarmonic + p.phase) + 1f) * 0.5f
                                    // Fade in at the left edge, out at the right: the wrap is unseen.
                                    val edgeFade = smoothStep(u / 0.10f) *
                                        smoothStep((1f - u) / 0.10f)
                                    val alpha = (0.30f + 0.70f * tw) * mix * edgeFade
                                    if (alpha <= 0.01f) continue
                                    val r = p.rDp.dp.toPx() * (0.75f + 0.45f * tw)

                                    if (p.hasStreak) {
                                        val sw = r * (5.5f + 3.5f * tw)
                                        val sh = (r * 0.85f).coerceAtLeast(0.8.dp.toPx())
                                        drawRoundRect(
                                            color = EnergyStar,
                                            topLeft = Offset(x - sw, y - sh / 2f),
                                            size = Size(sw, sh),
                                            cornerRadius = CornerRadius(sh / 2f),
                                            alpha = alpha * 0.42f,
                                        )
                                    }
                                    if (p.hasBloom) {
                                        drawCircle(
                                            color = EnergyStar,
                                            radius = r * 2.2f,
                                            center = Offset(x, y),
                                            alpha = alpha * 0.22f,
                                        )
                                    }
                                    drawCircle(
                                        color = EnergyStar,
                                        radius = r,
                                        center = Offset(x, y),
                                        alpha = alpha,
                                    )
                                }
                            }
                        }

                        // Soft bloom at the leading edge — energy past the thumb, not a cut.
                        val bloomColor = lerp(EnergyBlue, EnergyPurple, mix)
                        val bloomR = h * (1.55f + 0.55f * mix)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    bloomColor.copy(alpha = 0.26f + 0.40f * mix),
                                    Color.Transparent,
                                ),
                                center = Offset(centerX, h / 2f),
                                radius = bloomR,
                            ),
                            radius = bloomR,
                            center = Offset(centerX, h / 2f),
                        )

                        // Top sheen.
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
                                endY = h * 0.48f,
                            ),
                            size = Size(activeWidth, h * 0.48f),
                            cornerRadius = radius,
                        )
                    }

                    // Notch dots on the midpoints between stops — between two labels.
                    if (levelCount > 2) {
                        for (i in 1 until levelCount) {
                            val x = thumbPx / 2f + (w - thumbPx) * (i - 0.5f) / denom
                            drawCircle(
                                color = if (x <= activeWidth) Color.White.copy(alpha = 0.85f)
                                else Color.White.copy(alpha = 0.22f),
                                radius = 2.5.dp.toPx(),
                                center = Offset(x, h / 2f),
                            )
                        }
                    }

                    drawRoundRect(
                        color = trackBorder,
                        cornerRadius = radius,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            } // outer glow Box

            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(thumbSize)
                    .align(Alignment.CenterStart)
                    .drawBehind {
                        if (ultraMix > 0.01f) {
                            val r = size.minDimension * 1.15f
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        EnergyPurple.copy(alpha = 0.55f * ultraMix),
                                        EnergyViolet.copy(alpha = 0.18f * ultraMix),
                                        Color.Transparent,
                                    ),
                                    center = center,
                                    radius = r,
                                ),
                                radius = r,
                            )
                        }
                    }
                    .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
                    .background(Color.White, CircleShape)
                    .border(
                        width = 1.dp,
                        color = Color.Black.copy(alpha = 0.06f),
                        shape = CircleShape,
                    ),
            )
        }

        // Labels ride the same stops as the thumb: the row is widened by one thumb and
        // pulled out by half of it, which puts label i under stop i (error < thumb/10).
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .requiredWidth(maxWidth + thumbSize)
                    .offset(x = -(thumbSize / 2)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                levels.forEachIndexed { index, level ->
                    val selected = index == (displayFraction * denom).roundToInt()
                    Text(
                        text = level.label(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) {
                            labelColor
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (level != currentLevel) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectLevel(level)
                                }
                            }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}
