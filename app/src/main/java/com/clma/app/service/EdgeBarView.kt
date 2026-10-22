package com.clma.app.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.clma.app.data.datastore.EdgeBarColor
import com.clma.app.service.EdgeBarLook.Signal

/**
 * The edge bar: a [EdgeBarLook.BAR_WIDTH_DP] x [EdgeBarLook.BAR_HEIGHT_DP] pill drawn flush with
 * the screen edge it sits on ([onRight]), vertically centred in a slightly larger touch area (the
 * view's own bounds). Everything is drawn inside the pill, never outside it:
 *
 *  - [Signal.IDLE]: the plain pill in the chosen [barColor] (light / dark per [darkMode] for the
 *    "follow theme" swatch);
 *  - [Signal.WORKING]: a soft white light band gliding up and down inside the pill;
 *  - [Signal.DONE]: the pill turns green with a white check mark.
 *
 * With animations switched off system-wide the light band rests in the middle instead of moving.
 */
internal class EdgeBarView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(EdgeBarLook.OUTLINE_DP)
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2f)
        color = Color.WHITE
    }
    private val pill = RectF()
    private val clip = Path()
    private val check = Path()

    var barColor: EdgeBarColor = EdgeBarColor.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Light / dark look for the "follow theme" swatch ([EdgeBarColor.FOLLOW_THEME]). */
    var darkMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var signal: Signal = Signal.IDLE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Screen edge the pill hugs. */
    var onRight: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val barW = dp(EdgeBarLook.BAR_WIDTH_DP).coerceAtMost(w)
        val barH = dp(EdgeBarLook.BAR_HEIGHT_DP).coerceAtMost(h)
        val radius = dp(EdgeBarLook.BAR_CORNER_DP).coerceAtMost(barW / 2f)
        val left = if (onRight) w - barW else 0f
        val top = (h - barH) / 2f
        pill.set(left, top, left + barW, top + barH)

        val signal = signal
        fillPaint.color = if (signal == Signal.DONE) EdgeBarLook.DONE_GREEN else barColor.argb(darkMode)
        canvas.drawRoundRect(pill, radius, radius, fillPaint)
        if (signal != Signal.DONE) {
            barColor.outlineArgb(darkMode)?.let { outline ->
                outlinePaint.color = outline
                val inset = outlinePaint.strokeWidth / 2f
                pill.inset(inset, inset)
                canvas.drawRoundRect(pill, radius - inset, radius - inset, outlinePaint)
                pill.inset(-inset, -inset)
            }
        }

        when (signal) {
            Signal.IDLE -> Unit
            Signal.WORKING -> {
                val animate = ValueAnimator.areAnimatorsEnabled()
                drawSweep(canvas, radius, animate)
                if (animate) postInvalidateOnAnimation()
            }
            Signal.DONE -> drawCheck(canvas)
        }
    }

    /** The light band, clipped to the pill so nothing is drawn outside the bar. */
    private fun drawSweep(canvas: Canvas, radius: Float, animate: Boolean) {
        val band = pill.height() * EdgeBarLook.SWEEP_BAND_FRACTION
        val fraction = EdgeBarLook.sweepFraction(SystemClock.uptimeMillis(), animate)
        // The band's centre travels from just above the pill to just below it, so it glides in
        // and out at the ends instead of bouncing off them.
        val center = pill.top - band / 2f + (pill.height() + band) * fraction
        val peak = Color.argb(Math.round(EdgeBarLook.SWEEP_PEAK_ALPHA * 255f), 255, 255, 255)
        sweepPaint.shader = LinearGradient(
            0f, center - band / 2f, 0f, center + band / 2f,
            intArrayOf(Color.TRANSPARENT, peak, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        clip.reset()
        clip.addRoundRect(pill, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(pill, sweepPaint)
        canvas.restore()
        sweepPaint.shader = null
    }

    /** A small white check mark in the middle of the pill. */
    private fun drawCheck(canvas: Canvas) {
        val cx = pill.centerX()
        val cy = pill.centerY()
        val s = (pill.width() - dp(6f)).coerceAtLeast(dp(6f)) / 10f // 10dp-wide mark in a 16dp pill
        check.reset()
        check.moveTo(cx - 4.5f * s, cy + 0.2f * s)
        check.lineTo(cx - 1.4f * s, cy + 3.2f * s)
        check.lineTo(cx + 4.5f * s, cy - 3.2f * s)
        canvas.drawPath(check, checkPaint)
    }
}
