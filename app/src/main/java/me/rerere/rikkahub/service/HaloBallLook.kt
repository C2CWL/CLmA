package me.rerere.rikkahub.service

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
    const val HANDLE_WIDTH_DP = 6f
    const val HANDLE_HEIGHT_DP = 44f

    /** On-screen strip of the tucked window: the handle plus a little extra to hit with a thumb. */
    const val HANDLE_STRIP_DP = 16f

    /** [color] with its alpha replaced by [fraction] (0..1) of full opacity. */
    fun withAlphaFraction(color: Int, fraction: Float): Int {
        val a = Math.round(fraction.coerceIn(0f, 1f) * 255f)
        return (color and 0x00FFFFFF) or (a shl 24)
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
