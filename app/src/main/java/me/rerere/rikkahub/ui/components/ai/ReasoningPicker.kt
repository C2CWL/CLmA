package me.rerere.rikkahub.ui.components.ai

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningHigh
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningLow
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningMedium
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private val levels = ReasoningLevel.entries
private val levelCount = levels.size

@Composable
fun ReasoningButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ReasoningPicker(
            reasoningLevel = reasoningLevel,
            onDismissRequest = { showPicker = false },
            onUpdateReasoningLevel = onUpdateReasoningLevel
        )
    }

    ToggleSurface(
        checked = reasoningLevel.isEnabled,
        onClick = { showPicker = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                ReasoningIcon(reasoningLevel)
            }
            if (!onlyIcon) Text(stringResource(R.string.setting_provider_page_reasoning))
        }
    }
}

@Composable
fun ReasoningPicker(
    reasoningLevel: ReasoningLevel,
    onDismissRequest: () -> Unit = {},
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    val currentIndex = levels.indexOf(reasoningLevel).coerceAtLeast(0)
    var sliderValue by remember { mutableFloatStateOf(currentIndex.toFloat()) }

    LaunchedEffect(currentIndex) {
        sliderValue = currentIndex.toFloat()
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 标题
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.reasoning_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.reasoning_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // 当前等级展示
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val iconColor by animateColorAsState(
                    if (reasoningLevel.isEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = when (reasoningLevel) {
                        ReasoningLevel.OFF -> HugeIcons.Idea
                        ReasoningLevel.AUTO -> HugeIcons.Idea01
                        ReasoningLevel.LOW -> ReasoningLow
                        ReasoningLevel.MEDIUM -> ReasoningMedium
                        ReasoningLevel.HIGH -> ReasoningHigh
                        ReasoningLevel.XHIGH -> ReasoningHigh
                        ReasoningLevel.MAX -> ReasoningHigh
                    },
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = iconColor,
                )
                ReasoningLevelWord(
                    level = reasoningLevel,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    val snappedIndex = sliderValue.roundToInt().coerceIn(0, levelCount - 1)
                    sliderValue = snappedIndex.toFloat()
                    onUpdateReasoningLevel(levels[snappedIndex])
                },
                valueRange = 0f..(levelCount - 1).toFloat(),
                steps = levelCount - 2,
                modifier = Modifier.fillMaxWidth(),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onPrimary)
                        )
                    }
                },
                track = { sliderState ->
                    ReasoningSurgeTrack(sliderState, reasoningLevel)
                }
            )
        }
    }
}

@Composable
internal fun ReasoningIcon(level: ReasoningLevel, modifier: Modifier = Modifier) {
    when (level) {
        ReasoningLevel.OFF -> Icon(HugeIcons.Idea, null, modifier = modifier)
        ReasoningLevel.AUTO -> Icon(HugeIcons.Idea01, null, modifier = modifier)
        ReasoningLevel.LOW -> Icon(ReasoningLow, null, modifier = modifier)
        ReasoningLevel.MEDIUM -> Icon(ReasoningMedium, null, modifier = modifier)
        ReasoningLevel.HIGH -> Icon(ReasoningHigh, null, modifier = modifier)
        ReasoningLevel.XHIGH -> Icon(ReasoningHigh, null, modifier = modifier)
        ReasoningLevel.MAX -> Icon(ReasoningHigh, null, modifier = modifier)
    }
}

// ── Thinking-depth surge visuals ─────────────────────────────────────────────
// 拍板 2026-10-17 (reverses the 2026-10-16 "hue only on the word" call): the surge itself
// carries the colour — a fixed cold→warm ramp that ignores the theme, ending in an animated
// rainbow at MAX — plus sparks that multiply and quicken with depth. The level word follows
// the bar: same fixed colour, or the same rainbow at MAX.

private val SurgeOffColor = Color(0xFF8B96A3)         // 关闭 / Off — static grey
private val SurgeAutoColor = Color(0xFF29B6F6)        // 自动 / Auto — ice blue
private val SurgeFastColor = Color(0xFF26C6DA)        // 快速 / Fast — cyan
private val SurgeBalancedColor = Color(0xFF66BB6A)    // 均衡 / Balanced — green
private val SurgeDeepColor = Color(0xFFFFB300)        // 沉思 / Deep — amber
private val SurgeResearchColor = Color(0xFFFB8C00)    // 研究 / Research — orange
private val SurgeMaxFallbackColor = Color(0xFFE53935) // 极致 / Max — single-colour fallback

