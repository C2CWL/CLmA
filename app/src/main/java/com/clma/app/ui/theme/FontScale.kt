package me.rerere.rikkahub.ui.theme

import me.rerere.rikkahub.data.datastore.ChatFontFamily
import kotlin.math.roundToInt

/**
 * Pure rules behind the two font size controls on the display settings page:
 *  - the existing chat font size ([me.rerere.rikkahub.data.datastore.DisplaySetting.fontSizeRatio],
 *    chat messages only), and
 *  - the app-wide UI font size ([me.rerere.rikkahub.data.datastore.DisplaySetting.uiFontScale]),
 *    applied as a multiplier on `Density.fontScale` so `sp` text scales while `dp` layout does not.
 *
 * Both sliders are continuous with 1% granularity, and the percentage next to each can be typed
 * in exactly. Stored values are never rewritten on read: an old stepped value such as 0.875 keeps
 * working (it is shown as 88%) and is only snapped to a whole percent when the user moves it.
 */
object FontScale {
    val CHAT_RANGE: ClosedFloatingPointRange<Float> = 0.5f..2.0f
    val UI_RANGE: ClosedFloatingPointRange<Float> = 0.5f..2.0f
    const val DEFAULT = 1.0f

    /** Round to a whole percent and clamp; NaN/∞ fall back to [DEFAULT] (clamped). */
    fun snap(value: Float, range: ClosedFloatingPointRange<Float>): Float {
        if (value.isNaN() || value.isInfinite()) return DEFAULT.coerceIn(range)
        return ((value * 100f).roundToInt() / 100f).coerceIn(range)
    }

    /** The integer percentage shown next to a slider (rounded, not truncated: 0.875 → 88). */
    fun percentLabel(value: Float): Int = (value * 100f).roundToInt()

    /**
     * Parses typed input such as `"87"`, `" 120 "` or `"95%"` into a ratio clamped to [range].
     * Anything that is not an integer percentage (blank, `"abc"`, `"1.5"`) yields `null`, and the
     * caller keeps the current value.
     */
    fun parsePercent(input: String, range: ClosedFloatingPointRange<Float>): Float? {
        val percent = input.trim().removeSuffix("%").trim().toIntOrNull() ?: return null
        return (percent / 100f).coerceIn(range)
    }

    /** The stored UI font scale as applied; out-of-range or corrupt values are clamped. */
    fun uiScale(stored: Float): Float {
        if (stored.isNaN() || stored.isInfinite()) return DEFAULT
        return stored.coerceIn(UI_RANGE)
    }

    /**
     * Upper bound for system font scale × UI font scale. Layout stays in `dp`, so beyond this text
     * clips and overlaps. A system scale that is already larger is kept as is (the cap never
     * shrinks text below what the system asks for); it only stops the UI slider from adding more.
     */
    const val MAX_EFFECTIVE_FONT_SCALE = 2.0f

    /**
     * `Density.fontScale` with the UI font scale applied on top of the system font scale, capped
     * at [MAX_EFFECTIVE_FONT_SCALE] (or the system scale, if that is larger). At the default UI
     * scale of 1.0 this is exactly the system scale.
     */
    fun effectiveFontScale(systemFontScale: Float, storedUiScale: Float): Float {
        val cap = maxOf(MAX_EFFECTIVE_FONT_SCALE, systemFontScale)
        return (systemFontScale * uiScale(storedUiScale)).coerceAtMost(cap)
    }

    /**
     * Size factor for a preview line showing what [candidateUiScale] would look like, relative to
     * the font scale currently applied ([currentFontScale]). 1 when nothing would change.
     */
    fun previewRatio(systemFontScale: Float, currentFontScale: Float, candidateUiScale: Float): Float {
        if (currentFontScale <= 0f || currentFontScale.isNaN()) return 1f
        return effectiveFontScale(systemFontScale, candidateUiScale) / currentFontScale
    }

    /**
     * Whether the UI font replaces the app typography font: only when a non-default font is
     * chosen (a custom font also needs an imported file). DEFAULT keeps the stock typography.
     */
    fun shouldApplyUiFont(family: ChatFontFamily, customFontPath: String): Boolean =
        when (family) {
            ChatFontFamily.DEFAULT -> false
            ChatFontFamily.CUSTOM -> customFontPath.isNotBlank()
            ChatFontFamily.SERIF, ChatFontFamily.MONOSPACE -> true
        }
}
