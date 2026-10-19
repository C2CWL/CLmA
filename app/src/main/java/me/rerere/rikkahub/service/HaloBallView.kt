package me.rerere.rikkahub.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Artwork for the floating ball.
 *
 * At rest the ball is a **halo** on a backing disc: a translucent theme-surface disc with a hairline
 * outline and a soft shadow, and on it a slim ring in the ball's accent colour (the app theme's
 * primary, or the colour the user picked) with a faint bloom.
 *
 * While the agent is working the halo becomes the **orbit**: the ring drops back to a neutral
 * grey track and a bright light dot runs around it, dragging a comet tail of after-images and a
 * spray of particles. The dot's angular speed wobbles (see [HaloBallMotion]) so the motion reads
 * as alive rather than mechanical.
 *
 * A user-picked image, when there is one, keeps its place as the ball: the grey track and the
 * light dot are then drawn around the image's rim instead.
 *
 * Tucked against the screen edge the ball becomes a slim **handle** (see [setHandle]).
 *
 * Everything is drawn from the view's own bounds, so the caller only has to size the view.
 */
internal class HaloBallView(context: Context) : View(context) {

    companion object {
        // The two geometry fractions are shared with the settings page's miniature so the preview
        // cannot drift away from what the service actually draws.
        /** Ring centre-line radius, as a fraction of the view's half-size. */
        const val RING_RADIUS_FRACTION = 0.70f

        /** Ring stroke width, as a fraction of the view's half-size. */
        const val STROKE_FRACTION = 0.135f

        /** Track colour while working — the halo, dimmed to a neutral grey. */
        const val TRACK_GREY = 0xFFB4B8BE.toInt()

        /** Half-tucked "reply finished" dot radius, in stroke widths, and its backing rim in dp. */
        const val DONE_MARKER_SCALE = 1.1f
        const val DONE_MARKER_RIM_DP = 1.5f
        const val TRACK_ALPHA = 120

        const val DOT_SCALE = 1.15f        // dot radius, in stroke widths
        const val TRAIL_MAX_ALPHA = 195
        const val PARTICLE_MAX_ALPHA = 170
        const val PARTICLES_PER_SECOND = 34f
        const val MAX_PARTICLES = 72
        const val MAX_FRAME_SECONDS = 0.05f

        const val TWO_PI = (2.0 * PI).toFloat()
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val density = context.resources.displayMetrics.density
    private val iconClip = Path()
    private val iconSrc = Rect()
    private val iconDst = Rect()

    /** Ring colour when idle, and the tint of the comet tail while working. */
    var accentColor: Int = 0xFF6750A4.toInt()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Backing disc colour (theme surface, already translucent). */
    var surfaceColor: Int = HaloBallLook.withAlphaFraction(Color.WHITE, HaloBallLook.BACKING_ALPHA)
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Hairline around the backing disc (theme outlineVariant). */
    var outlineColor: Int = 0x33000000
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var handleMode = false
    private var handleOnRight = true
    private val handlePainter = EdgeHandlePainter(density)

    /** What the tucked handle signals (running turn / unseen finished reply); see [EdgeHandlePainter]. */
    var handleSignal: HaloBallLook.HandleSignal = HaloBallLook.HandleSignal.IDLE
        set(value) {
            if (field == value) return
            field = value
            if (handleMode || halfTuckedOnRight != null) invalidate()
        }

    /**
     * Non-null while the whole ball is tucked half off-screen (the half-ball tuck style), true
     * when it hugs the right edge. The ball keeps its full look, orbit included; an unseen
     * finished reply ([handleSignal] DONE) adds an accent marker on the visible half.
     */
    var halfTuckedOnRight: Boolean? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Draw only the slim edge handle ([on]) or the whole ball. [onRight] is the screen edge the
     * tucked window hugs; the service places the window with [HaloBallLook.tuckedX].
     */
    fun setHandle(on: Boolean, onRight: Boolean = handleOnRight) {
        if (handleMode == on && handleOnRight == onRight) return
        val wasHandle = handleMode
        handleMode = on
        handleOnRight = onRight
        // The orbit is not visible behind a handle, so it pauses there and resumes on wake.
        if (working && wasHandle && !on && isAttachedToWindow) {
            lastFrameMs = 0.0
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        invalidate()
    }

    private var icon: Bitmap? = null

    /** The picked image that replaces the halo, or null for the built-in look. */
    fun setIcon(bitmap: Bitmap?) {
        if (icon === bitmap) return
        icon = bitmap
        invalidate()
    }

    private var working = false

    /** True while the agent is working: the halo turns into an orbit for the light dot. */
    fun setWorking(value: Boolean) {
        if (working == value) return
        working = value
        if (value) {
            angle = 0f
            lastFrameMs = 0.0
            startedMs = 0.0
            spawnCarry = 0f
            particles.clear()
            if (isAttachedToWindow) Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            particles.clear()
        }
        invalidate()
    }

    // ---- Animation clock ----

    private var angle = 0f
    private var lastFrameMs = 0.0
    private var startedMs = 0.0
    private var spawnCarry = 0f
    private val particles = ArrayList<Particle>(MAX_PARTICLES)
    private val random = Random(System.nanoTime())

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!working || handleMode) return
            advance(frameTimeNanos / 1_000_000.0)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    private fun advance(nowMs: Double) {
        if (lastFrameMs == 0.0) {
            lastFrameMs = nowMs
            startedMs = nowMs
        }
        // Clamp the step so a stalled frame (screen off, service throttled) cannot teleport the
        // dot or spawn a burst of particles.
        val dt = ((nowMs - lastFrameMs) / 1000.0).coerceIn(0.0, MAX_FRAME_SECONDS.toDouble()).toFloat()
        lastFrameMs = nowMs
        val elapsed = ((nowMs - startedMs) / 1000.0).toFloat()

        angle = (angle + HaloBallMotion.omega(elapsed) * dt) % TWO_PI

        spawnCarry += dt * PARTICLES_PER_SECOND
        while (spawnCarry >= 1f) {
            spawnCarry -= 1f
            spawnParticle()
        }
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.life += dt
            if (p.life >= p.maxLife) {
                iterator.remove()
                continue
            }
            p.angle = (p.angle + p.angularVelocity * dt) % TWO_PI
            p.radius += p.radialVelocity * dt
        }
    }

