package com.clma.app.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Colour of the edge bar (the slim pill that replaced the floating ball). Six fixed swatches;
 * stored as [id] (1..6) under `floating_ball_bar_color`. [FOLLOW_THEME] is the default and also
 * what any missing / unknown value (e.g. a store from before the swatches existed) falls back to.
 *
 * [FOLLOW_THEME] has no single colour: it is cream white in light mode and warm dark grey in dark
 * mode (see [argb] / [outlineArgb], which take the current dark-mode flag). The other swatches
 * ignore the flag.
 *
 * [rgb] is opaque `0xRRGGBB`; [alpha] is 0..1; [outlineRgb] is an optional 1dp outline (only the
 * white swatch has one among the fixed colours, so it still shows on light screens). All pure so
 * it is unit-testable.
 */
@Serializable
enum class EdgeBarColor(
    val id: Int,
    val rgb: Int,
    val alpha: Float,
    val outlineRgb: Int? = null,
) {
    /**
     * Follows the app's light / dark theme. Was "dark grey" (same id 1) before; the serial name is
     * kept so Settings JSON written by older versions (backups) still decodes.
     */
    @SerialName("DARK_GRAY")
    FOLLOW_THEME(1, 0xF6F1E7, 0.92f),
    LIGHT_GRAY(2, 0xAAACB2, 0.85f),
    BLACK(3, 0x141416, 0.85f),
    WHITE(4, 0xFAFAFC, 0.95f, outlineRgb = 0xC8C8CD),
    BLUE(5, 0x2F80ED, 0.8f),
    TEAL(6, 0x14A38B, 0.8f);

    /** True when the colour changes with the light / dark theme. */
    val followsTheme: Boolean get() = this == FOLLOW_THEME

    /** Fill as a packed ARGB int, ready for an Android Paint / Compose Color. */
    fun argb(dark: Boolean): Int = when {
        this == FOLLOW_THEME -> if (dark) THEME_DARK_FILL else THEME_LIGHT_FILL
        else -> withAlpha(rgb, alpha)
    }

    /** 1dp inside outline as a packed ARGB int, or null when this swatch has none. */
    fun outlineArgb(dark: Boolean): Int? = when {
        this == FOLLOW_THEME -> if (dark) THEME_DARK_OUTLINE else THEME_LIGHT_OUTLINE
        else -> outlineRgb?.let { withAlpha(it, 1f) }
    }

    companion object {
        val DEFAULT = FOLLOW_THEME

        /** [FOLLOW_THEME] in light mode: cream white, ~92% opaque, with a warm grey outline. */
        const val THEME_LIGHT_FILL = 0xEBF6F1E7.toInt()
        const val THEME_LIGHT_OUTLINE = 0xFFCDC3B5.toInt()

        /** [FOLLOW_THEME] in dark mode: warm dark grey, ~78% opaque, with a faint warm outline. */
        const val THEME_DARK_FILL = 0xC73E3934.toInt()
        const val THEME_DARK_OUTLINE = 0x1AFFF4E4

        fun fromId(id: Int?): EdgeBarColor = entries.firstOrNull { it.id == id } ?: DEFAULT

        internal fun withAlpha(rgb: Int, alpha: Float): Int {
            val a = Math.round(alpha.coerceIn(0f, 1f) * 255f)
            return (a shl 24) or (rgb and 0x00FFFFFF)
        }
    }
}
