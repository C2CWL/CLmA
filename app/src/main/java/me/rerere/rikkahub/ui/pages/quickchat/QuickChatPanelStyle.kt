package me.rerere.rikkahub.ui.pages.quickchat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/**
 * The quick-chat panel's glass palette, one set per theme.
 *
 * Keyed off the app's own [LocalDarkMode] (never the system setting), so a user who forces light
 * or dark inside the app gets the matching panel even when the phone is set the other way.
 */
@Immutable
internal data class QuickChatPanelStyle(
    /** Panel tint; the alpha is what lets the blurred app behind show through. */
    val panel: Color,
    /** 1dp outline around the panel and the input capsule, and the header divider. */
    val hairline: Color,
    /** Container of the round translucent buttons. */
    val button: Color,
    /** The input capsule: a touch lighter than the panel, so it reads as raised. */
    val input: Color,
)

/** Panel alpha over the OS backdrop blur, per theme: dark glass needs less tint to stay deep. */
private const val PANEL_ALPHA_BLURRED_DARK = 0.72f
private const val PANEL_ALPHA_BLURRED_LIGHT = 0.78f

/**
 * Without a backdrop blur the app underneath shows through raw, so the panel stays nearly opaque
 * to keep text readable over a busy screen — just short of 1 so it still does not read as a slab.
 */
private const val PANEL_ALPHA_OPAQUE = 0.95f

@Composable
internal fun rememberQuickChatPanelStyle(blurredBackdrop: Boolean): QuickChatPanelStyle {
    val dark = LocalDarkMode.current
    val scheme = MaterialTheme.colorScheme
    val panelAlpha = when {
        !blurredBackdrop -> PANEL_ALPHA_OPAQUE
        dark -> PANEL_ALPHA_BLURRED_DARK
        else -> PANEL_ALPHA_BLURRED_LIGHT
    }
    return if (dark) {
        QuickChatPanelStyle(
            panel = scheme.surface.copy(alpha = panelAlpha),
            hairline = Color.White.copy(alpha = 0.12f),
            button = Color.White.copy(alpha = 0.08f),
            input = Color.White.copy(alpha = 0.07f),
        )
    } else {
        QuickChatPanelStyle(
            panel = scheme.surface.copy(alpha = panelAlpha),
            hairline = scheme.onSurface.copy(alpha = 0.10f),
            button = scheme.onSurface.copy(alpha = 0.05f),
            input = scheme.surfaceContainerLowest.copy(alpha = 0.72f),
        )
    }
}

/**
 * One round control of the panel: a 36dp circle inside the 40dp slot the old IconButtons took,
 * so the header and composer keep their layout while every button gets the same glass chrome.
 * [accent] fills it with the primary colour instead (send with text, Agent 介入).
 */
@Composable
internal fun PanelRoundButton(
    onClick: () -> Unit,
    style: QuickChatPanelStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (accent && enabled) scheme.primary else style.button
    val contentColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        accent -> scheme.onPrimary
        else -> scheme.onSurface
    }
    var circle = modifier
        .padding(2.dp)
        .clip(CircleShape)
        .background(container)
    if (!(accent && enabled)) {
        circle = circle.border(1.dp, style.hairline, CircleShape)
    }
    Box(
        modifier = circle.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}
