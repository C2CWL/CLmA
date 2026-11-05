package com.clma.app.ui.components.ai

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.clma.ai.core.ReasoningLevel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Cool starfield palette — cyan → indigo → violet; no yellow / amber / gold. */
private val StellarVoid = Color(0xFF080A10)
private val StellarCyan = Color(0xFF22D3EE)
private val StellarSky = Color(0xFF38BDF8)
private val StellarIndigo = Color(0xFF818CF8)
private val StellarViolet = Color(0xFFA78BFA)
private val StellarStarlight = Color(0xFFC7D2FE)
private val StellarMuted = Color(0xFF8B93A7)

private data class FieldStar(
    val x: Float,
    val y: Float,
    val radius: Float,
    val twinkle: Float,
    val phase: Float,
)

/**
 * v5 Stellar picker body: starfield stage, central star that grows from dim → nova with level,
 * and a row of magnitude star icons for discrete selection.
 */
@Composable
internal fun ReasoningStellarSheet(
    currentLevel: ReasoningLevel,
    onSelectLevel: (ReasoningLevel) -> Unit,
    levels: List<ReasoningLevel> = ReasoningLevel.entries,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val ordinal = levels.indexOf(currentLevel).let { if (it >= 0) it else 0 }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(StellarVoid)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            StellarStarfield(levelOrdinal = ordinal)
            StellarCentralStar(level = currentLevel)
        }

        ReasoningLevelWord(
            level = currentLevel,
            style = MaterialTheme.typography.headlineSmall,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            levels.forEach { level ->
                val selected = level == currentLevel
                val tint by animateColorAsState(
                    targetValue = if (selected) reasoningLevelColor(level) else StellarMuted.copy(alpha = 0.55f),
                    label = "stellarMagTint",
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
                ) {
                    StellarMagnitudeIcon(
                        level = level,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = level.label(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = tint,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun StellarMagnitudeIcon(
    level: ReasoningLevel,
    modifier: Modifier = Modifier,
    tint: Color = reasoningLevelColor(level),
) {
    val points = when (level) {
        ReasoningLevel.OFF -> 0
        ReasoningLevel.AUTO -> 4
        ReasoningLevel.LOW -> 4
        ReasoningLevel.MEDIUM -> 5
        ReasoningLevel.HIGH -> 5
        ReasoningLevel.XHIGH -> 6
        ReasoningLevel.MAX -> 8
    }
    val filled = level != ReasoningLevel.OFF
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        if (points == 0) {
            drawCircle(
                color = tint.copy(alpha = 0.45f),
                radius = r * 0.28f,
                center = c,
                style = Stroke(width = 1.6.dp.toPx()),
            )
            return@Canvas
        }
        val path = starPath(c, r * 0.92f, r * 0.42f, points)
        if (filled) {
            drawPath(path, color = tint.copy(alpha = 0.92f))
            if (level == ReasoningLevel.MAX || level == ReasoningLevel.XHIGH) {
                drawCircle(color = tint.copy(alpha = 0.22f), radius = r * 0.95f, center = c)
            }
        } else {
            drawPath(path, color = tint, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

@Composable
private fun StellarStarfield(levelOrdinal: Int) {
    val stars = remember(levelOrdinal) {
        val rnd = kotlin.random.Random(levelOrdinal * 911L + 17L)
        val count = 28 + levelOrdinal * 6
        List(count) {
            FieldStar(
                x = rnd.nextFloat(),
                y = rnd.nextFloat(),
                radius = 0.6f + rnd.nextFloat() * 1.6f,
                twinkle = 0.5f + rnd.nextFloat(),
                phase = rnd.nextFloat() * 2f * PI.toFloat(),
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "stellarField")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "fieldClock",
    )
    Canvas(Modifier.fillMaxSize()) {
        stars.forEach { s ->
            val a = (0.18f + 0.55f * ((sin((clock * s.twinkle + s.phase).toDouble()).toFloat() + 1f) / 2f))
                .coerceIn(0f, 1f)
            drawCircle(
                color = StellarStarlight.copy(alpha = a),
                radius = s.radius.dp.toPx(),
                center = Offset(size.width * s.x, size.height * s.y),
            )
        }
    }
}

@Composable
private fun StellarCentralStar(level: ReasoningLevel) {
    val transition = rememberInfiniteTransition(label = "centralStar")
    val pulse by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "novaPulse",
    )
    val coreColor = reasoningLevelColor(level)
    val intensity = when (level) {
        ReasoningLevel.OFF -> 0.15f
        ReasoningLevel.AUTO -> 0.35f
        ReasoningLevel.LOW -> 0.45f
        ReasoningLevel.MEDIUM -> 0.6f
        ReasoningLevel.HIGH -> 0.75f
        ReasoningLevel.XHIGH -> 0.9f
        ReasoningLevel.MAX -> 1f
    }
    Canvas(Modifier.size(96.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val base = size.minDimension * 0.18f * (0.55f + 0.45f * intensity) * pulse
        // Soft nebula halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    coreColor.copy(alpha = 0.35f * intensity),
                    StellarIndigo.copy(alpha = 0.12f * intensity),
                    Color.Transparent,
                ),
                center = c,
                radius = base * 3.2f,
            ),
            radius = base * 3.2f,
            center = c,
        )
        if (level == ReasoningLevel.MAX || level == ReasoningLevel.XHIGH) {
            // Nova rays
            val rays = if (level == ReasoningLevel.MAX) 10 else 6
            repeat(rays) { i ->
                val ang = (i * (2.0 * PI / rays)).toFloat()
                val len = base * (2.4f + 0.6f * intensity) * pulse
                drawLine(
                    color = StellarCyan.copy(alpha = 0.35f * intensity),
                    start = c,
                    end = Offset(c.x + cos(ang) * len, c.y + sin(ang) * len),
                    strokeWidth = 1.4.dp.toPx(),
                )
            }
        }
        drawCircle(color = coreColor.copy(alpha = 0.95f), radius = base, center = c)
        drawCircle(color = Color.White.copy(alpha = 0.85f), radius = base * 0.38f, center = c)
    }
}

private fun starPath(center: Offset, outer: Float, inner: Float, points: Int): Path {
    val path = Path()
    val step = PI / points
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) outer else inner
        val a = -PI / 2 + i * step
        val x = center.x + (cos(a) * r).toFloat()
        val y = center.y + (sin(a) * r).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
