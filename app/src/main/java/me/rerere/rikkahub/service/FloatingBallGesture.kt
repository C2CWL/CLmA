package me.rerere.rikkahub.service

/**
 * Pure gesture / settle maths for the floating ball overlay.
 *
 * Kept free of Android types so unit tests can lock the hysteresis and the tuck/wake
 * vertical-centre preservation without spinning up a WindowManager.
 */
internal object FloatingBallGesture {

    /**
     * Below this centre-x / screen-width ratio the ball settles on the left edge.
     * Above [SNAP_RIGHT_RATIO] it settles on the right. Between the two it keeps the side it
     * started the drag on — so a natural "pull the tucked handle out" swipe (which moves
     * inward a long way, especially with the wide capsule) does not fling it to the opposite
     * edge.
     */
    const val SNAP_LEFT_RATIO = 0.38f
    const val SNAP_RIGHT_RATIO = 0.62f

    /**
     * Which horizontal edge a drag should settle on.
     *
     * @param centerX horizontal centre of the overlay in screen pixels
     * @param screenWidth current display width in pixels
     * @param startedOnRight side the overlay was on when the finger went down
     */
    fun settleOnRight(centerX: Float, screenWidth: Int, startedOnRight: Boolean): Boolean {
        val width = screenWidth.coerceAtLeast(1).toFloat()
        val ratio = centerX / width
        return when {
            ratio < SNAP_LEFT_RATIO -> false
            ratio > SNAP_RIGHT_RATIO -> true
            else -> startedOnRight
        }
    }

    /**
     * Top edge that keeps the same vertical centre when the overlay's height changes
     * (capsule full bar ↔ slim handle). Without this, expanding the capsule grows downward
     * from a fixed top and reads as a downward jerk on wake.
     */
    fun topPreservingCenter(oldTop: Int, oldHeight: Int, newHeight: Int): Int {
        if (oldHeight <= 0 || newHeight <= 0 || oldHeight == newHeight) return oldTop
        return oldTop + (oldHeight - newHeight) / 2
    }
}
