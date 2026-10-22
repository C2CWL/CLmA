package com.clma.app.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.floor
import com.clma.app.data.datastore.EdgeBarColor
import com.clma.app.service.EdgeBarLook.Signal

/**
 * The edge bar: a [EdgeBarLook.BAR_WIDTH_DP] x [EdgeBarLook.BAR_HEIGHT_DP] pill drawn flush with
 * the screen edge it sits on ([onRight]), vertically centred in a slightly larger touch area (the
 * view's own bounds). Everything is drawn inside the pill, never outside it:
 *
 *  - [Signal.IDLE]: the plain pill in the chosen [barColor] (light / dark per [darkMode] for the
 *    "follow theme" swatch);
 *  - [Signal.WORKING]: a rainbow streak (bright head, fading tail) runs clockwise around the
 *    inside of the pill's outline, over a faint track and a soft inner glow;
 *  - [Signal.DONE]: the pill turns green with a white check mark.
 *
 * The streak is the only animation, and it only runs while [Signal.WORKING] (the next frame is
 * requested from onDraw, so it stops by itself in any other state, and while the view is not
 * drawn). With animations switched off system-wide the streak rests in one place.
 */
internal class EdgeBarView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(EdgeBarLook.OUTLINE_DP)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
    }
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

    /** Ring centre line (rebuilt only when the pill moves) and the pieces cut from it each frame. */
    private val ringPath = Path()
    private val ringMeasure = PathMeasure()
    private var ringLength = 0f
    private val ringArc = RectF()
    private val ringFor = RectF()
    private val trackPiece = Path()
    private val streakPieces = Array(STREAK_PIECES) { Path() }
    private val streakColors = IntArray(STREAK_PIECES)

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
                drawRing(canvas, radius, animate)
                if (animate) postInvalidateOnAnimation()
            }
            Signal.DONE -> drawCheck(canvas)
        }
    }

    /**
     * The rainbow streak running clockwise around the outline. Its centre line is inset by half the
     * stroke, and everything (glow included) is clipped to the pill, so nothing is drawn outside
     * the bar. The faint track (the rest of the outline) is one seamless stroke; the streak itself
     * is cut into short pieces, each in its own colour / alpha from [EdgeBarLook.ringColor].
     */
    private fun drawRing(canvas: Canvas, radius: Float, animate: Boolean) {
        buildRingPath(radius)
        if (ringLength <= 0f) return
        val now = SystemClock.uptimeMillis()
        val phase = EdgeBarLook.ringPhase(now, animate)
        val hueShift = EdgeBarLook.ringHueShift(now, animate)
        val style = EdgeBarLook.ringStyle(barColor.argb(darkMode))
        val stroke = dp(EdgeBarLook.RING_STROKE_DP)

        val start = EdgeBarLook.ringStreakStart(phase)
        val end = EdgeBarLook.ringStreakEnd(phase)
        val piece = (end - start) / STREAK_PIECES
        // Each piece runs a hair into the next one, so the anti-aliased butt ends leave no seams.
        val overlap = SEGMENT_OVERLAP_PX / ringLength
        for (i in 0 until STREAK_PIECES) {
            val from = start + piece * i
            ringSpan(streakPieces[i], from, from + piece + overlap)
            streakColors[i] = EdgeBarLook.ringColor(from + piece / 2f, phase, hueShift, style)
        }
        ringSpan(trackPiece, end, start + 1f)

        clip.reset()
        clip.addRoundRect(pill, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        // Soft inner glow: two wider, fainter passes of the streak's colours under the stroke.
        for ((width, strength) in GLOW_PASSES) {
            ringPaint.strokeWidth = stroke * width
            for (i in 0 until STREAK_PIECES) {
                val color = streakColors[i]
                val alpha = Math.round((color ushr 24) * style.glow * strength)
                if (alpha < 2) continue
                ringPaint.color = (color and 0x00FFFFFF) or (alpha.coerceAtMost(255) shl 24)
                canvas.drawPath(streakPieces[i], ringPaint)
            }
        }
        ringPaint.strokeWidth = stroke
        ringPaint.color = EdgeBarLook.ringTrack(style)
        canvas.drawPath(trackPiece, ringPaint)
        for (i in 0 until STREAK_PIECES) {
            ringPaint.color = streakColors[i]
            canvas.drawPath(streakPieces[i], ringPaint)
        }
        canvas.restore()
    }

    /** Puts the stretch [from]..[to] of the outline (fractions, may run past 0 / 1) into [dst]. */
    private fun ringSpan(dst: Path, from: Float, to: Float) {
        dst.reset()
        val shift = floor(from)
        val a = (from - shift) * ringLength
        val b = (to - shift) * ringLength
        if (b <= ringLength) {
            ringMeasure.getSegment(a, b, dst, true)
        } else {
            ringMeasure.getSegment(a, ringLength, dst, true)
            ringMeasure.getSegment(0f, (b - ringLength).coerceAtMost(ringLength), dst, true)
        }
    }

    /**
     * The outline's centre line, clockwise from the top of the right side (u = 0, as in
     * [EdgeBarLook.ringColor]): down the right, round the bottom, up the left, round the top.
     * Rebuilt only when the pill moves.
     */
    private fun buildRingPath(radius: Float) {
        if (ringFor == pill && ringLength > 0f) return
        ringFor.set(pill)
        val inset = dp(EdgeBarLook.RING_STROKE_DP) / 2f
        val l = pill.left + inset
        val t = pill.top + inset
        val r = pill.right - inset
        val b = pill.bottom - inset
        val rc = (radius - inset).coerceAtLeast(0f)
        val d = rc * 2f
        ringPath.reset()
        ringPath.moveTo(r, t + rc)
        ringPath.lineTo(r, b - rc)
        ringArc.set(r - d, b - d, r, b)
        ringPath.arcTo(ringArc, 0f, 90f, false)
        ringPath.lineTo(l + rc, b)
        ringArc.set(l, b - d, l + d, b)
        ringPath.arcTo(ringArc, 90f, 90f, false)
        ringPath.lineTo(l, t + rc)
        ringArc.set(l, t, l + d, t + d)
        ringPath.arcTo(ringArc, 180f, 90f, false)
        ringPath.lineTo(r - rc, t)
        ringArc.set(r - d, t, r, t + d)
        ringPath.arcTo(ringArc, 270f, 90f, false)
        ringPath.close()
        ringMeasure.setPath(ringPath, true)
        ringLength = ringMeasure.length
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

    private companion object {
        /** Pieces the streak is cut into (~0.9dp each on a 16x64dp pill). */
        const val STREAK_PIECES = 72

        /** How far each piece overlaps the next one, in pixels. */
        const val SEGMENT_OVERLAP_PX = 0.6f

        /** Inner-glow passes: stroke width multiplier to alpha multiplier (times [EdgeBarLook.RingStyle.glow]). */
        val GLOW_PASSES = listOf(3f to 0.22f, 2f to 0.3f)
    }
}
