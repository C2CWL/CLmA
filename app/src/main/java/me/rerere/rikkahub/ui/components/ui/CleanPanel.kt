package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The clean layout's two panels — the header at the top and the composer card at the bottom — are
// one material: same corner radius, same hairline, same fill. They are also the two places the
// user can dial the panel strength (DisplaySetting.cleanLayoutTopOpacity /
// cleanLayoutBottomOpacity), so both read their colours from here and cannot drift apart.

/** Corner radius shared by the header and the composer card. */
internal val CleanPanelCorner = 14.dp

/** The header is flush with the screen edges, so only its bottom corners are rounded. */
@Composable
internal fun cleanHeaderShape(): RoundedCornerShape = RoundedCornerShape(
    bottomStart = CleanPanelCorner,
    bottomEnd = CleanPanelCorner,
)

/** Clamps a stored panel strength to 0..1; shared by the flat fill and the glass tint. */
internal fun cleanPanelAlpha(opacity: Float): Float = opacity.coerceIn(0f, 1f)

/**
 * Range of the composer's vertical inset (DisplaySetting.cleanLayoutBottomPadding), in dp.
 *
 * The card is dominated by the field's own 56dp height, so the old 4..10 window could move it by
 * 12dp at most and 4dp away from the default 6dp was invisible - the slider read as dead. 0..24
 * still keeps the text off the card's edge at the low end and can visibly shorten or grow the box.
 */
internal const val CleanInputPaddingMinDp = 0f
internal const val CleanInputPaddingMaxDp = 24f

/**
 * Fill of a panel in the flat (non-glass) material.
 *
 * [opacity] is the user's panel strength: 0 = fully clear, 1 = a solid card. The base is the
 * lowest surface container, so "opaque" is a white card in the light theme and a dark one in the
 * dark theme instead of a white slab.
 */
@Composable
internal fun cleanPanelFill(opacity: Float): Color =
    MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = cleanPanelAlpha(opacity))

/**
 * Hairline around a panel. It deliberately does not fade with [cleanPanelFill]'s opacity: at 0%
 * the frame is the only thing left, and without it the panel would stop reading as a panel.
 */
@Composable
internal fun cleanPanelOutline(darkTheme: Boolean): Color =
    if (darkTheme) {
        Color.White.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }