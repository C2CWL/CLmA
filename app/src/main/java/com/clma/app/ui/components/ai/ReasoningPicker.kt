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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
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
import androidx.compose.runtime.rememberUpdatedState
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
import me.rerere.hugeicons.stroke.SignalFull02
import me.rerere.hugeicons.stroke.SignalHigh
import me.rerere.hugeicons.stroke.SignalLow02
import me.rerere.hugeicons.stroke.SignalLowMedium
import me.rerere.hugeicons.stroke.SignalMedium02
import me.rerere.hugeicons.stroke.SignalNo02
import com.clma.app.R
import com.clma.app.ui.components.ui.ToggleSurface
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
                ReasoningIcon(
                    level = reasoningLevel,
                    modifier = Modifier.size(32.dp),
                )
                ReasoningLevelWord(
                    level = reasoningLevel,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            ReasoningEnergyBar(
                currentLevel = reasoningLevel,
                onSelectLevel = onUpdateReasoningLevel,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
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
        ReasoningLevel.OFF -> Icon(HugeIcons.SignalNo02, contentDescription = null, modifier = modifier, tint = iconColor)
        ReasoningLevel.AUTO -> Icon(HugeIcons.Atom02, contentDescription = null, modifier = modifier, tint = iconColor)
        ReasoningLevel.LOW -> Icon(HugeIcons.SignalLow02, contentDescription = null, modifier = modifier, tint = iconColor)
        ReasoningLevel.MEDIUM -> Icon(HugeIcons.SignalLowMedium, contentDescription = null, modifier = modifier, tint = iconColor)
        ReasoningLevel.HIGH -> Icon(HugeIcons.SignalMedium02, contentDescription = null, modifier = modifier, tint = iconColor)
        ReasoningLevel.XHIGH -> Icon(HugeIcons.SignalHigh, contentDescription = null, modifier = modifier, tint = iconColor)
        ReasoningLevel.MAX -> Icon(HugeIcons.SignalFull02, contentDescription = null, modifier = modifier, tint = iconColor)
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
 * A sci-fi energy bar for thinking depth (能量条).
 * Features an animated glowing energy surge channel with deep-space aurora gradients,
 * floating stardust particles, smooth spring-animated seeking, and horizontal drag / discrete tap.
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
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = if (dragProgress != null) tween(0)
        else spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "energyBarFraction",
    )

    val levelColor by animateColorAsState(
        targetValue = reasoningLevelColor(activeLevel),
        label = "energyColor",
    )
    val isMax = activeLevel == ReasoningLevel.MAX
    val auroraBrush = reasoningAuroraNebulaBrush("energyBarAurora")

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The Energy Bar Capsule
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(18.dp),
                )
                .pointerInput(levelCount) {
                    // Claim the pointer on the Initial pass so the bottom sheet's vertical
                    // drag cannot cancel a slightly diagonal swipe, and a tap (no slop) seeks.
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
                        var lastRound = progress.roundToInt()
                        dragProgress = progress
                        val pointerId = down.id
                        var committed = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
                                if (!change.pressed) {
                                    change.consume()
                                    if (change.changedToUp()) {
                                        val target = progress.roundToInt().coerceIn(0, levelCount - 1)
                                        val selected = levels[target]
                                        committed = true
                                        dragProgress = null
                                        if (selected != latestLevel) {
                                            latestHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            latestOnSelect(selected)
                                        }
                                    }
                                    break
                                }
                                change.consume()
                                progress = indexAt(change.position.x)
                                val newRound = progress.roundToInt()
                                if (newRound != lastRound) {
                                    latestHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    lastRound = newRound
                                }
                                dragProgress = progress
                            }
                        } finally {
                            if (!committed) dragProgress = null
                        }
                    }
                },
        ) {
            val totalWidth = maxWidth
            val activeWidth = totalWidth * animatedFraction

            // Active glowing energy surge with flowing wave animation
            val transition = rememberInfiniteTransition(label = "energySurgeFlow")
            val flowPhase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
                label = "flowWave",
            )
            val crestPulse by transition.animateFloat(
                initialValue = 0.6f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(1000, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse
                ),
                label = "crestPulse",
            )

            if (activeWidth.value > 0f) {
                val flowShift = flowPhase * 160.dp.value
                val streamBrush = if (isMax) auroraBrush
                else Brush.horizontalGradient(
                    colors = listOf(
                        SurgeGlowBlue.copy(alpha = 0.45f),
                        levelColor.copy(alpha = 0.75f),
                        SurgeGlowIndigo.copy(alpha = 0.85f),
                        levelColor,
                    ),
                    startX = flowShift - 160.dp.value,
                    endX = flowShift + 160.dp.value,
                    tileMode = TileMode.Repeated,
                )

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(activeWidth)
                        .clip(RoundedCornerShape(18.dp))
                        .background(streamBrush)
                ) {
                    StardustGlowOverlay(
                        level = activeLevel,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Bright energy crest at the leading edge with breathing pulse
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 2.dp)
                            .width(3.5.dp)
                            .fillMaxHeight(0.7f)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = crestPulse * 0.9f))
                    )
                }
            }

            // Ticks across the track
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                levels.forEachIndexed { index, level ->
                    val covered = index <= displayIndex.roundToInt() && animatedFraction > 0.05f
                    Box(
                        modifier = Modifier
                            .size(if (index == displayIndex.roundToInt()) 7.dp else 4.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    index == displayIndex.roundToInt() -> Color.White
                                    covered -> Color.White.copy(alpha = 0.65f)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                                }
                            )
                    )
                }
            }
        }

        // Discrete labels below the energy bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            levels.forEachIndexed { index, level ->
                val isSelected = index == displayIndex.roundToInt()
                Text(
                    text = level.label(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) {
                        if (isMax) SurgeGlowPurple else levelColor
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (level != currentLevel) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectLevel(level)
                            }
                        }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                )
            }
        }
    }
}

@Composable
internal fun ReasoningLevel.label(): String = when (this) {
    ReasoningLevel.OFF -> stringResource(R.string.reasoning_off)
    ReasoningLevel.AUTO -> "自动"
    ReasoningLevel.LOW -> "Low"
    ReasoningLevel.MEDIUM -> "Med"
    ReasoningLevel.HIGH -> "High"
    ReasoningLevel.XHIGH -> "XHigh"
    ReasoningLevel.MAX -> "Max"
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
