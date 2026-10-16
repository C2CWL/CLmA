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
import androidx.compose.ui.res.stringResource
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
import kotlin.math.roundToInt

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
                val wordColor by animateColorAsState(
                    reasoningLevelColor(reasoningLevel),
                    label = "reasoningWord",
                )
                Text(
                    text = reasoningLevel.label(),
                    style = MaterialTheme.typography.titleMedium,
                    color = wordColor,
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
// The per-level hue lives ONLY on the level word (拍板 2026-10-16). The slider itself keeps
// the theme primary and expresses depth through motion: a light band flows along the active
// track, scrolling faster and breathing harder the deeper the level. OFF is fully static.

private val ReasoningFastColor = Color(0xFF00B8D4)     // 快速 / Fast
private val ReasoningBalancedColor = Color(0xFF43A047) // 均衡 / Balanced
private val ReasoningDeepColor = Color(0xFF7E57C2)     // 沉思 / Deep
private val ReasoningResearchColor = Color(0xFFFB8C00) // 研究 / Research
private val ReasoningMaxColor = Color(0xFFE53935)      // 极致 / Max

/** The colour of the level word for [level]. Theme-derived for OFF/AUTO, fixed mid-tones else. */
@Composable
internal fun reasoningLevelColor(level: ReasoningLevel): Color = when (level) {
    ReasoningLevel.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
    ReasoningLevel.AUTO -> MaterialTheme.colorScheme.primary
    ReasoningLevel.LOW -> ReasoningFastColor
    ReasoningLevel.MEDIUM -> ReasoningBalancedColor
    ReasoningLevel.HIGH -> ReasoningDeepColor
    ReasoningLevel.XHIGH -> ReasoningResearchColor
    ReasoningLevel.MAX -> ReasoningMaxColor
}

/**
 * The depth slider's track: inactive side in surfaceContainerHighest, active side in primary
 * with a scrolling light band (infinite transition; period shrinks from 3.4s at AUTO to 0.7s
 * at MAX) plus a halo that breathes with an amplitude scaled by the level. [level] is the
 * previewed level so the motion reacts while the finger is still on the thumb.
 */
@Composable
internal fun ReasoningSurgeTrack(sliderState: SliderState, level: ReasoningLevel) {
    val base = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceContainerHighest
    val ordinal = levels.indexOf(level).coerceAtLeast(0)
    val intensity = ordinal / (levelCount - 1).toFloat()
    val animated = level != ReasoningLevel.OFF
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
    val range = sliderState.valueRange.endInclusive - sliderState.valueRange.start
    val fraction =
        if (range > 0f) ((sliderState.value - sliderState.valueRange.start) / range).coerceIn(0f, 1f)
        else 0f

    Canvas(modifier = Modifier.fillMaxWidth().height(16.dp)) {
        val trackHeight = 4.dp.toPx()
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
        // Breathing halo behind the active side.
        drawRoundRect(
            color = base.copy(alpha = 0.06f + 0.20f * intensity * pulse),
            topLeft = Offset(0f, y - trackHeight * 0.75f),
            size = Size(activeWidth, trackHeight * 2.5f),
            cornerRadius = CornerRadius(trackHeight * 1.25f, trackHeight * 1.25f),
        )
        // Flowing band: a repeated primary→dim→primary wave sliding along the track.
        val period = 140.dp.toPx()
        val shift = phase * period
        val brush = Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to base,
                0.45f to base.copy(alpha = 0.45f),
                0.55f to base.copy(alpha = 0.45f),
                1.0f to base,
            ),
            start = Offset(shift - period, 0f),
            end = Offset(shift, 0f),
            tileMode = TileMode.Repeated,
        )
        drawRoundRect(
            brush = brush,
            topLeft = Offset(0f, y),
            size = Size(activeWidth, trackHeight),
            cornerRadius = radius,
        )
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
