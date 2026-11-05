package com.clma.app.ui.components.ai

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Dp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.clma.ai.core.ReasoningLevel
import com.clma.app.data.datastore.findProvider
import com.clma.ai.provider.nearestSupportedReasoningLevel
import com.clma.ai.provider.supportedReasoningLevels
import com.clma.ai.provider.Model
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Atom02
import com.clma.app.R
import com.clma.app.ui.components.ui.ToggleSurface
import com.clma.app.ui.components.ui.LocalModernChatLayout
import com.clma.app.ui.components.ui.ModernSmallCorner
import com.clma.app.ui.components.ui.modernAccentSoft
import com.clma.app.data.datastore.Settings
import com.clma.app.ui.context.LocalSettings
import androidx.compose.runtime.CompositionLocalProvider
import me.rerere.hugeicons.stroke.Sparkles
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun ReasoningButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
    showCodexSpeed: Boolean = false,
    codexServiceTier: String? = null,
    onUpdateCodexServiceTier: (String?) -> Unit = {},
    /** When null (unknown model), the picker shows every [ReasoningLevel]. */
    model: Model? = null,
    supportedLevels: List<ReasoningLevel>? = null,
) {
    val settings = LocalSettings.current
    val derivedLevels = remember(model?.id, model?.modelId, settings.providers) {
        if (model == null) {
            ReasoningLevel.entries.toList()
        } else {
            supportedReasoningLevels(model.findProvider(settings.providers), model)
        }
    }
    val levels = supportedLevels ?: derivedLevels
    ReasoningPickerButton(
        reasoningLevel = reasoningLevel,
        onUpdateReasoningLevel = onUpdateReasoningLevel,
        modifier = modifier,
        onlyIcon = onlyIcon,
        showCodexSpeed = showCodexSpeed,
        codexServiceTier = codexServiceTier,
        onUpdateCodexServiceTier = onUpdateCodexServiceTier,
        supportedLevels = levels,
    )
}

/**
 * An action pill button placed on the chat input bar to quickly inspect and tune thinking depth.
 */
@Composable
fun ReasoningPickerButton(
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = true,
    showCodexSpeed: Boolean = false,
    codexServiceTier: String? = null,
    onUpdateCodexServiceTier: (String?) -> Unit = {},
    supportedLevels: List<ReasoningLevel> = ReasoningLevel.entries,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ReasoningPicker(
            reasoningLevel = reasoningLevel,
            onDismissRequest = { showPicker = false },
            onUpdateReasoningLevel = onUpdateReasoningLevel,
            showCodexSpeed = showCodexSpeed,
            codexServiceTier = codexServiceTier,
            onUpdateCodexServiceTier = onUpdateCodexServiceTier,
            supportedLevels = supportedLevels,
        )
    }

    val activeColor = reasoningLevelColor(reasoningLevel)
    // 现代布局：输入框工具栏里的按钮是 8dp 小方块，开启时铺一层浅主题色。
    val modern = LocalModernChatLayout.current
    ToggleSurface(
        checked = reasoningLevel.isEnabled,
        onClick = { showPicker = true },
        modifier = if (onlyIcon) modifier.size(if (modern) 32.dp else 30.dp) else modifier,
        shape = when {
            onlyIcon && modern -> RoundedCornerShape(ModernSmallCorner)
            onlyIcon -> CircleShape
            else -> RoundedCornerShape(12.dp)
        },
        checkedColor = if (modern) modernAccentSoft() else Color.Transparent,
    ) {
        if (onlyIcon) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ReasoningIcon(
                    level = reasoningLevel,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ReasoningIcon(reasoningLevel)
                }
                Text(stringResource(R.string.setting_provider_page_reasoning))
            }
        }
    }
}

