package me.rerere.rikkahub.service

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import me.rerere.rikkahub.service.HaloBallLook.HandleSignal

/**
 * Paints the tucked edge handle, shared by the round ball ([HaloBallView]) and the capsule
 * ([CapsuleBallView]) so both say the same thing (see [HaloBallLook.HandleSignal]):
 *
 *  - idle: the accent pill, as before;
 *  - working: the pill in the awake ball's grey track colour, with a light dot running along it;
 *  - done: the accent pill with the light dot resting in its middle.
 *
 * With animations turned off system-wide (reduced motion) the working dot rests in the middle
 * too; the grey vs accent pill still tells the two apart.
 */
internal class EdgeHandlePainter(private val density: Float) {
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val rect = RectF()

    /**
     * Draws the pill into [left], [top], [width] x [height]. Returns true when it is animating
     * and the caller should invalidate again on the next frame.
     */
    fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        accent: Int,
        signal: HandleSignal,
    ): Boolean {
        val radius = width / 2f
        rect.set(left, top, left + width, top + height)
        pillPaint.color = if (signal == HandleSignal.WORKING) HaloBallView.TRACK_GREY else accent
        canvas.drawRoundRect(rect, radius, radius, pillPaint)
        if (signal == HandleSignal.IDLE) return false

        val animate = signal == HandleSignal.WORKING && ValueAnimator.areAnimatorsEnabled()
        val dotRadius = minOf(HaloBallLook.HANDLE_DOT_DP * density, width * 0.6f) / 2f
        // Keep the dot inside the pill's straight part, clear of the rounded ends.
        val travelTop = top + radius
        val travel = (height - 2f * radius).coerceAtLeast(0f)
        val fraction = HaloBallLook.handleDotFraction(
            nowMs = SystemClock.uptimeMillis(),
            working = signal == HandleSignal.WORKING,
            animate = animate,
        )
        canvas.drawCircle(left + radius, travelTop + travel * fraction, dotRadius, dotPaint)
        return animate
    }
}
