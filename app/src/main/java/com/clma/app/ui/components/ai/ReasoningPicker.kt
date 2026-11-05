package com.clma.app.ui.components.ai

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp

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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.clma.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Atom02
import com.clma.app.R
import com.clma.app.ui.components.ui.ToggleSurface
import com.clma.app.ui.components.ui.LocalModernChatLayout
import com.clma.app.ui.components.ui.ModernSmallCorner
import com.clma.app.ui.components.ui.modernAccentSoft
import com.clma.app.data.datastore.ReasoningPickerStyle
import com.clma.app.data.datastore.Settings
import com.clma.app.ui.context.LocalSettings
import androidx.compose.runtime.CompositionLocalProvider
import me.rerere.hugeicons.stroke.Sparkles
import kotlin.math.PI
import kotlin.math.cos
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
    showCodexSpeed: Boolean = false,
    codexServiceTier: String? = null,
    onUpdateCodexServiceTier: (String?) -> Unit = {},
) {
    ReasoningPickerButton(
        reasoningLevel = reasoningLevel,
        onUpdateReasoningLevel = onUpdateReasoningLevel,
        modifier = modifier,
        onlyIcon = onlyIcon,
        showCodexSpeed = showCodexSpeed,
        codexServiceTier = codexServiceTier,
        onUpdateCodexServiceTier = onUpdateCodexServiceTier,
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
) {
    val style = LocalSettings.current.displaySetting.reasoningPickerStyle
    val isMax = reasoningLevel == ReasoningLevel.MAX
    val titleColor by animateColorAsState(
        targetValue = if (isMax) CodexUltraAccent else MaterialTheme.colorScheme.onSurface,
        label = "reasoningTitleTint",
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
                Text(
                    text = stringResource(
                        if (isMax) R.string.reasoning_picker_max_usage_hint
                        else R.string.reasoning_picker_hint,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isMax) CodexUltraAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isMax) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }

            when (style) {
                ReasoningPickerStyle.ENERGY_BAR -> {
                    // Compact badge for the active level — the fat capsule carries the interaction.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ReasoningIcon(
                            level = reasoningLevel,
                            modifier = Modifier.size(22.dp),
                        )
                        ReasoningLevelWord(
                            level = reasoningLevel,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    ReasoningEnergyBar(
                        currentLevel = reasoningLevel,
                        onSelectLevel = onUpdateReasoningLevel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ReasoningPickerStyle.STELLAR -> {
                    ReasoningStellarSheet(
                        currentLevel = reasoningLevel,
                        onSelectLevel = onUpdateReasoningLevel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

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
    val style = LocalSettings.current.displaySetting.reasoningPickerStyle
    val iconColor by animateColorAsState(
        if (level.isEnabled) reasoningLevelColor(level)
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "reasoningIconTint"
    )
    when (style) {
        ReasoningPickerStyle.STELLAR -> StellarMagnitudeIcon(
            level = level,
            tint = iconColor,
            modifier = modifier,
        )
        ReasoningPickerStyle.ENERGY_BAR -> when (level) {
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
private val SurgeGlowIndigo = Color(0xFF7166E6)
private val SurgeGlowPurple = Color(0xFFAC72FF)
private val SurgeGlowViolet = Color(0xFF9273F8)
private val SurgeGlowLight = Color(0xFFE8EAF0)
private val SurgeOffColor = Color(0xFF8B93A7)
/** Title / badge tint at 最大 — matches real Codex Ultra accent. */
internal val CodexUltraAccent = Color(0xFFB06DFF)

/**
 * Cool palette from slate → solid blue → violet. Used by the energy bar and stellar icons.
 */
internal fun reasoningLevelColor(level: ReasoningLevel): Color = when (level) {
    ReasoningLevel.OFF -> SurgeOffColor
    ReasoningLevel.AUTO -> Color(0xFF5865F2)
    ReasoningLevel.LOW -> SurgeGlowBlue
    ReasoningLevel.MEDIUM -> SurgeGlowBlue
    ReasoningLevel.HIGH -> Color(0xFF2F4FC5)
    ReasoningLevel.XHIGH -> SurgeGlowPurple
    ReasoningLevel.MAX -> CodexUltraAccent
}

/** Blue → violet fill used at 超高 / 最大. */
private val UltraGradientColors = listOf(
    Color(0xFF2B4CC2),
    Color(0xFFAC72FF),
    Color(0xFF9273F8),
    Color(0xFFB06DFF),
)

/** Stardust particle in the active pill indicator. */
private data class Stardust(
    val xFraction: Float,
    val yFraction: Float,
    val sizeDp: Float,
    val phaseOffset: Float,
    val speed: Float,
)

@Composable
private fun StardustGlowOverlay(
    level: ReasoningLevel,
    modifier: Modifier = Modifier,
) {
    if (level == ReasoningLevel.OFF) return
    val ordinal = levels.indexOf(level).coerceAtLeast(0)
    val count = (4 + ordinal * 2).coerceIn(4, 16)
    val particles = remember(ordinal) {
        val rnd = kotlin.random.Random(ordinal * 1013L + 73L)
        List(count) {
            Stardust(
                xFraction = rnd.nextFloat(),
                yFraction = 0.2f + 0.6f * rnd.nextFloat(),
                sizeDp = 1.0f + 1.2f * rnd.nextFloat(),
                phaseOffset = rnd.nextFloat() * 2f * PI.toFloat(),
                speed = 0.8f + 0.6f * rnd.nextFloat(),
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "stardust")
    val clock = transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
        label = "stardustClock",
    )

    val starlightColor = SurgeGlowLight
    val levelColor = reasoningLevelColor(level)

    Canvas(modifier = modifier.fillMaxSize()) {
        particles.forEach { p ->
            val angle = clock.value * p.speed + p.phaseOffset
            val alpha = (0.25f + 0.55f * ((sin(angle.toDouble()).toFloat() + 1f) / 2f)).coerceIn(0f, 1f)
            val swayX = sin((angle * 0.7).toDouble()).toFloat() * 4.dp.toPx()
            val swayY = sin((angle * 0.5).toDouble()).toFloat() * 2.dp.toPx()

            val cx = (size.width * p.xFraction + swayX).coerceIn(0f, size.width)
            val cy = (size.height * p.yFraction + swayY).coerceIn(0f, size.height)
            val radius = p.sizeDp.dp.toPx()

            drawCircle(
                color = if (level == ReasoningLevel.MAX) starlightColor.copy(alpha = alpha)
                else levelColor.copy(alpha = alpha * 0.85f),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
    }
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

/**
 * Codex-style fat purple energy capsule (~32dp track, white thumb ≈ bar height).
 * Snaps to the 7 reasoning levels; tap a label to jump. Low/mid fill solid blue;
 * 超高/最大 use a blue→violet gradient with animated stardust.
 */
@Composable
internal fun ReasoningEnergyBar(
    currentLevel: ReasoningLevel,
    onSelectLevel: (ReasoningLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val latestLevel by rememberUpdatedState(currentLevel)
    val latestOnSelect by rememberUpdatedState(onSelectLevel)
    val latestHaptic by rememberUpdatedState(haptic)
    val currentIndex = levels.indexOf(currentLevel).coerceAtLeast(0)
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val displayIndex = dragProgress ?: currentIndex.toFloat()
    val activeLevel = levels[displayIndex.roundToInt().coerceIn(0, levelCount - 1)]

    val targetFraction = (displayIndex / (levelCount - 1).toFloat()).coerceIn(0f, 1f)
    val animatedFraction = animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = if (dragProgress != null) tween(0)
        else spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "energyBarFraction",
    )

    val levelColor = reasoningLevelColor(activeLevel)
    val isUltra = activeLevel == ReasoningLevel.XHIGH || activeLevel == ReasoningLevel.MAX
    val isMax = activeLevel == ReasoningLevel.MAX
    val trackHeight = 32.dp
    val thumbSize = 32.dp

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
                        var committedLevel = latestLevel
                        fun commit(index: Int) {
                            val selected = levels[index.coerceIn(0, levelCount - 1)]
                            if (selected == committedLevel) return
                            committedLevel = selected
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
            val trackShape = RoundedCornerShape(percent = 50)
            // Track
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(trackShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        shape = trackShape,
                    ),
            ) {
                val transition = rememberInfiniteTransition(label = "energySurgeFlow")
                val flowPhase = transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
                    label = "flowWave",
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            val fraction = animatedFraction.value.coerceIn(0f, 1f)
                            val activeWidth = size.width * fraction
                            val radius = CornerRadius(size.height / 2f)
                            if (activeWidth > 0f) {
                                val brush = if (isUltra) {
                                    val period = 160.dp.toPx()
                                    val shift = flowPhase.value * period
                                    Brush.horizontalGradient(
                                        colors = UltraGradientColors,
                                        startX = shift - period,
                                        endX = shift + period,
                                        tileMode = TileMode.Repeated,
                                    )
                                } else {
                                    Brush.horizontalGradient(
                                        listOf(
                                            SurgeGlowBlue.copy(alpha = 0.85f),
                                            SurgeGlowBlue,
                                        ),
                                    )
                                }
                                drawRoundRect(brush, size = Size(activeWidth, size.height), cornerRadius = radius)
                                if (isUltra) {
                                    clipRect(right = activeWidth) { this@drawWithContent.drawContent() }
                                }
                                drawRoundRect(
                                    brush = Brush.verticalGradient(
                                        listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                                    ),
                                    size = Size(activeWidth, size.height * 0.42f),
                                    cornerRadius = radius,
                                )
                            }
                        },
                ) {
                    if (isUltra) {
                        StardustGlowOverlay(activeLevel)
                    }
                }
            }

            // White round thumb ≈ bar height, centred on the fill edge.
            val thumbOffset = (maxWidth - thumbSize) * animatedFraction.value
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(thumbSize)
                    .align(Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(Color.White)
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
                val isSelected = index == displayIndex.roundToInt()
                Text(
                    text = level.label(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) {
                        if (isMax) CodexUltraAccent else levelColor
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