@Composable
fun ReasoningPicker(
    reasoningLevel: ReasoningLevel,
    onDismissRequest: () -> Unit = {},
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
    showCodexSpeed: Boolean = false,
    codexServiceTier: String? = null,
    onUpdateCodexServiceTier: (String?) -> Unit = {},
    supportedLevels: List<ReasoningLevel> = ReasoningLevel.entries,
) {
    val levels = remember(supportedLevels) {
        supportedLevels.ifEmpty { ReasoningLevel.entries.toList() }
    }
    val displayLevel = nearestSupportedReasoningLevel(reasoningLevel, levels)
    val isMax = displayLevel == ReasoningLevel.MAX
    val titleColor by animateColorAsState(
        targetValue = if (isMax) CodexUltraAccent else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(250),
        label = "reasoningTitleTint",
    )
    val hintColor by animateColorAsState(
        targetValue = if (isMax) CodexUltraAccent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(250),
        label = "reasoningHintTint",
    )

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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.reasoning_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = titleColor,
                    fontWeight = FontWeight.SemiBold,
                )
                AnimatedContent(
                    targetState = isMax,
                    transitionSpec = {
                        fadeIn(tween(250)) togetherWith fadeOut(tween(200))
                    },
                    label = "reasoningHint",
                ) { max ->
                    Text(
                        text = stringResource(
                            if (max) R.string.reasoning_picker_max_usage_hint
                            else R.string.reasoning_picker_hint,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = hintColor,
                        fontWeight = if (max) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Compact badge for the active level — the fat capsule carries the interaction.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReasoningIcon(
                    level = displayLevel,
                    modifier = Modifier.size(22.dp),
                )
                ReasoningLevelWord(
                    level = displayLevel,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            ReasoningEnergyBar(
                currentLevel = displayLevel,
                onSelectLevel = onUpdateReasoningLevel,
                levels = levels,
                modifier = Modifier.fillMaxWidth(),
            )

            // Codex 标准/快速：only when the model supports speed tiers; under the bar to match v7.
            if (showCodexSpeed) {
                CodexSpeedSelector(
                    serviceTier = codexServiceTier,
                    onUpdate = onUpdateCodexServiceTier,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun ReasoningIcon(level: ReasoningLevel, modifier: Modifier = Modifier) {
    val iconColor by animateColorAsState(
        if (level.isEnabled) reasoningLevelColor(level)
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "reasoningIconTint"
    )
    when (level) {
        ReasoningLevel.AUTO -> Icon(HugeIcons.Atom02, contentDescription = null, modifier = modifier, tint = iconColor)
        ReasoningLevel.MAX, ReasoningLevel.XHIGH -> Icon(
            HugeIcons.Sparkles,
            contentDescription = null,
            modifier = modifier,
            tint = iconColor,
        )
        else -> DepthMark(level = level, tint = iconColor, modifier = modifier)
    }
}

/** Orbital mark in the launcher's language. More rings and satellites mean a deeper level. */
@Composable
private fun DepthMark(level: ReasoningLevel, tint: Color, modifier: Modifier = Modifier) {
    val rings = when (level) {
        ReasoningLevel.XHIGH, ReasoningLevel.MAX -> 2
        else -> 1
    }
    val satellites = when (level) {
        ReasoningLevel.OFF, ReasoningLevel.AUTO -> 0
        ReasoningLevel.LOW -> 0
        ReasoningLevel.MEDIUM -> 1
        ReasoningLevel.HIGH -> 2
        ReasoningLevel.XHIGH -> 2
        ReasoningLevel.MAX -> 3
    }
    val core = level != ReasoningLevel.OFF
    Canvas(modifier) {
        val strokePx = 1.6.dp.toPx()
        val c = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f - strokePx
        val arc = Stroke(width = strokePx, cap = StrokeCap.Round)
        drawArc(
            color = tint,
            startAngle = 128f,
            sweepAngle = 284f,
            useCenter = false,
            topLeft = Offset(c.x - radius, c.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = arc,
        )
        if (rings >= 2) {
            val inner = radius * 0.62f
            drawArc(
                color = tint.copy(alpha = 0.8f),
                startAngle = 210f,
                sweepAngle = 250f,
                useCenter = false,
                topLeft = Offset(c.x - inner, c.y - inner),
                size = Size(inner * 2f, inner * 2f),
                style = arc,
            )
        }
        if (core) {
            drawCircle(color = tint, radius = strokePx * 0.85f, center = c)
        }
        repeat(satellites) { index ->
            val angle = Math.toRadians(200.0 + index * 52.0)
            val orbit = radius.toDouble()
            drawCircle(
                color = tint,
                radius = strokePx * 0.72f,
                center = Offset(
                    c.x + (cos(angle) * orbit).toFloat(),
                    c.y + (sin(angle) * orbit).toFloat(),
                ),
            )
        }
    }
}

// ── Codex Ultra cool energy palette (no yellow / amber / orange / gold) ─────
private val SurgeGlowBlue = Color(0xFF2B4CC2)       // solid low/mid fill
private val SurgeGlowPurple = Color(0xFFAC72FF)
private val SurgeOffColor = Color(0xFF8B93A7)
/** Title / badge tint at 最大 — matches real Codex Ultra accent. */
internal val CodexUltraAccent = Color(0xFFB06DFF)

/**
 * Cool palette from slate → solid blue → violet. Used by the energy bar and icons.
 */
internal fun reasoningLevelColor(level: ReasoningLevel): Color = when (level) {
    ReasoningLevel.OFF -> SurgeOffColor
    ReasoningLevel.AUTO -> Color(0xFF5865F2)
    ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH -> SurgeGlowBlue
    ReasoningLevel.XHIGH -> SurgeGlowPurple
    ReasoningLevel.MAX -> CodexUltraAccent
}

/** Immediate label updates avoid stacking outgoing words during rapid level changes. */
@Composable
internal fun ReasoningLevelWord(
    level: ReasoningLevel,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Text(
        text = level.label(),
        style = style,
        modifier = modifier,
        color = reasoningLevelColor(level),
        fontWeight = FontWeight.Bold,
    )
}


/**
 * A segmented control with a smoothly animated sliding pill indicator.
 */
@Composable
internal fun <T> SlidingSegmentedControl(
    items: List<T>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    indicatorColor: Color = MaterialTheme.colorScheme.surface,
    indicatorBrush: Brush? = null,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    indicatorShape: RoundedCornerShape = RoundedCornerShape(9.dp),
    contentPadding: Dp = 4.dp,
    label: @Composable (item: T, isSelected: Boolean) -> Unit,
) {
    val count = items.size.coerceAtLeast(1)
    val safeIndex = selectedIndex.coerceIn(0, count - 1)

    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .padding(contentPadding),
    ) {
        val availableWidth = maxWidth
        val itemWidth = availableWidth / count
        val targetOffset = itemWidth * safeIndex
        val animatedOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "slidingIndicatorOffset",
        )

        // The sliding pill indicator
        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .width(itemWidth)
                .fillMaxHeight()
                .clip(indicatorShape)
                .then(
                    if (indicatorBrush != null) Modifier.background(indicatorBrush)
                    else Modifier.background(indicatorColor)
                )
        )

        // Clickable item labels
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == safeIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (index != safeIndex) {
                                onSelectIndex(index)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    label(item, isSelected)
                }
            }
        }
    }
}

@Composable
internal fun CodexSpeedSelector(
    serviceTier: String?,
    onUpdate: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val speedOptions = listOf(
        stringResource(R.string.codex_speed_standard) to null,
        stringResource(R.string.codex_speed_fast) to "fast",
    )
    val selectedIndex = if (serviceTier == "fast") 1 else 0

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.codex_speed_title),
            style = MaterialTheme.typography.titleMedium,
        )
        SlidingSegmentedControl(
            items = speedOptions,
            selectedIndex = selectedIndex,
            onSelectIndex = { index -> onUpdate(speedOptions[index].second) },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            indicatorColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            indicatorShape = RoundedCornerShape(9.dp),
            contentPadding = 4.dp,
        ) { item, isSelected ->
            val contentColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "codexSpeedTextColor",
            )
            Text(
                text = item.first,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = stringResource(
                if (serviceTier == "fast") R.string.codex_speed_fast_hint
                else R.string.codex_speed_standard_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ReasoningLevel.label(): String = when (this) {
    ReasoningLevel.OFF -> stringResource(R.string.reasoning_picker_level_off)
    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_picker_level_auto)
    ReasoningLevel.LOW -> stringResource(R.string.reasoning_picker_level_low)
    ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_picker_level_medium)
    ReasoningLevel.HIGH -> stringResource(R.string.reasoning_picker_level_high)
    ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_picker_level_xhigh)
    ReasoningLevel.MAX -> stringResource(R.string.reasoning_picker_level_max)
}

@Composable
@Preview(showBackground = true)
private fun ReasoningPickerPreview() {
    MaterialTheme {
        CompositionLocalProvider(LocalSettings provides Settings()) {
            var level by remember { mutableStateOf(ReasoningLevel.AUTO) }
            ReasoningPicker(
                reasoningLevel = level,
                onUpdateReasoningLevel = { level = it }
            )
        }
    }
}
