package com.clma.app.ui.components.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clma.app.ui.theme.LocalDarkMode

// 「现代」聊天布局（ChatLayout.MODERN，存储值沿用原暖阳的 "claude"）的一套外观参数。
// 思路：方为主、圆点缀；不用硬描边和虚线，改用浅色底块、柔和阴影和留白来分区。
// 颜色全部取自 MaterialTheme，浅色/深色和动态取色都能跟着变。

/** True inside the composer while the 现代 layout is active; read by its private buttons. */
val LocalModernChatLayout = staticCompositionLocalOf { false }

/** Big surfaces: thinking strip, code block, top bar buttons' parent. */
val ModernSurfaceCorner = 12.dp

/** The floating composer card. */
val ModernCardCorner = 20.dp

/** The floating top bar. */
val ModernTopBarCorner = 16.dp

/** Small square buttons and chips (tool buttons, token chip). */
val ModernSmallCorner = 8.dp

/** Top bar icon buttons: soft rounded squares. */
val ModernTopButtonCorner = 11.dp

/** User bubble: rounded with a small tail at the top end. */
val ModernUserBubbleShape = RoundedCornerShape(
    topStart = 16.dp,
    topEnd = 5.dp,
    bottomEnd = 16.dp,
    bottomStart = 16.dp,
)

/** Soft accent tint used for the user bubble, the active tool button and the model pill. */
@Composable
fun modernAccentSoft(): Color =
    MaterialTheme.colorScheme.primary.copy(alpha = if (LocalDarkMode.current) 0.18f else 0.10f)

/** Even softer tint for the thinking strip. */
@Composable
fun modernThinkFill(): Color =
    MaterialTheme.colorScheme.primary.copy(alpha = if (LocalDarkMode.current) 0.11f else 0.06f)

/** Neutral tonal fill (top bar buttons, code block, token chip). */
@Composable
fun modernTonal(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/** Faint hairline: the only line the layout still draws (under a code header, above the tools). */
@Composable
fun modernHairline(): Color =
    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)

/** Short vertical divider before + / send in the composer's tool row. */
@Composable
fun modernDivider(): Color =
    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)

/** Thin rim of the model pill. */
@Composable
fun modernPillRim(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)

/**
 * Base colour of the floating panels (top bar and composer card). Light: the brightest
 * container (a white card on a grey page). Dark: a step above the page, so the card still reads
 * as raised — surfaceContainerLowest is darker than the page there.
 */
@Composable
fun modernPanelBase(): Color =
    if (LocalDarkMode.current) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }

/** Soft drop-shadow colour for the floating panels. */
@Composable
fun modernShadowColor(): Color =
    if (LocalDarkMode.current) Color.Black.copy(alpha = 0.5f) else Color(0xFF141828).copy(alpha = 0.16f)

/** Status dot next to the model name in the top bar ("online"). */
@Composable
fun modernStatusGreen(): Color =
    if (LocalDarkMode.current) Color(0xFF34D399) else Color(0xFF22C55E)

/**
 * Panel alpha for the modern top bar / composer from the user's opacity slider (0..1).
 * Even at 0 the panel keeps a floor, so it still reads as a floating card and the messages
 * scrolling underneath don't show through its text.
 */
fun modernPanelAlpha(opacity: Float): Float = 0.55f + 0.45f * opacity.coerceIn(0f, 1f)
