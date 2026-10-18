package me.rerere.rikkahub.ui.components.message

import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.usage.TurnFooter
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.chat.CHAT_WALLPAPER_HAZE_KEY

/**
 * 显示消息的技术统计信息（如 token 使用量）。
 *
 * D6 — this is now the **only** footer: the ledger-backed [TurnFooter] replaces the old
 * `message.usage` widget that used to render alongside it (the two coexisted, and which one you
 * saw depended on whether the turn's ledger row had landed yet). The ledger view is the honest
 * one for a multi-call turn, so the fallback was dropped rather than kept in parallel; while a
 * turn is still generating and its first row has not landed, the widget itself shows
 * “counting…” instead of a second, differently-shaped line.
 */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    turnFooter: TurnFooter? = null,
    modifier: Modifier = Modifier,
    photoShown: Boolean = false,
    hazeState: HazeState? = null,
) {
    val settings = LocalSettings.current.displaySetting
    // A user message has no turn footer; when the ledger has nothing to say and the turn is not
    // running there is simply nothing to draw.
    if (!settings.showTokenUsage || turnFooter == null) return

    // Frosted only where Haze blurs for real (API 31+) and the page gave us its haze state.
    val style = usageLineStyle(photoShown, sdkInt = Build.VERSION.SDK_INT, hasHazeState = hazeState != null)
    val ink = when (style.ink) {
        UsageLineInk.ON_SURFACE -> MaterialTheme.colorScheme.onSurface
        UsageLineInk.SECONDARY -> MaterialTheme.colorScheme.secondary
    }
    val color = ink.copy(alpha = style.inkAlpha)

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            val plateAlpha = style.plateAlpha
            val frost = style.frost
            if (frost != null && hazeState != null) {
                val shape = RoundedCornerShape(12.dp)
                val backdrop = MaterialTheme.colorScheme.background
                val tint = MaterialTheme.colorScheme.surface.copy(alpha = frost.tintAlpha)
                val blurStyle = remember(backdrop, tint, frost.blurRadiusDp) {
                    HazeBlurStyle {
                        backgroundColor(backdrop)
                        blurRadius(frost.blurRadiusDp.dp)
                        colorEffects(listOf(HazeColorEffect.tint(tint)))
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(shape)
                        .hazeBlur(
                            input = HazeInput.Sources(hazeState, selection = WallpaperOnlySelection),
                            style = blurStyle,
                        ),
                ) {
                    TurnUsageLine(
                        footer = turnFooter,
                        modifier = modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            } else if (plateAlpha != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = plateAlpha),
                ) {
                    TurnUsageLine(
                        footer = turnFooter,
                        modifier = modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            } else {
                TurnUsageLine(footer = turnFooter, modifier = modifier)
            }
        }
    }
}

/**
 * Only the wallpaper source. The message list is a source of the same state and an ancestor of
 * this line; selecting it would blur other messages and feed the effect into itself.
 * Kept as one instance so [HazeInput.Sources] stays equal across recompositions.
 */
private val WallpaperOnlySelection: HazeSourceSelection =
    HazeSourceSelection.All.where { it.key == CHAT_WALLPAPER_HAZE_KEY }

// Generation cost is often a tiny fraction of a cent, so a fixed decimal count would show
// "$0.0000". Render up to 6 decimals and trim trailing zeros (e.g. "$0.0123", "$0.000045").
// A positive cost smaller than 1e-6 would round to zero at 6dp and read as "$0" (free), which
// is misleading; clamp those to a "<$0.000001" form so a real charge never displays as free.
@VisibleForTesting
internal fun formatCost(cost: Double): String {
    val rounded = java.math.BigDecimal(cost)
        .setScale(6, java.math.RoundingMode.HALF_UP)
    if (cost > 0.0 && rounded.signum() == 0) {
        return "<$0.000001"
    }
    val s = rounded.stripTrailingZeros().toPlainString()
    return "$" + s
}

@Composable
fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}
