package com.clma.app.service

import com.clma.app.ui.theme.ColorMode
import kotlin.math.PI
import kotlin.math.cos

/**
 * Size, status and placement maths for the edge bar (the slim pill on the screen edge that
 * replaced the floating ball). Pure Kotlin, no Android types, so it can be unit-tested and shared
 * with the settings page preview.
 */
internal object EdgeBarLook {
    /** The visible pill. */
    const val BAR_WIDTH_DP = 16f
    const val BAR_HEIGHT_DP = 64f
    const val BAR_CORNER_DP = 8f

    /** The overlay window (touch area) the pill sits in, flush with the screen edge. */
    const val TOUCH_WIDTH_DP = 32f
    const val TOUCH_HEIGHT_DP = 76f

    /** Outline width of swatches that have one. */
    const val OUTLINE_DP = 1f

    /** Hold this long before a drag moves the bar; plain swipes never move it. */
    const val LONG_PRESS_MS = 450L

    /** Gap kept between the bar and the status / navigation bars. */
    const val EDGE_MARGIN_DP = 8f

    /** "Reply finished, not seen yet" fill. */
    const val DONE_GREEN = 0xF22E9E5B.toInt()

    /** One glide of the working light sweep from one end of the pill to the other. */
    const val SWEEP_MS = 1400L

    /** Height of the soft light band, as a fraction of the pill height. */
    const val SWEEP_BAND_FRACTION = 0.45f

    /** Peak opacity of the light band (0..1). */
    const val SWEEP_PEAK_ALPHA = 0.7f

    /** Drop-across thresholds for switching sides after a long-press drag. */
    const val SWITCH_TO_LEFT_RATIO = 0.38f
    const val SWITCH_TO_RIGHT_RATIO = 0.62f

    /** Where the bar's centre sits on first run, as a fraction of the usable height. */
    const val DEFAULT_CENTER_FRACTION = 0.62f

    /**
     * What the bar shows. [WORKING]: a light sweep glides through the pill; [DONE]: the pill turns
     * green with a white check until the user taps the bar (opening the app); [IDLE]: plain pill.
     */
    enum class Signal { IDLE, WORKING, DONE }

    fun signal(working: Boolean, unseenDone: Boolean): Signal = when {
        working -> Signal.WORKING
        unseenDone -> Signal.DONE
        else -> Signal.IDLE
    }

    /**
     * Centre of the light band along the pill, 0 (top) .. 1 (bottom). While [animate] it eases
     * back and forth once per [SWEEP_MS] (cosine ping-pong, slow at the ends); otherwise (reduced
     * motion) it rests in the middle.
     */
    fun sweepFraction(nowMs: Long, animate: Boolean): Float {
        if (!animate) return 0.5f
        val period = SWEEP_MS * 2
        val phase = nowMs.mod(period).toFloat() / period
        return (1f - cos(phase * 2f * PI.toFloat())) / 2f
    }

    /**
     * Whether the bar should use its dark-mode look, decided the same way the app's own theme is
     * (see `RikkahubTheme`): the app's colour-mode setting ([colorMode], a [ColorMode] name stored
     * under `colorMode`) wins when it is LIGHT or DARK; SYSTEM, missing or unknown follow the
     * system night mode ([systemNight], from `Configuration.uiMode`).
     */
    fun isDarkMode(colorMode: String?, systemNight: Boolean): Boolean = when (colorMode) {
        ColorMode.LIGHT.name -> false
        ColorMode.DARK.name -> true
        else -> systemNight
    }

    /**
     * True for a light fill (cream / light grey / white), which gets the toned-down "light" look of
     * the working streak. Perceived brightness of the RGB part; alpha is ignored.
     */
    fun isLightFill(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f > LIGHT_FILL_THRESHOLD
    }

    private const val LIGHT_FILL_THRESHOLD = 0.6f

    /**
     * Side the bar is on, from the saved window x. Both the old ball (resting / tucked) and the
     * bar save an x left of the middle for the left edge and right of it for the right edge; a
     * missing value means the default right edge.
     */
    fun onRightFromSavedX(savedX: Int?, screenWidth: Int): Boolean =
        savedX == null || savedX >= screenWidth / 2

    /** Window x to save for a side (the window is placed by gravity, this only records the side). */
    fun savedXFor(onRight: Boolean, screenWidth: Int, windowWidth: Int): Int =
        if (onRight) (screenWidth - windowWidth).coerceAtLeast(screenWidth / 2) else 0

    /**
     * Keeps a window of [windowHeight] fully inside the usable band: below the top inset plus
     * [margin] and above the bottom inset plus [margin]. If the band is too small the window
     * sticks to its top.
     */
    fun clampTop(top: Int, windowHeight: Int, screenHeight: Int, topInset: Int, bottomInset: Int, margin: Int): Int {
        val min = topInset + margin
        val max = (screenHeight - bottomInset - margin - windowHeight).coerceAtLeast(min)
        return top.coerceIn(min, max)
    }

    /** First-run window top: the bar centred at [DEFAULT_CENTER_FRACTION] of the screen height. */
    fun defaultTop(windowHeight: Int, screenHeight: Int): Int =
        (screenHeight * DEFAULT_CENTER_FRACTION).toInt() - windowHeight / 2

    /**
     * Side after a long-press drag released at [rawX]: dropped well across the screen it switches
     * edges, otherwise it stays where it was.
     */
    fun onRightAfterDrag(rawX: Float, screenWidth: Int, wasOnRight: Boolean): Boolean {
        val ratio = rawX / screenWidth.coerceAtLeast(1).toFloat()
        return when {
            wasOnRight && ratio < SWITCH_TO_LEFT_RATIO -> false
            !wasOnRight && ratio > SWITCH_TO_RIGHT_RATIO -> true
            else -> wasOnRight
        }
    }
}