    private fun spawnParticle() {
        val g = geometry() ?: return
        if (particles.size >= MAX_PARTICLES) return
        particles += Particle(
            angle = angle - random.nextFloat() * 0.45f,
            radius = g.ringRadius + (random.nextFloat() - 0.5f) * g.stroke * 2.2f,
            angularVelocity = -(0.5f + random.nextFloat() * 1.4f),
            radialVelocity = (random.nextFloat() - 0.5f) * g.stroke * 4f,
            life = 0f,
            maxLife = 0.35f + random.nextFloat() * 0.5f,
            size = g.stroke * (0.10f + random.nextFloat() * 0.16f),
            tint = if (random.nextFloat() < 0.55f) Color.WHITE else accentColor,
        )
    }

    // ---- Drawing ----

    private class Geometry(
        val cx: Float,
        val cy: Float,
        val r: Float,
        val ringRadius: Float,
        val stroke: Float,
    )

    private fun geometry(): Geometry? {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return null
        val r = min(w, h) / 2f
        return Geometry(
            cx = w / 2f,
            cy = h / 2f,
            r = r,
            ringRadius = r * RING_RADIUS_FRACTION,
            stroke = r * STROKE_FRACTION,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val g = geometry() ?: return
        if (handleMode) {
            drawHandle(canvas)
            return
        }
        drawBacking(canvas, g)
        val icon = icon
        if (icon != null) drawIcon(canvas, icon, g)
        if (working) {
            drawTrack(canvas, g)
            drawComet(canvas, g)
            drawParticles(canvas, g)
        } else if (icon == null) {
            drawHalo(canvas, g)
        }
        val tuckedOnRight = halfTuckedOnRight
        if (tuckedOnRight != null && handleSignal == HaloBallLook.HandleSignal.DONE) {
            drawDoneMarker(canvas, g, tuckedOnRight)
        }
    }

    /**
     * "A reply is waiting" on a half-tucked ball: a solid accent dot on the ring, on the visible
     * half, ringed in the backing colour so it reads over the ring or a picked image.
     */
    private fun drawDoneMarker(canvas: Canvas, g: Geometry, onRight: Boolean) {
        val (dx, dy) = HaloBallLook.doneMarkerDirection(onRight)
        val x = g.cx + dx * g.ringRadius
        val y = g.cy + dy * g.ringRadius
        val radius = g.stroke * DONE_MARKER_SCALE
        fillPaint.color = withAlpha(surfaceColor, 255)
        canvas.drawCircle(x, y, radius + DONE_MARKER_RIM_DP * density, fillPaint)
        fillPaint.color = withAlpha(accentColor, 255)
        canvas.drawCircle(x, y, radius, fillPaint)
    }

    /**
     * The resting backing: a soft shadow, a translucent surface disc and a hairline outline. Drawn
     * under the working orbit too, so starting a turn does not make the disc blink away; the orbit
     * itself is drawn exactly as before.
     */
    private fun drawBacking(canvas: Canvas, g: Geometry) {
        val backing = g.r * HaloBallLook.BACKING_RADIUS_FRACTION
        val offset = g.r * HaloBallLook.SHADOW_OFFSET_FRACTION
        val shadowR = g.r - offset
        if (shadowR > 0f) {
            fillPaint.shader = RadialGradient(
                g.cx,
                g.cy + offset,
                shadowR,
                intArrayOf(
                    withAlpha(Color.BLACK, HaloBallLook.SHADOW_ALPHA),
                    withAlpha(Color.BLACK, HaloBallLook.SHADOW_ALPHA),
                    withAlpha(Color.BLACK, 0),
                ),
                floatArrayOf(0f, ((backing - offset) / shadowR).coerceIn(0f, 1f), 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(g.cx, g.cy + offset, shadowR, fillPaint)
            fillPaint.shader = null
        }

        fillPaint.color = surfaceColor
        canvas.drawCircle(g.cx, g.cy, backing, fillPaint)

        val hairline = HaloBallLook.OUTLINE_WIDTH_DP * density
        ringPaint.color = outlineColor
        ringPaint.strokeWidth = hairline
        canvas.drawCircle(g.cx, g.cy, backing - hairline / 2f, ringPaint)
    }

    /** The idle look: a slim accent ring with a faint bloom, kept inside the backing disc. */
    private fun drawHalo(canvas: Canvas, g: Geometry) {
        val stroke = g.stroke * HaloBallLook.REST_RING_STROKE_SCALE
        val backing = g.r * HaloBallLook.BACKING_RADIUS_FRACTION
        val inner = (g.ringRadius - stroke / 2f).coerceAtLeast(0f)
        val outer = g.ringRadius + stroke / 2f
        fillPaint.shader = RadialGradient(
            g.cx,
            g.cy,
            backing,
            intArrayOf(
                withAlpha(accentColor, 0),
                withAlpha(accentColor, 0),
                withAlpha(accentColor, HaloBallLook.REST_BLOOM_ALPHA),
                withAlpha(accentColor, 0),
            ),
            floatArrayOf(
                0f,
                (inner / backing).coerceIn(0f, 1f),
                (outer / backing).coerceIn(0f, 1f),
                1f,
            ),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(g.cx, g.cy, backing, fillPaint)
        fillPaint.shader = null

        ringPaint.color = withAlpha(accentColor, HaloBallLook.REST_RING_ALPHA)
        ringPaint.strokeWidth = stroke
        canvas.drawCircle(g.cx, g.cy, g.ringRadius, ringPaint)
    }

    /**
     * The tucked look: a slim pill flush with the screen edge, centred vertically, showing
     * [handleSignal] (accent when idle).
     */
    private fun drawHandle(canvas: Canvas) {
        val size = width
        val handleW = (HaloBallLook.HANDLE_WIDTH_DP * density).toInt().coerceAtLeast(1)
        val handleH = (HaloBallLook.HANDLE_HEIGHT_DP * density).coerceAtMost(height.toFloat())
        val strip = (HaloBallLook.HANDLE_STRIP_DP * density).toInt()
        val left = HaloBallLook.handleLeft(handleOnRight, size, strip, handleW).toFloat()
        val top = (height - handleH) / 2f
        val animating = handlePainter.draw(canvas, left, top, handleW.toFloat(), handleH, accentColor, handleSignal)
        if (animating) postInvalidateOnAnimation()
    }

    /** The halo demoted to a neutral track the dot can run along. */
    private fun drawTrack(canvas: Canvas, g: Geometry) {
        ringPaint.color = withAlpha(TRACK_GREY, TRACK_ALPHA)
        ringPaint.strokeWidth = g.stroke * 0.8f
        canvas.drawCircle(g.cx, g.cy, g.ringRadius, ringPaint)
    }

    /** The light dot plus its comet tail of after-images. */
    private fun drawComet(canvas: Canvas, g: Geometry) {
        val dotRadius = g.stroke * DOT_SCALE
        for (i in HaloBallMotion.TRAIL_GHOSTS downTo 1) {
            val f = i / HaloBallMotion.TRAIL_GHOSTS.toFloat()
            val a = angle - HaloBallMotion.trailOffset(i)
            val x = g.cx + g.ringRadius * cos(a)
            val y = g.cy + g.ringRadius * sin(a)
            val radius = dotRadius * (1f - 0.62f * f)
            val alpha = (HaloBallMotion.fade(f) * TRAIL_MAX_ALPHA).toInt().coerceIn(0, 255)
            // White right behind the dot, warming into the ball's accent colour further back.
            val tint = if (f < 0.3f) blend(Color.WHITE, accentColor, f / 0.3f) else accentColor
            fillPaint.color = withAlpha(tint, alpha)
            canvas.drawCircle(x, y, radius, fillPaint)
        }

        val dx = g.cx + g.ringRadius * cos(angle)
        val dy = g.cy + g.ringRadius * sin(angle)
        // Bloom is capped at the gap between track and view edge so it never clips square.
        val bloom = (g.r - g.ringRadius).coerceAtLeast(dotRadius)
        fillPaint.shader = RadialGradient(
            dx,
            dy,
            bloom,
            intArrayOf(
                withAlpha(Color.WHITE, 255),
                withAlpha(accentColor, 110),
                withAlpha(accentColor, 0),
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(dx, dy, bloom, fillPaint)
        fillPaint.shader = null

        fillPaint.color = Color.WHITE
        canvas.drawCircle(dx, dy, dotRadius, fillPaint)
    }

    private fun drawParticles(canvas: Canvas, g: Geometry) {
        for (p in particles) {
            val life = p.life / p.maxLife
            val alpha = (HaloBallMotion.fade(life) * PARTICLE_MAX_ALPHA).toInt().coerceIn(0, 255)
            if (alpha <= 0) continue
            val orbit = (g.ringRadius + p.radius).coerceIn(-g.r, g.r)
            val x = g.cx + orbit * cos(p.angle)
            val y = g.cy + orbit * sin(p.angle)
            fillPaint.color = withAlpha(p.tint, alpha)
            canvas.drawCircle(x, y, p.size * (1f - 0.45f * life), fillPaint)
        }
    }

    /** The user's picked image, centre-cropped into the circle the halo would have drawn. */
    private fun drawIcon(canvas: Canvas, bitmap: Bitmap, g: Geometry) {
        val bw = bitmap.width
        val bh = bitmap.height
        if (bw <= 0 || bh <= 0) return
        val iconRadius = g.ringRadius + g.stroke / 2f
        // Rect, not RectF: Canvas only offers drawBitmap(Bitmap, Rect, Rect/Float…, Paint).
        val side = min(bw, bh)
        val left = (bw - side) / 2
        val top = (bh - side) / 2
        iconSrc.set(left, top, left + side, top + side)
        iconDst.set(
            (g.cx - iconRadius).toInt(),
            (g.cy - iconRadius).toInt(),
            (g.cx + iconRadius).toInt(),
            (g.cy + iconRadius).toInt(),
        )
        iconClip.reset()
        iconClip.addCircle(g.cx, g.cy, iconRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(iconClip)
        canvas.drawBitmap(bitmap, iconSrc, iconDst, bitmapPaint)
        canvas.restore()
    }

    private class Particle(
        var angle: Float,
        var radius: Float,
        var angularVelocity: Float,
        var radialVelocity: Float,
        var life: Float,
        var maxLife: Float,
        var size: Float,
        val tint: Int,
    )

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun blend(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = from ushr shift and 0xFF
            val b = to ushr shift and 0xFF
            return (a + (b - a) * f).toInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
