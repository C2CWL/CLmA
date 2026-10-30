package com.clma.app.service

import com.clma.app.ui.theme.ColorMode
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

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

    /** Hold this long before a drag moves the bar; early finger movement does not cancel the hold. */
    const val LONG_PRESS_MS = 450L

    /** Gap kept between the bar and the status / navigation bars. */
    const val EDGE_MARGIN_DP = 8f

    /** "Reply finished, not seen yet" fill. */
    const val DONE_GREEN = 0xF22E9E5B.toInt()

    /** One clockwise lap of the "AI is replying" rainbow streak around the pill's outline. */
    const val RING_LOOP_MS = 1440L

    /** Stroke of the streak; its centre line is inset by half of it, so it stays inside the pill. */
    const val RING_STROKE_DP = 2f

    /** Length of the streak's fading tail, as a fraction of the outline length. */
    const val RING_TAIL = 0.42f

    /** How far the palette rotates per lap (the hues shift slowly while it runs). */
    const val RING_HUE_SHIFT_PER_LOOP = 0.35f

    /** Where the streak rests (fraction of the outline) when animations are off system-wide. */
    const val RING_STILL_PHASE = 0.10f

    /** Soft-edged leading tip and white-hot head, as fractions of the outline length. */
    private const val RING_LEAD = 0.02f
    private const val RING_HOT = 0.03f

    /**
     * Palette of the streak. [base] / [baseAlpha] are the faint track that shows the rest of the
     * outline; [hot] is how far the head is pushed towards white; [glow] scales the soft inner glow.
     */
    class RingStyle(
        val saturation: Float,
        val value: Float,
        val hot: Float,
        val base: Int,
        val baseAlpha: Float,
        val glow: Float,
    )

    /** Dark / saturated bars: vivid rainbow, white-hot head, faint white track. */
    val RING_ON_DARK = RingStyle(
        saturation = 0.85f, value = 1f, hot = 0.55f, base = 0xFFFFFF, baseAlpha = 0.06f, glow = 0.55f,
    )

    /** Light (cream / light grey / white) bars: darker, fully saturated, toned-down head, warm-grey track. */
    val RING_ON_LIGHT = RingStyle(
        saturation = 1f, value = 0.86f, hot = 0.20f, base = 0x968C80, baseAlpha = 0.10f, glow = 0.45f,
    )

    fun ringStyle(fillArgb: Int): RingStyle = if (isLightFill(fillArgb)) RING_ON_LIGHT else RING_ON_DARK

    /** Drop-across thresholds for switching sides after a long-press drag. */
    const val SWITCH_TO_LEFT_RATIO = 0.38f
    const val SWITCH_TO_RIGHT_RATIO = 0.62f

    /** Where the bar's centre sits on first run, as a fraction of the usable height. */
    const val DEFAULT_CENTER_FRACTION = 0.62f

    /**
     * What the bar shows. [WORKING]: a rainbow streak runs clockwise around the pill's outline; [DONE]: the pill turns
     * green with a white check until the user taps the bar (opening the app); [IDLE]: plain pill.
     */
    enum class Signal { IDLE, WORKING, DONE }

    fun signal(working: Boolean, unseenDone: Boolean): Signal = when {
        working -> Signal.WORKING
        unseenDone -> Signal.DONE
        else -> Signal.IDLE
    }

    /**
     * Position of the streak's head along the outline, 0..1 (clockwise from the top of the right
     * side), once per [RING_LOOP_MS]. With animations off it rests at [RING_STILL_PHASE].
     */
    fun ringPhase(nowMs: Long, animate: Boolean): Float {
        if (!animate) return RING_STILL_PHASE
        return nowMs.mod(RING_LOOP_MS).toFloat() / RING_LOOP_MS
    }

    /**
     * Palette rotation, 0..1. Runs on its own clock (continuous across laps, so the colours never
     * jump when the head wraps around), [RING_HUE_SHIFT_PER_LOOP] per lap.
     */
    fun ringHueShift(nowMs: Long, animate: Boolean): Float {
        if (!animate) return RING_STILL_PHASE * RING_HUE_SHIFT_PER_LOOP
        // 20 laps * 0.35 = 7 whole turns, so wrapping the clock there is seamless.
        val laps = nowMs.mod(RING_LOOP_MS * 20).toFloat() / RING_LOOP_MS
        return fract(laps * RING_HUE_SHIFT_PER_LOOP)
    }

    /**
     * Colour (packed ARGB) of the outline at [u] (0..1 along the outline, clockwise) for a head at
     * [phase]: a bright head, a rainbow tail fading out over [RING_TAIL] of the outline, a short
     * soft leading tip, and a faint [RingStyle.base] track everywhere else. Same maths as the
     * approved mockup (theme.py).
     */
    fun ringColor(u: Float, phase: Float, hueShift: Float, style: RingStyle): Int {
        val p = ringPixel(u, phase, hueShift, style)
        return argb(p[1], p[2], p[3], p[4])
    }

    /**
     * Faint track colour (packed ARGB): what [ringColor] gives away from the streak, i.e. outside
     * [ringStreakStart]..[ringStreakEnd]. The view draws that stretch as one seamless stroke.
     */
    fun ringTrack(style: RingStyle): Int {
        val r = ((style.base shr 16) and 0xFF) / 255f
        val g = ((style.base shr 8) and 0xFF) / 255f
        val b = (style.base and 0xFF) / 255f
        return argb(style.baseAlpha, r, g, b)
    }

    /** Where the visible streak begins (end of its tail) along the outline; may be below 0. */
    fun ringStreakStart(phase: Float): Float = phase - RING_TAIL

    /** Where the visible streak ends (tip of its soft leading edge); may be above 1. */
    fun ringStreakEnd(phase: Float): Float = phase + RING_LEAD

    /** [streak weight, alpha, r, g, b], all 0..1. */
    private fun ringPixel(u: Float, phase: Float, hueShift: Float, style: RingStyle): FloatArray {
        val behind = fract(phase - u) // 0 at the head, growing backwards along the tail
        val ahead = 1f - behind
        var lead = (1f - ahead / RING_LEAD).coerceIn(0f, 1f)
        lead = lead * lead * (3f - 2f * lead)
        val tail = if (behind < 0.9f) (1f - behind / RING_TAIL).coerceIn(0f, 1f).pow(1.4f) else 0f
        val a = maxOf(tail, lead)

        val signed = if (behind < 0.9f) behind else behind - 1f
        val rgb = hsv(signed / RING_TAIL * 0.8f + hueShift, style.saturation, style.value)
        val hot = (1f - min(behind, ahead) / RING_HOT).coerceIn(0f, 1f) * style.hot
        val streak = (a * 3f).coerceIn(0f, 1f)
        val br = ((style.base shr 16) and 0xFF) / 255f
        val bg = ((style.base shr 8) and 0xFF) / 255f
        val bb = (style.base and 0xFF) / 255f
        fun mix(c: Float, base: Float): Float {
            val lit = c + (1f - c) * hot
            return lit * streak + base * (1f - streak)
        }
        val alpha = (style.baseAlpha * (1f - a) + a).coerceIn(0f, 1f)
        return floatArrayOf(a, alpha, mix(rgb[0], br), mix(rgb[1], bg), mix(rgb[2], bb))
    }

    private fun fract(x: Float): Float = x - floor(x)

    /** HSV (all 0..1, hue wraps) to RGB 0..1, as in the mockup script. */
    internal fun hsv(h: Float, s: Float, v: Float): FloatArray {
        val h6 = fract(h) * 6f
        val i = floor(h6).toInt().mod(6)
        val f = h6 - floor(h6)
        val p = v * (1f - s * f)
        val q = v * (1f - s * (1f - f))
        val t = v * (1f - f)
        return when (i) {
            0 -> floatArrayOf(v, t, p)
            1 -> floatArrayOf(q, v, p)
            2 -> floatArrayOf(p, v, t)
            3 -> floatArrayOf(p, q, v)
            4 -> floatArrayOf(t, p, v)
            else -> floatArrayOf(v, p, q)
        }
    }

    private fun argb(a: Float, r: Float, g: Float, b: Float): Int {
        fun ch(x: Float) = Math.round(x.coerceIn(0f, 1f) * 255f)
        return (ch(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
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
        val b = (argb and 0xFF)
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

    /** Screen edge the bar is snapped to. TOP = a horizontal pill just under the status bar. */
    enum class Edge { LEFT, RIGHT, TOP }

    /** A drop whose finger is in the top [TOP_SNAP_RATIO] of the screen snaps to the top edge. */
    const val TOP_SNAP_RATIO = 0.14f

    /** Value saved under the edge key for the top edge; anything else means "use the saved x". */
    const val SAVED_EDGE_TOP = "top"

    /**
     * Edge from the saved values: [savedEdge] == [SAVED_EDGE_TOP] is the top edge; otherwise the
     * side comes from the saved x exactly as before (so existing users keep their side).
     */
    fun edgeFromSaved(savedEdge: String?, savedX: Int?, screenWidth: Int): Edge = when {
        savedEdge == SAVED_EDGE_TOP -> Edge.TOP
        onRightFromSavedX(savedX, screenWidth) -> Edge.RIGHT
        else -> Edge.LEFT
    }

    /**
     * Edge after a free long-press drag released at ([rawX], [rawY]): near the top of the screen it
     * snaps to the top; otherwise to a side, with the same "well across" rule as before when it
     * was already on a side, and the nearer half when it comes down from the top.
     */
    fun edgeAfterDrag(rawX: Float, rawY: Float, screenWidth: Int, screenHeight: Int, was: Edge): Edge {
        if (rawY < screenHeight.coerceAtLeast(1) * TOP_SNAP_RATIO) return Edge.TOP
        val onRight = when (was) {
            Edge.TOP -> rawX >= screenWidth / 2f
            Edge.RIGHT -> onRightAfterDrag(rawX, screenWidth, wasOnRight = true)
            Edge.LEFT -> onRightAfterDrag(rawX, screenWidth, wasOnRight = false)
        }
        return if (onRight) Edge.RIGHT else Edge.LEFT
    }

    /** Window x (gravity TOP|LEFT) of the top-edge bar, centred on [centerX] and kept on screen. */
    fun topBarLeft(centerX: Int, windowWidth: Int, screenWidth: Int): Int =
        (centerX - windowWidth / 2).coerceIn(0, (screenWidth - windowWidth).coerceAtLeast(0))

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
