package com.clma.app.ui.pages.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.clma.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowDownDouble
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowUpDouble
import me.rerere.hugeicons.stroke.Cancel01

/** How long the collapsed trigger stays after scrolling stops. */
private const val JumperIdleHideMs = 2500L

/**
 * A first message scrolled further than this past the reading line counts as "being read", so
 * 上一条 first goes back to its own start instead of skipping it.
 */
private val PreviousSnapThreshold = 24.dp

/**
 * Item to scroll to for 上一条. Message groups are the first [messageCount] items of the chat
 * list (index = message), everything after them (system-prompt button, loading row, bottom
 * spacer) is not a message.
 *
 * [firstIndex] / [firstOffsetPx] are [LazyListState.firstVisibleItemIndex] /
 * [LazyListState.firstVisibleItemScrollOffset]. Returns null when there are no messages.
 */
internal fun previousMessageTarget(
    firstIndex: Int,
    firstOffsetPx: Int,
    messageCount: Int,
    thresholdPx: Int,
): Int? {
    if (messageCount <= 0) return null
    if (firstIndex >= messageCount) return messageCount - 1
    return if (firstOffsetPx > thresholdPx) firstIndex else (firstIndex - 1).coerceAtLeast(0)
}

/**
 * Item to scroll to for 下一条: the start of the message after the one at the reading line.
 * Returns null when there is no later message, meaning "go to the bottom".
 */
internal fun nextMessageTarget(firstIndex: Int, messageCount: Int): Int? {
    val next = firstIndex + 1
    return if (firstIndex >= 0 && next < messageCount) next else null
}

private enum class JumpAction { TOP, PREVIOUS, NEXT, BOTTOM }

/**
 * 消息快速跳转：右下角（设置为左侧时左下角）一个小圆钮，点开竖向菜单
 * 「最顶部 / 上一条 / 下一条 / 最底部」，选完自动收起。
 *
 * - 滚动停下后出现，收起状态下 [JumperIdleHideMs] 后自动隐藏；展开时不自动隐藏。
 * - 再点圆钮、点菜单外、开始滚动或按返回键都会收起。
 * - [enabled] 为 false（设置关闭 / 长截图中）时整体隐藏并收起。
 *
 * Placed bottom-aligned above the input bar so it stays clear of the edge bar (which defaults to
 * ~62% of the screen height on the edge) and of the bottom-centre suggestion chips.
 */
@Composable
internal fun BoxScope.MessageJumper(
    enabled: Boolean,
    onLeft: Boolean,
    state: LazyListState,
    messageCount: Int,
) {
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { PreviousSnapThreshold.roundToPx() }
    var expanded by remember { mutableStateOf(false) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    val available = enabled && messageCount > 0

    LaunchedEffect(scrolling, expanded) {
        if (scrolling) {
            recentlyScrolled = true
            expanded = false
        } else if (!expanded) {
            delay(JumperIdleHideMs)
            recentlyScrolled = false
        }
    }
    LaunchedEffect(available) {
        if (!available) expanded = false
    }
    BackHandler(enabled = expanded) { expanded = false }

    fun jump(action: JumpAction) {
        expanded = false
        scope.launch {
            val lastIndex = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            when (action) {
                JumpAction.TOP -> state.scrollToItem(0)
                JumpAction.PREVIOUS -> previousMessageTarget(
                    firstIndex = state.firstVisibleItemIndex,
                    firstOffsetPx = state.firstVisibleItemScrollOffset,
                    messageCount = messageCount,
                    thresholdPx = thresholdPx,
                )?.let { state.animateScrollToItem(it) }

                JumpAction.NEXT -> {
                    val target = nextMessageTarget(state.firstVisibleItemIndex, messageCount)
                    if (target != null) state.animateScrollToItem(target) else state.scrollToItem(lastIndex)
                }

                JumpAction.BOTTOM -> state.scrollToItem(lastIndex)
            }
        }
    }

    val visible = available && (expanded || (recentlyScrolled && !scrolling))

    // 菜单展开时，点菜单外任意处（列表区域）先收起菜单。
    if (visible && expanded) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        expanded = false
                    }
                }
        )
    }

    val origin = if (onLeft) TransformOrigin(0f, 1f) else TransformOrigin(1f, 1f)
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(if (onLeft) Alignment.BottomStart else Alignment.BottomEnd)
            .padding(
                start = if (onLeft) 20.dp else 0.dp,
                end = if (onLeft) 0.dp else 20.dp,
                bottom = 56.dp,
            ),
        enter = fadeIn() + scaleIn(initialScale = 0.6f, transformOrigin = origin),
        exit = fadeOut() + scaleOut(targetScale = 0.6f, transformOrigin = origin),
    ) {
        Column(
            horizontalAlignment = if (onLeft) Alignment.Start else Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .padding(vertical = 4.dp)
                    ) {
                        JumperMenuItem(HugeIcons.ArrowUpDouble, stringResource(R.string.chat_page_jump_to_top)) {
                            jump(JumpAction.TOP)
                        }
                        JumperMenuItem(HugeIcons.ArrowUp01, stringResource(R.string.chat_page_jump_previous_message)) {
                            jump(JumpAction.PREVIOUS)
                        }
                        JumperMenuItem(HugeIcons.ArrowDown01, stringResource(R.string.chat_page_jump_next_message)) {
                            jump(JumpAction.NEXT)
                        }
                        JumperMenuItem(HugeIcons.ArrowDownDouble, stringResource(R.string.chat_page_jump_to_bottom)) {
                            jump(JumpAction.BOTTOM)
                        }
                    }
                }
            }

            val triggerLabel = stringResource(
                if (expanded) R.string.chat_page_message_navigation_collapse else R.string.chat_page_message_navigation
            )
            Surface(
                onClick = { expanded = !expanded },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.96f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .size(40.dp)
                    .semantics { contentDescription = triggerLabel },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (expanded) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = HugeIcons.ArrowUp01,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(18.dp)
                                    .offset(y = 2.dp),
                            )
                            Icon(
                                imageVector = HugeIcons.ArrowDown01,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(18.dp)
                                    .offset(y = (-2).dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JumperMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
