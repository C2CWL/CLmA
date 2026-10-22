package com.clma.app.data.datastore

import kotlinx.serialization.Serializable

/**
 * Colour of the edge bar (the slim pill that replaced the floating ball). Six fixed swatches;
 * stored as [id] (1..6) under `floating_ball_bar_color`. [DARK_GRAY] is the default and also what
 * any missing / unknown value (e.g. a store from before the swatches existed) falls back to.
 *
 * [rgb] is opaque `0xRRGGBB`; [alpha] is 0..1; [outlineRgb] is an optional 1dp outline (only the
 * white swatch has one, so it still shows on light screens). All pure so it is unit-testable.
 */
@Serializable
enum class EdgeBarColor(
    val id: Int,
    val rgb: Int,
    val alpha: Float,
    val outlineRgb: Int? = null,
) {
    DARK_GRAY(1, 0x28282E, 0.55f),
    LIGHT_GRAY(2, 0xAAACB2, 0.85f),
    BLACK(3, 0x141416, 0.85f),
    WHITE(4, 0xFAFAFC, 0.95f, outlineRgb = 0xC8C8CD),
    BLUE(5, 0x2F80ED, 0.8f),
    TEAL(6, 0x14A38B, 0.8f);

    /** Fill as a packed ARGB int, ready for an Android Paint / Compose Color. */
    val argb: Int get() = withAlpha(rgb, alpha)

    /** Outline as a packed, fully opaque ARGB int, or null when this swatch has none. */
    val outlineArgb: Int? get() = outlineRgb?.let { withAlpha(it, 1f) }

    companion object {
        val DEFAULT = DARK_GRAY

        fun fromId(id: Int?): EdgeBarColor = entries.firstOrNull { it.id == id } ?: DEFAULT

        internal fun withAlpha(rgb: Int, alpha: Float): Int {
            val a = Math.round(alpha.coerceIn(0f, 1f) * 255f)
            return (a shl 24) or (rgb and 0x00FFFFFF)
        }
    }
}
