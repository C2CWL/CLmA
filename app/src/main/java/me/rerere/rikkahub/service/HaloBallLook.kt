package me.rerere.rikkahub.service

import kotlin.math.PI
import kotlin.math.cos

/**
 * Resting-look and edge-handle numbers for the floating ball, plus the small bits of maths that
 * place them. Kept pure (no Android types) so they can be unit-tested and shared with the
 * settings page's miniature.
 */
internal object HaloBallLook {
    /** Backing disc radius, as a fraction of the view's half-size; the rest is shadow room. */
    const val BACKING_RADIUS_FRACTION = 0.88f

    /** Backing disc opacity: overlays cannot blur other apps, so a translucent surface instead. */
    const val BACKING_ALPHA = 0.85f

    /** Peak opacity (out of 255) of the soft drop shadow under the backing disc. */
    const val SHADOW_ALPHA = 44

    /** How far the shadow sits below the disc, as a fraction of the half-size. */
    const val SHADOW_OFFSET_FRACTION = 0.035f

    /** Hairline outline around the backing disc, in dp. */
    const val OUTLINE_WIDTH_DP = 1f

    /** Resting accent ring: thinner and a touch translucent, so it reads as a detail. */
    const val REST_RING_STROKE_SCALE = 0.62f
    const val REST_RING_ALPHA = 230

    /** Resting bloom, much fainter than the old 128. */
    const val REST_BLOOM_ALPHA = 48

    /** The slim handle a tucked ball collapses into. */
    const val HANDLE_WIDTH_DP = 10f
    const val HANDLE_HEIGHT_DP = 44f

    /**
     * On-screen strip of the tucked window: the visible handle plus room to grab it with a thumb
     * and drag it out (the strip is also excluded from the system back gesture).
     */
    const val HANDLE_STRIP_DP = 30f

    /**
     * What a tucked handle signals. The awake ball shows a running turn as a grey track with a
     * light dot orbiting it; the handle says the same in its own shape: [WORKING] is a grey pill
     * with a light dot running along it, [DONE] (a turn finished since the user last woke the
     * ball or opened the panel) is the usual accent pill with the light dot resting in its middle.
     */
    enum class HandleSignal { IDLE, WORKING, DONE }

    fun handleSignal(working: Boolean, unseenDone: Boolean): HandleSignal = when {
        working -> HandleSignal.WORKING
        unseenDone -> HandleSignal.DONE
        else -> HandleSignal.IDLE
    }

    /** The light dot on a signalling handle, in dp; it sits inside the [HANDLE_WIDTH_DP] pill. */
    const val HANDLE_DOT_DP = 4f

    /** One sweep of the working dot from one end of the pill to the other, in ms. */
    const val HANDLE_SWEEP_MS = 1400L

    /**
     * Where the light dot sits along the pill, 0 (top) .. 1 (bottom). While [working] and
     * [animate] it eases back and forth once per [HANDLE_SWEEP_MS]; otherwise (done, or reduced
     * motion) it rests in the middle.
     */
    fun handleDotFraction(nowMs: Long, working: Boolean, animate: Boolean): Float {
        if (!working || !animate) return 0.5f
        val period = HANDLE_SWEEP_MS * 2
        val phase = (nowMs.mod(period)).toFloat() / period
        // Cosine ping-pong: 0 -> 1 -> 0, slowing at the ends.
        return ((1f - cos(phase * 2f * PI.toFloat())) / 2f)
    }

    /**
     * A tucked handle with an unseen finished reply is never fainter than this, so the signal
     * reaches the eye even with a very low "hidden opacity" setting.
     */
    const val HANDLE_DONE_MIN_ALPHA = 0.85f

    /** Opacity of the tucked window: the user's [hiddenAlpha], raised for [HandleSignal.DONE]. */
    fun tuckedAlpha(hiddenAlpha: Float, signal: HandleSignal): Float =
        if (signal == HandleSignal.DONE) maxOf(hiddenAlpha, HANDLE_DONE_MIN_ALPHA) else hiddenAlpha

    /** [color] with its alpha replaced by [fraction] (0..1) of full opacity. */
    fun withAlphaFraction(color: Int, fraction: Float): Int {
        val a = Math.round(fraction.coerceIn(0f, 1f) * 255f)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** Half-ball tuck (FloatingBallTuckStyle.HALF_BALL): how much of the ball stays on screen. */
    const val HALF_TUCK_VISIBLE_FRACTION = 0.5f

    /** On-screen strip of a half-tucked ball of width [size], for [tuckedX]. */
    fun halfTuckStrip(size: Int): Int = (size * HALF_TUCK_VISIBLE_FRACTION).toInt().coerceAtLeast(1)

    /**
     * The on-screen part of a ball of width [size] tucked by [tuckedX] with [strip], in view x
     * coordinates as `[first, second)`: the edge-gesture exclusion covers exactly this.
     */
    fun visibleSpan(onRight: Boolean, size: Int, strip: Int): Pair<Int, Int> {
        val visible = strip.coerceIn(1, size.coerceAtLeast(1))
        return if (onRight) 0 to visible else (size - visible) to size
    }

    /**
     * Unit offset from the ball's centre of the "reply finished" marker on a half-tucked ball: on
     * the ring, upper inner side, i.e. on the visible half (left half when tucked right).
     */
    fun doneMarkerDirection(onRight: Boolean): Pair<Float, Float> {
        val d = 0.70710677f
        return (if (onRight) -d else d) to -d
    }

    /**
     * Window x for a tucked ball of width [size]: everything but a [strip]-wide sliver is pushed
     * past the edge the ball sits on.
     */
    fun tuckedX(onRight: Boolean, screenWidth: Int, size: Int, strip: Int): Int {
        val visible = strip.coerceIn(1, size.coerceAtLeast(1))
        return if (onRight) screenWidth - visible else visible - size
    }

    /**
     * Left edge, in view coordinates, of a [handleWidth]-wide handle that sits flush against the
     * screen edge once the view is tucked by [tuckedX] with the same [strip].
     */
    fun handleLeft(onRight: Boolean, size: Int, strip: Int, handleWidth: Int): Int {
        val visible = strip.coerceIn(1, size.coerceAtLeast(1))
        return if (onRight) visible - handleWidth else size - visible
    }
}
