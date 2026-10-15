package me.rerere.rikkahub.ui.components.message

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.usage.TurnFooter
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.LocalDarkMode

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
) {
    val settings = LocalSettings.current.displaySetting
    // A user message has no turn footer; when the ledger has nothing to say and the turn is not
    // running there is simply nothing to draw.
    if (!settings.showTokenUsage || turnFooter == null) return

    val darkTheme = LocalDarkMode.current
    val needPlate = photoShown  // any photo (light or dark) benefits from a plate
    // On a photo the usual secondary@0.5 can disappear into the image. Use a
    // higher-contrast colour and let the plate carry the rest.
    val color = if (photoShown) {
        if (darkTheme) {
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        }
    } else {
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
    }

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            if (needPlate) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
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