/** Looped rainbow for MAX (first stop repeated at the end so TileMode.Repeated stays smooth). */
private val SurgeRainbow = listOf(
    Color(0xFFFF5252),
    Color(0xFFFF9800),
    Color(0xFFFFEB3B),
    Color(0xFF4CAF50),
    Color(0xFF2196F3),
    Color(0xFF9C27B0),
    Color(0xFFFF5252),
)

/**
 * The surge colour for [level]: a fixed cold→warm palette, theme-independent (拍板 2026-10-17).
 * MAX returns a hot red used only where a single [Color] is unavoidable — the bar and the
 * level word render the animated rainbow instead.
 */
internal fun reasoningLevelColor(level: ReasoningLevel): Color = when (level) {
    ReasoningLevel.OFF -> SurgeOffColor
    ReasoningLevel.AUTO -> SurgeAutoColor
    ReasoningLevel.LOW -> SurgeFastColor
    ReasoningLevel.MEDIUM -> SurgeBalancedColor
    ReasoningLevel.HIGH -> SurgeDeepColor
    ReasoningLevel.XHIGH -> SurgeResearchColor
    ReasoningLevel.MAX -> SurgeMaxFallbackColor
}

/** The bar's MAX rainbow, drifting slowly — shared by the level word so it follows the bar. */
@Composable
private fun reasoningSurgeRainbowBrush(label: String): Brush {
    val transition = rememberInfiniteTransition(label = label)
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "rainbow",
    )
    val period = with(LocalDensity.current) { 96.dp.toPx() }
    val shift = phase * period
    return Brush.linearGradient(
        colors = SurgeRainbow,
        start = Offset(shift - period, 0f),
        end = Offset(shift, 0f),
        tileMode = TileMode.Repeated,
    )
}

/**
 * The thinking-depth word, enlarged (拍板 2026-10-17) and coloured to follow the surge bar:
 * the fixed level colour, or the bar's animated rainbow at MAX.
 */
