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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Greenfield Codex Ultra energy bar (v7 mockup).
 *
 * - ~32dp capsule track, white round thumb ≈ bar height
 * - Low / mid / 高: solid cool blue `#2B4CC2`
 * - 超高 / 最大 (or 高 when it is the model's top stop): blue→violet gradient
 *   with fixed-seed drifting + twinkling stardust, soft leading-edge bloom,
 *   thumb shadow + faint violet outer glow
 * - Spring fill / thumb; ~250ms color crossfades
 * - Single Canvas for the track fill (no nested overlay composables)
 */
private val EnergyBlue = Color(0xFF2B4CC2)
private val EnergyIndigo = Color(0xFF7166E6)
private val EnergyPurple = Color(0xFFAC72FF)
private val EnergyViolet = Color(0xFF9273F8)
private val EnergyAccent = Color(0xFFB06DFF)
private val EnergyStar = Color(0xFFF5F2FF)

private val UltraFillColors = listOf(
    Color(0xFF2B4CC2),
    Color(0xFF2F4FC5),
    EnergyIndigo,
    EnergyPurple,
    EnergyViolet,
)

/** Fixed-seed particle field: drifts right and twinkles. */
private class Dust(
    val x0: Float,
    val y: Float,
    val rDp: Float,
    val drift: Float,
    val twinkle: Float,
    val phase: Float,
)

private val DustField: List<Dust> = run {
    val rnd = Random(0xC0DE5)
    List(40) {
        Dust(
            x0 = rnd.nextFloat(),
            y = 0.16f + 0.68f * rnd.nextFloat(),
            rDp = 0.55f + 1.6f * rnd.nextFloat() * rnd.nextFloat(),
            drift = 0.45f + 1.1f * rnd.nextFloat(),
            twinkle = 2.5f + 5.5f * rnd.nextFloat(),
            phase = rnd.nextFloat() * (2f * PI.toFloat()),
        )
    }
}

/** True when this stop should show the Ultra gradient + stardust. */
private fun isUltraStop(level: ReasoningLevel, levels: List<ReasoningLevel>): Boolean {
    if (level == ReasoningLevel.XHIGH || level == ReasoningLevel.MAX) return true
    // When the model tops out at HIGH, HIGH is the highest high-tier stop.
    return level == ReasoningLevel.HIGH && levels.lastOrNull() == ReasoningLevel.HIGH
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
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val displayIndex = dragProgress ?: currentIndex.toFloat()
    val activeLevel = levels[displayIndex.roundToInt().coerceIn(0, levelCount - 1)]

    val denom = (levelCount - 1).toFloat().coerceAtLeast(1f)
    val targetFraction = (displayIndex / denom).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = if (dragProgress != null) {
            tween(0)
        } else {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
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
            ultra && activeLevel == ReasoningLevel.MAX -> EnergyAccent
            ultra -> EnergyPurple
            else -> EnergyBlue
        },
        animationSpec = tween(250),
        label = "energyLabelColor",
    )

    val trackHeight = 32.dp
    val thumbSize = 32.dp

    val infinite = rememberInfiniteTransition(label = "energyBarLife")
    val flowPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "energyFlow",
    )
    // One full horizontal drift every 12s.
    val drift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing)),
        label = "energyDustDrift",
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
                        fun indexAt(x: Float): Float =
                            (x / size.width.toFloat().coerceAtLeast(1f) * (levelCount - 1))
                                .coerceIn(0f, (levelCount - 1).toFloat())
                        var progress = indexAt(down.position.x)
                        var committed = latestLevel
                        fun commit(index: Int) {
                            val selected = levels[index.coerceIn(0, levelCount - 1)]
                            if (selected == committed) return
                            committed = selected
                            latestHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnSelect(selected)
                        }
                        dragProgress = progress
                        commit(progress.roundToInt())
                        val pointerId = down.id
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
                                change.consume()
                                if (!change.pressed) break
                                progress = indexAt(change.position.x)
                                dragProgress = progress
                                commit(progress.roundToInt())
                            }
                        } finally {
                            commit(progress.roundToInt())
                            dragProgress = null
                        }
                    }
                },
        ) {
            val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            val trackBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            val thumbPx = with(LocalDensity.current) { thumbSize.toPx() }
            val trackShape = RoundedCornerShape(percent = 50)

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(trackShape),
            ) {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(color = trackColor, cornerRadius = radius)

                val fraction = animatedFraction.coerceIn(0f, 1f)
                // Fill extends past the thumb centre so the bloom sits under it.
                val edgeX = (size.width - thumbPx) * fraction + thumbPx / 2f
                val fillOff = activeLevel == ReasoningLevel.OFF && fraction <= 0.001f
                val activeWidth = if (fillOff) 0f else (edgeX + thumbPx / 2f).coerceAtMost(size.width)

                if (activeWidth > 0f) {
                    val fillSize = Size(activeWidth, size.height)
                    val mix = ultraMix

                    if (mix < 1f) {
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                listOf(EnergyBlue.copy(alpha = 0.9f), EnergyBlue),
                            ),
                            size = fillSize,
                            cornerRadius = radius,
                            alpha = 1f - mix,
                        )
                    }
                    if (mix > 0f) {
                        val period = 180.dp.toPx()
                        val shift = flowPhase * period
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = UltraFillColors,
                                startX = shift - period,
                                endX = shift + period,
                                tileMode = TileMode.Repeated,
                            ),
                            size = fillSize,
                            cornerRadius = radius,
                            alpha = mix,
                        )

                        // Drifting + twinkling stardust, clipped to the filled capsule.
                        clipRect(right = activeWidth) {
                            val twoPi = 2f * PI.toFloat()
                            for (p in DustField) {
                                val x = ((p.x0 + drift * p.drift) % 1f) * size.width
                                val tw = (sin(drift * twoPi * p.twinkle + p.phase) + 1f) * 0.5f
                                val alpha = (0.22f + 0.78f * tw) * mix
                                val r = p.rDp.dp.toPx() * (0.75f + 0.45f * tw)
                                drawCircle(
                                    color = EnergyStar,
                                    radius = r,
                                    center = Offset(x, p.y * size.height),
                                    alpha = alpha,
                                )
                            }
                        }
                    }

                    // Soft bloom at the leading edge — energy, not a hard cut.
                    val bloomColor = lerp(EnergyBlue, EnergyPurple, mix)
                    val bloomR = size.height * 1.75f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                bloomColor.copy(alpha = 0.32f + 0.38f * mix),
                                Color.Transparent,
                            ),
                            center = Offset(edgeX, size.height / 2f),
                            radius = bloomR,
                        ),
                        radius = bloomR,
                        center = Offset(edgeX, size.height / 2f),
                    )

                    // Top sheen.
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                            endY = size.height * 0.48f,
                        ),
                        size = Size(activeWidth, size.height * 0.48f),
                        cornerRadius = radius,
                    )
                }

                // Tick dots for intermediate stops (mockup cdot).
                if (levelCount > 2) {
                    for (i in 1 until levelCount - 1) {
                        val x = size.width * (i / denom)
                        val onFill = x <= activeWidth
                        drawCircle(
                            color = if (onFill) Color.White.copy(alpha = 0.85f)
                            else Color.White.copy(alpha = 0.22f),
                            radius = 2.5.dp.toPx(),
                            center = Offset(x, size.height / 2f),
                        )
                    }
                }

                drawRoundRect(
                    color = trackBorder,
                    cornerRadius = radius,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            val thumbOffset = (maxWidth - thumbSize) * animatedFraction
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(thumbSize)
                    .align(Alignment.CenterStart)
                    .drawBehind {
                        if (ultraMix > 0.01f) {
                            val r = size.minDimension * 1.05f
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        EnergyPurple.copy(alpha = 0.5f * ultraMix),
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            levels.forEachIndexed { index, level ->
                val selected = index == displayIndex.roundToInt()
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
