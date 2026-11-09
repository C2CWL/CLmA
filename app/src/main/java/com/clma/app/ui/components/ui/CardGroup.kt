package com.clma.app.ui.components.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import com.clma.app.ui.motion.Motion
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import com.clma.app.ui.theme.CustomColors

private val CardGroupCorner = 20.dp
private val CardGroupItemSpacing = 0.dp
private val CardGroupInnerCorner = 0.dp

private data class CardGroupItem(
    val onClick: (() -> Unit)?,
    val modifier: Modifier,
    val overlineContent: (@Composable () -> Unit)?,
    val headlineContent: @Composable () -> Unit,
    val supportingContent: (@Composable () -> Unit)?,
    val leadingContent: (@Composable () -> Unit)?,
    val trailingContent: (@Composable () -> Unit)?,
    val colors: ListItemColors?,
    /** null = 普通常驻项（不包动画，省开销）；true/false = 条件子项，带显隐动画。 */
    val animatedVisible: Boolean?,
) {
    val visible: Boolean get() = animatedVisible != false
}

@DslMarker
private annotation class CardGroupDsl

@CardGroupDsl
interface CardGroupScope {
    fun item(
        onClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        overlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        colors: ListItemColors? = null,
        /**
         * 由开关带出的子项：传条件而不是包 `if`，这样出现 / 消失时会平滑长高、收起，
         * 而不是突然冒出来。不传（null）就是普通常驻项。
         */
        visible: Boolean? = null,
        headlineContent: @Composable () -> Unit,
    )
}

private class CardGroupScopeImpl : CardGroupScope {
    val items = mutableListOf<CardGroupItem>()

    override fun item(
        onClick: (() -> Unit)?,
        modifier: Modifier,
        overlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        colors: ListItemColors?,
        visible: Boolean?,
        headlineContent: @Composable () -> Unit,
    ) {
        items.add(
            CardGroupItem(
                onClick = onClick,
                modifier = modifier,
                overlineContent = overlineContent,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = colors,
                animatedVisible = visible,
            )
        )
    }
}

@Composable
private fun CardGroupListItem(
    item: CardGroupItem,
    count: Int,
    index: Int,
) {
    val isFirst = index == 0
    val isLast = index == count - 1

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val topCorner by animateDpAsState(
        targetValue = if (isPressed || count == 1 || isFirst) CardGroupCorner else CardGroupInnerCorner,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    val bottomCorner by animateDpAsState(
        targetValue = if (isPressed || count == 1 || isLast) CardGroupCorner else CardGroupInnerCorner,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )

    ListItem(
        headlineContent = item.headlineContent,
        modifier = item.modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    // Spatial springs can overshoot below zero when a pressed corner returns to flat.
                    topStart = topCorner.coerceAtLeast(0.dp),
                    topEnd = topCorner.coerceAtLeast(0.dp),
                    bottomStart = bottomCorner.coerceAtLeast(0.dp),
                    bottomEnd = bottomCorner.coerceAtLeast(0.dp),
                )
            )
            .then(
                if (item.onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = item.onClick,
                    )
                } else Modifier
            ),
        overlineContent = item.overlineContent,
        supportingContent = item.supportingContent,
        leadingContent = item.leadingContent,
        trailingContent = item.trailingContent,
        colors = item.colors ?: CustomColors.listItemColors,
    )
}

@Composable
fun CardGroup(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    content: CardGroupScope.() -> Unit,
) {
    // Cache the scope across recompositions instead of allocating a fresh CardGroupScopeImpl
    // + N CardGroupItem records every time the parent recomposes. On dense pages (the
    // Local Tools toggle list has 30+ rows) the per-tap allocation churn raced the
    // Switch's 150ms thumb-slide for main-thread frames and animations dropped to a
    // visible <10 fps. Reusing the scope and clearing its items in place avoids the
    // churn without changing the public DSL.
    val scope = remember { CardGroupScopeImpl() }
    scope.items.clear()
    scope.content()

    Column(modifier = modifier) {
        if (title != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                ProvideTextStyle(MaterialTheme.typography.titleSmallEmphasized) {
                    Box(modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)) {
                        title()
                    }
                }
            }
        }
        // 圆角 / 分隔线只按「可见」项计算；隐藏项仍留在列表里，好让它带着收起动画离场。
        val count = scope.items.count { it.visible }
        var visibleIndex = 0
        scope.items.fastForEachIndexed { index, item ->
            val itemVisibleIndex = if (item.visible) visibleIndex++ else visibleIndex
            val row: @Composable () -> Unit = {
                Column {
                    if (itemVisibleIndex > 0) {
                        if (CardGroupItemSpacing > 0.dp) {
                            Spacer(modifier = Modifier.height(CardGroupItemSpacing))
                        } else {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 44.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                        }
                    }
                    CardGroupListItem(
                        item = item,
                        count = count.coerceAtLeast(1),
                        index = itemVisibleIndex.coerceAtMost((count - 1).coerceAtLeast(0)),
                    )
                }
            }
            key(index) {
                if (item.animatedVisible == null) {
                    row()
                } else {
                    AnimatedVisibility(
                        visible = item.animatedVisible,
                        enter = expandVertically(Motion.enter(Motion.NORMAL), expandFrom = Alignment.Top) +
                            fadeIn(Motion.enter(Motion.NORMAL)),
                        exit = shrinkVertically(Motion.exit(Motion.NORMAL), shrinkTowards = Alignment.Top) +
                            fadeOut(Motion.exit(Motion.FAST)),
                    ) {
                        row()
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun CardGroupPreview() {
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text("Card Group")
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            CardGroup(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = { Text("About") },
            ) {
                item(
                    headlineContent = { Text("第一项") },
                )
                item(
                    headlineContent = { Text("第二项") },
                    supportingContent = { Text("支持文本") },
                )
                item(
                    onClick = {},
                    headlineContent = { Text("第三项") },
                    trailingContent = { Text("→") },
                )
            }
        }
    }
}
