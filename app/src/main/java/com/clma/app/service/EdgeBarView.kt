package com.clma.app.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
 *  - [Signal.WORKING]: the pill fills with one steady colour — generating, nothing moves;
 *  - [Signal.APPROVAL] / [Signal.INPUT]: the amber "needs review" colour, with a mark;
 *  - [Signal.DONE]: the pill turns green with a white check mark.
 *
 * No signal animates. Nothing here schedules frames, so the bar never redraws on its own.
 */
internal class EdgeBarView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(EdgeBarLook.OUTLINE_DP)
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2f)
        color = Color.WHITE
    }
    private val pill = RectF()
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

    /**
     * Lying along the top edge: the pill turns horizontal (64x16dp), flush with the top of the
     * window and centred horizontally in it.
     */
    var horizontal: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Nothing here animates, so there is no frame loop and no lifecycle bookkeeping to do. */
    internal fun stopFrameLoop() {
        signal = Signal.IDLE
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val thin = dp(EdgeBarLook.BAR_WIDTH_DP)
        val long = dp(EdgeBarLook.BAR_HEIGHT_DP)
        val barW = (if (horizontal) long else thin).coerceAtMost(w)
        val barH = (if (horizontal) thin else long).coerceAtMost(h)
        val radius = dp(EdgeBarLook.BAR_CORNER_DP).coerceAtMost(minOf(barW, barH) / 2f)
        val left = when {
            horizontal -> (w - barW) / 2f
            onRight -> w - barW
            else -> 0f
        }
        val top = if (horizontal) 0f else (h - barH) / 2f
        pill.set(left, top, left + barW, top + barH)

        val signal = signal
        fillPaint.color = when (signal) {
            Signal.WORKING -> EdgeBarLook.WORKING_BLUE
            Signal.DONE -> EdgeBarLook.DONE_GREEN
            Signal.APPROVAL, Signal.INPUT -> EdgeBarLook.WAITING_AMBER
            else -> barColor.argb(darkMode)
        }
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
            Signal.IDLE, Signal.WORKING -> Unit
            Signal.DONE -> drawCheck(canvas)
            Signal.APPROVAL, Signal.INPUT -> {
                val x = pill.centerX()
                val y = pill.centerY()
                if (signal == Signal.INPUT) {
                    canvas.drawLine(x - dp(3f), y - dp(4f), x - dp(3f), y + dp(4f), checkPaint)
                    canvas.drawLine(x + dp(3f), y - dp(4f), x + dp(3f), y + dp(4f), checkPaint)
                } else {
                    canvas.drawLine(x, y - dp(4f), x, y + dp(1f), checkPaint)
                    canvas.drawPoint(x, y + dp(5f), checkPaint)
                }
            }
        }
    }

    /** A small white check mark in the middle of the pill. */
    private fun drawCheck(canvas: Canvas) {
        val cx = pill.centerX()
        val cy = pill.centerY()
        val s = (minOf(pill.width(), pill.height()) - dp(6f)).coerceAtLeast(dp(6f)) / 10f // 10dp-wide mark in a 16dp pill
        check.reset()
        check.moveTo(cx - 4.5f * s, cy + 0.2f * s)
        check.lineTo(cx - 1.4f * s, cy + 3.2f * s)
        check.lineTo(cx + 4.5f * s, cy - 3.2f * s)
        canvas.drawPath(check, checkPaint)
    }
}
