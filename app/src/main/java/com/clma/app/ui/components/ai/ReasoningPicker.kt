package com.clma.app.ui.components.ai

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import com.clma.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Atom02
import com.clma.app.R
import com.clma.app.ui.components.ui.ToggleSurface
import com.clma.app.ui.components.ui.icons.ReasoningHigh
import com.clma.app.ui.components.ui.icons.ReasoningLow
import com.clma.app.ui.components.ui.icons.ReasoningMedium
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
    ReasoningPickerButton(
        reasoningLevel = reasoningLevel,
        onUpdateReasoningLevel = onUpdateReasoningLevel,
        modifier = modifier,
        onlyIcon = onlyIcon,
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
    ToggleSurface(
        checked = reasoningLevel.isEnabled,
        onClick = { showPicker = true },
        modifier = if (onlyIcon) modifier.size(30.dp) else modifier,
        shape = if (onlyIcon) CircleShape else RoundedCornerShape(12.dp),
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
            if (showCodexSpeed) {
                CodexSpeedSelector(
                    serviceTier = codexServiceTier,
                    onUpdate = onUpdateCodexServiceTier,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

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
                        ReasoningLevel.OFF -> HugeIcons.Atom02
                        ReasoningLevel.AUTO -> HugeIcons.Atom02
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

            ReasoningSegmentedPicker(
                currentLevel = reasoningLevel,
                onSelectLevel = onUpdateReasoningLevel,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun ReasoningIcon(level: ReasoningLevel, modifier: Modifier = Modifier) {
    when (level) {
        ReasoningLevel.OFF -> Icon(HugeIcons.Atom02, null, modifier = modifier)
        ReasoningLevel.AUTO -> Icon(HugeIcons.Atom02, null, modifier = modifier)
        ReasoningLevel.LOW -> Icon(ReasoningLow, null, modifier = modifier)
        ReasoningLevel.MEDIUM -> Icon(ReasoningMedium, null, modifier = modifier)
        ReasoningLevel.HIGH -> Icon(ReasoningHigh, null, modifier = modifier)
        ReasoningLevel.XHIGH -> Icon(ReasoningHigh, null, modifier = modifier)
        ReasoningLevel.MAX -> Icon(ReasoningHigh, null, modifier = modifier)
    }
}

// ── Deep Cold / Nebula Glow Visuals (CodeX Style) ──────────────────────────
// 采用极简克制的科技深空冷调：量子青蓝、深空紫蓝与纯净微光白，告别粗暴红橙黄绿与彩虹
private val SurgeGlowBlue = Color(0xFF38BDF8)       // 量子青蓝 / Sky Cyan
private val SurgeGlowIndigo = Color(0xFF6366F1)     // 深空紫蓝 / Deep Indigo
private val SurgeGlowPurple = Color(0xFF8B5CF6)     // 极光星紫 / Aurora Violet
private val SurgeGlowLight = Color(0xFFE0E7FF)      // 微光冷白 / Starlight White
private val SurgeOffColor = Color(0xFF94A3B8)       // 关闭浅灰 / Slate Grey

/**
 * The surge colour for [level]: a modern, coherent deep-space cool palette.
 * Transitioning gracefully from subtle slate to cyan, deep indigo, and aurora violet.
 */
internal fun reasoningLevelColor(level: ReasoningLevel): Color = when (level) {
    ReasoningLevel.OFF -> SurgeOffColor
    ReasoningLevel.AUTO -> Color(0xFF38BDF8)     // 柔和天青
    ReasoningLevel.LOW -> Color(0xFF60A5FA)      // 纯净冰蓝
    ReasoningLevel.MEDIUM -> Color(0xFF818CF8)   // 浅靛蓝
    ReasoningLevel.HIGH -> Color(0xFF6366F1)     // 深空紫蓝
    ReasoningLevel.XHIGH -> Color(0xFF8B5CF6)    // 极光星紫
    ReasoningLevel.MAX -> Color(0xFFA855F7)      // 巅峰超新星紫
}

/** Looped smooth aurora nebula glow for MAX. */
private val AuroraNebulaColors = listOf(
    Color(0xFF38BDF8),
    Color(0xFF6366F1),
    Color(0xFF8B5CF6),
    Color(0xFFA855F7),
    Color(0xFF38BDF8),
)

/** The pill's MAX aurora nebula brush, drifting slowly and smoothly. */
@Composable
private fun reasoningAuroraNebulaBrush(label: String): Brush {
    val transition = rememberInfiniteTransition(label = label)
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "auroraPhase",
    )
    val period = with(LocalDensity.current) { 140.dp.toPx() }
    val shift = phase * period
    return Brush.linearGradient(
        colors = AuroraNebulaColors,
        start = Offset(shift - period, 0f),
        end = Offset(shift, 0f),
        tileMode = TileMode.Repeated,
    )
}

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
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
        label = "stardustClock",
    )

    val starlightColor = SurgeGlowLight
    val levelColor = reasoningLevelColor(level)

    Canvas(modifier = modifier.fillMaxSize()) {
        particles.forEach { p ->
            val angle = clock * p.speed + p.phaseOffset
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
    AnimatedContent(
        targetState = level,
        modifier = modifier,
        transitionSpec = {
            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (slideInVertically(animationSpec = tween(180)) { direction * it / 2 } + fadeIn(tween(180))) togetherWith
                (slideOutVertically(animationSpec = tween(180)) { -direction * it / 2 } + fadeOut(tween(120)))
        },
        label = "reasoningLevelWord",
    ) { currentLevel ->
        if (currentLevel == ReasoningLevel.MAX) {
            Text(
                text = currentLevel.label(),
                style = style.copy(brush = reasoningAuroraNebulaBrush("reasoningWordAurora")),
                fontWeight = FontWeight.Bold,
            )
        } else {
            val wordColor by animateColorAsState(reasoningLevelColor(currentLevel), label = "reasoningWord")
            Text(
                text = currentLevel.label(),
                style = style,
                color = wordColor,
                fontWeight = FontWeight.Bold,
            )
        }
    }
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
 * A segmented reasoning-level selector with an animated sliding pill indicator.
 * Supports both direct tap on any segment and continuous dragging across levels with spring snap.
 */
@Composable
internal fun ReasoningSegmentedPicker(
    currentLevel: ReasoningLevel,
    onSelectLevel: (ReasoningLevel) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp,
) {
    val haptic = LocalHapticFeedback.current
    val currentIndex = levels.indexOf(currentLevel).coerceAtLeast(0)
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val displayIndex = dragProgress ?: currentIndex.toFloat()

    val activeLevel = levels[displayIndex.roundToInt().coerceIn(0, levelCount - 1)]
    val isMax = activeLevel == ReasoningLevel.MAX
    val auroraBrush = reasoningAuroraNebulaBrush("reasoningSegmentAurora")
    val levelColor by animateColorAsState(
        targetValue = reasoningLevelColor(activeLevel),
        label = "reasoningSegmentColor",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val itemWidth = size.width / levelCount
                        val index = (offset.x / itemWidth).coerceIn(0f, (levelCount - 1).toFloat())
                        dragProgress = index
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val itemWidth = size.width / levelCount
                        val index = (change.position.x / itemWidth).coerceIn(0f, (levelCount - 1).toFloat())
                        val oldRound = dragProgress?.roundToInt() ?: -1
                        val newRound = index.roundToInt()
                        if (oldRound != newRound && oldRound != -1) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        dragProgress = index
                    },
                    onDragEnd = {
                        val targetIndex = (dragProgress ?: currentIndex.toFloat()).roundToInt().coerceIn(0, levelCount - 1)
                        dragProgress = null
                        if (levels[targetIndex] != currentLevel) {
                            onSelectLevel(levels[targetIndex])
                        }
                    },
                    onDragCancel = {
                        dragProgress = null
                    },
                )
            }
            .padding(4.dp),
    ) {
        val availableWidth = maxWidth
        val itemWidth = availableWidth / levelCount
        val targetOffset = itemWidth * displayIndex
        val animatedOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = if (dragProgress != null) tween(0)
            else spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "reasoningPillOffset",
        )

        // The sliding pill indicator with stardust glow particles
        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .width(itemWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (isMax) Modifier.background(auroraBrush)
                    else Modifier.background(levelColor.copy(alpha = 0.18f))
                )
                .border(
                    width = 1.dp,
                    brush = if (isMax) auroraBrush else SolidColor(levelColor.copy(alpha = 0.65f)),
                    shape = RoundedCornerShape(10.dp),
                )
        ) {
            StardustGlowOverlay(
                level = activeLevel,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Segment labels
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            levels.forEachIndexed { index, level ->
                val isSelected = index == displayIndex.roundToInt()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (level != currentLevel) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectLevel(level)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = level.label(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) {
                            if (isMax) Color.White else levelColor
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
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
                colors = AuroraNebulaColors,
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
        val rainbowSwatches = AuroraNebulaColors.size - 1
        particles.forEachIndexed { i, p ->
            val t = (particleClock + p.offset) % 1f
            val px = activeWidth * p.xFrac +
                sinf(t * 2f * PI.toFloat() + i.toFloat()) * trackHeight * 0.5f * p.sway
            val py = barCenterY - t * riseMax * p.rise
            val alpha = sinf(t * PI.toFloat()) * (0.45f + 0.45f * intensity)
            drawCircle(
                color = if (isMax) AuroraNebulaColors[i % rainbowSwatches] else base,
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