@Composable
internal fun ReasoningLevelWord(
    level: ReasoningLevel,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    if (level == ReasoningLevel.MAX) {
        Text(
            text = level.label(),
            modifier = modifier,
            style = style.copy(brush = reasoningSurgeRainbowBrush("reasoningWordRainbow")),
            fontWeight = FontWeight.Bold,
        )
    } else {
        val wordColor by animateColorAsState(reasoningLevelColor(level), label = "reasoningWord")
        Text(
            text = level.label(),
            modifier = modifier,
            style = style,
            color = wordColor,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** One spark rising off the active bar; fields are fixed, position derives from the clock. */
private data class SurgeParticle(
    val xFrac: Float,   // home position along the active bar, 0..1
    val offset: Float,  // clock phase offset, 0..1
    val rise: Float,    // rise-height multiplier, 0.55..1.2
    val radiusDp: Float,
    val sway: Float,    // horizontal wobble multiplier, -1..1
)

private fun sinf(x: Float): Float = sin(x.toDouble()).toFloat()

/**
 * The depth slider's track: inactive side in surfaceContainerHighest; the active side carries
 * the level's colour as a scrolling light band (period 3.4s at AUTO → 0.7s at MAX) over a
 * breathing halo, with sparks rising off the bar — more of them, faster, the deeper the level.
 * MAX swaps the band for a flowing rainbow. OFF is fully static. [level] is the previewed
 * level so the motion reacts while the finger is still on the thumb.
 */
@Composable
internal fun ReasoningSurgeTrack(sliderState: SliderState, level: ReasoningLevel) {
    val base = reasoningLevelColor(level)
    val inactive = MaterialTheme.colorScheme.surfaceContainerHighest
    val ordinal = levels.indexOf(level).coerceAtLeast(0)
    val intensity = ordinal / (levelCount - 1).toFloat()
    val animated = level != ReasoningLevel.OFF
    val isMax = level == ReasoningLevel.MAX
    val flowPeriodMs = (3400 - (2600 * intensity)).roundToInt().coerceAtLeast(700)
    val transition = rememberInfiniteTransition(label = "reasoningSurge")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(flowPeriodMs, easing = LinearEasing)),
        label = "flow",
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween((flowPeriodMs / 2).coerceAtLeast(350), easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    // Sparks: count (3→18) and rise speed (~2.3s→0.7s) both scale with depth.
    val particleCount = (intensity * 18f).roundToInt()
    val particlePeriodMs = (2300 - 1600 * intensity).roundToInt().coerceAtLeast(700)
    val particleClock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(particlePeriodMs, easing = LinearEasing)),
        label = "particles",
    )
    val particles = remember(ordinal) {
        List(particleCount) { i ->
            val rnd = kotlin.random.Random(i.toLong() * 31 + 17)
            SurgeParticle(
                xFrac = rnd.nextFloat(),
                offset = rnd.nextFloat(),
                rise = 0.55f + 0.65f * rnd.nextFloat(),
                radiusDp = 1.2f + 1.4f * rnd.nextFloat(),
                sway = rnd.nextFloat() * 2f - 1f,
            )
        }
    }
    val range = sliderState.valueRange.endInclusive - sliderState.valueRange.start
    val fraction =
        if (range > 0f) ((sliderState.value - sliderState.valueRange.start) / range).coerceIn(0f, 1f)
        else 0f

    Canvas(modifier = Modifier.fillMaxWidth().height(32.dp)) {
        val trackHeight = 8.dp.toPx()
        val y = (size.height - trackHeight) / 2f
        val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        drawRoundRect(
            color = inactive,
            topLeft = Offset(0f, y),
            size = Size(size.width, trackHeight),
            cornerRadius = radius,
        )
        val activeWidth = size.width * fraction
        if (activeWidth <= 0f) return@Canvas
        if (!animated) {
            drawRoundRect(
                color = base.copy(alpha = 0.6f),
                topLeft = Offset(0f, y),
                size = Size(activeWidth, trackHeight),
                cornerRadius = radius,
            )
            return@Canvas
        }
        // Flowing band: a repeated colour→dim→colour wave sliding along the track; a flowing
        // rainbow at MAX.
        val bandPeriod = (if (isMax) 200.dp else 140.dp).toPx()
        val shift = phase * bandPeriod
        val bandBrush = if (isMax) {
            Brush.linearGradient(
                colors = SurgeRainbow,
                start = Offset(shift - bandPeriod, 0f),
                end = Offset(shift, 0f),
                tileMode = TileMode.Repeated,
            )
        } else {
            Brush.linearGradient(
                colorStops = arrayOf(
                    0.0f to base,
                    0.45f to base.copy(alpha = 0.45f),
                    0.55f to base.copy(alpha = 0.45f),
                    1.0f to base,
                ),
                start = Offset(shift - bandPeriod, 0f),
                end = Offset(shift, 0f),
                tileMode = TileMode.Repeated,
            )
        }
        // Breathing halo behind the active side.
        val haloAlpha = 0.06f + 0.20f * intensity * pulse
        val haloTopLeft = Offset(0f, y - trackHeight * 0.75f)
        val haloSize = Size(activeWidth, trackHeight * 2.5f)
        val haloRadius = CornerRadius(trackHeight * 1.25f, trackHeight * 1.25f)
        if (isMax) {
            drawRoundRect(
                brush = bandBrush,
                topLeft = haloTopLeft,
                size = haloSize,
                cornerRadius = haloRadius,
                alpha = haloAlpha,
            )
        } else {
            drawRoundRect(
                color = base,
                topLeft = haloTopLeft,
                size = haloSize,
                cornerRadius = haloRadius,
                alpha = haloAlpha,
            )
        }
        drawRoundRect(
            brush = bandBrush,
            topLeft = Offset(0f, y),
            size = Size(activeWidth, trackHeight),
            cornerRadius = radius,
        )
        // Sparks rising off the bar, fading in then out over their rise.
        val riseMax = trackHeight * 2f
        val barCenterY = y + trackHeight / 2f
        val rainbowSwatches = SurgeRainbow.size - 1
        particles.forEachIndexed { i, p ->
            val t = (particleClock + p.offset) % 1f
            val px = activeWidth * p.xFrac +
                sinf(t * 2f * PI.toFloat() + i.toFloat()) * trackHeight * 0.5f * p.sway
            val py = barCenterY - t * riseMax * p.rise
            val alpha = sinf(t * PI.toFloat()) * (0.45f + 0.45f * intensity)
            drawCircle(
                color = if (isMax) SurgeRainbow[i % rainbowSwatches] else base,
                alpha = alpha,
                radius = p.radiusDp.dp.toPx(),
                center = Offset(px, py),
            )
        }
    }
}

@Composable
internal fun ReasoningLevel.label(): String = when (this) {
    ReasoningLevel.OFF -> stringResource(R.string.reasoning_off)
    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto)
    ReasoningLevel.LOW -> stringResource(R.string.reasoning_light)
    ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_medium)
    ReasoningLevel.HIGH -> stringResource(R.string.reasoning_heavy)
    ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_xhigh)
    ReasoningLevel.MAX -> stringResource(R.string.reasoning_max)
}

@Composable
@Preview(showBackground = true)
private fun ReasoningPickerPreview() {
    MaterialTheme {
        var level by remember { mutableStateOf(ReasoningLevel.AUTO) }
        ReasoningPicker(
            reasoningLevel = level,
            onUpdateReasoningLevel = { level = it }
        )
    }
}
