package com.clma.app.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.clma.app.R
import com.clma.app.data.ai.tools.local.VirtualDisplayController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Draggable "what the agent sees" window for a running virtual display.
 *
 * The frame source is the same [android.media.ImageReader] the display was created with (see
 * [VirtualDisplayController]), polled at ~15 fps into a single reusable bitmap — no Surface
 * switching and no extra decode, so screenshots keep working while the preview is up.
 *
 * Placement: bottom-left with a margin, i.e. clear of the top-center agent pill ([AgentOverlay])
 * and the left/right edge bar ([FloatingBallService]). The window is a single instance; creating a
 * second display replaces the preview, destroying the shown display closes it.
 *
 * Two windows on purpose: the frame is FLAG_NOT_TOUCHABLE so it never swallows a tap, swipe or
 * long-press aimed at whatever the agent is driving underneath, while a narrow bar above it stays
 * touchable for drag / close / resize. Trade-off: pinch-to-zoom is gone, the bar keeps −/+.
 *
 * No-ops silently when SYSTEM_ALERT_WINDOW has not been granted.
 */
object VirtualDisplayPreviewWindow {

    private const val TAG = "VDPreview"

    /** ~15 fps. Smooth enough to follow what the agent is doing, cheap on a 1080p display. */
    private const val TICK_MS = 66L

    /** Long enough for the compositor to drop the layer; a same-frame capture still sees it. */
    private const val CAPTURE_SETTLE_MS = 120L

    private val main = Handler(Looper.getMainLooper())

    /** Touchable bar: the only part of the preview that takes input. */
    private var root: View? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null

    /** Frame, kept [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] so agent gestures pass through. */
    private var body: View? = null
    private var bodyParams: WindowManager.LayoutParams? = null
    private var shownInfo: VirtualDisplayController.DisplayInfo? = null
    private var appContext: Context? = null

    // Survives hide/show, so a screenshot's temporary detach does not reset size or position.
    private var lastWidth = 0
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    private var dragStartX = 0
    private var dragStartY = 0
    private var dragDownX = 0f
    private var dragDownY = 0f

    /** Show (or replace) the preview for [info]. Safe to call from any thread. */
    fun show(context: Context, info: VirtualDisplayController.DisplayInfo) {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            Log.d(TAG, "show: SYSTEM_ALERT_WINDOW not granted, no-op")
            return
        }
        main.post { showInternal(app, info) }
    }

    /** Close the preview if it is currently showing [displayId] (display was destroyed). */
    fun hideIfShowing(displayId: Int) {
        main.post {
            if (shownInfo?.displayId == displayId) hideInternal()
        }
    }

    /** Close the preview regardless of which display it shows. */
    fun hide() {
        main.post { hideInternal() }
    }

    /**
     * Run [block] with the preview detached, then put it back.
     *
     * A physical-screen capture composites every overlay on the default display, so a visible
     * picture would land in the model's image as a scaled copy of the virtual display and every
     * coordinate read off that image would be off. Detaching is a hard guarantee (alpha /
     * visibility may still be composited); the display keeps running — only the windows go away.
     */
    suspend fun <T> hiddenDuring(block: suspend () -> T): T {
        val restore = withContext<Pair<Context, VirtualDisplayController.DisplayInfo>?>(Dispatchers.Main) {
            val info = shownInfo ?: return@withContext null
            val app = appContext ?: return@withContext null
            hideInternal()
            app to info
        } ?: return block()
        // Two frames plus slack: a capture on this frame could still include the layer.
        delay(CAPTURE_SETTLE_MS)
        return try {
            block()
        } finally {
            val (app, info) = restore
            main.post { showInternal(app, info) }
        }
    }

    @Suppress("SetTextI18n")
    private fun showInternal(app: Context, info: VirtualDisplayController.DisplayInfo) {
        // A display swap replaces the window rather than stacking a second one.
        hideInternal()

        val density = app.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val screenW = app.resources.displayMetrics.widthPixels
        val screenH = app.resources.displayMetrics.heightPixels

        var contentWidth = (screenW * 0.42f).toInt().coerceIn(dp(120), dp(320))
        var contentHeight = (contentWidth.toLong() * info.height / info.width.coerceAtLeast(1)).toInt()
        val maxHeight = (screenH * 0.5f).toInt()
        if (contentHeight > maxHeight) {
            contentHeight = maxHeight
            contentWidth = (contentHeight.toLong() * info.width / info.height.coerceAtLeast(1)).toInt()
        }

        val title = TextView(app).apply {
            text = app.getString(R.string.virtual_display_preview_title, info.width, info.height)
            setTextColor(0xFFE6E6E6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        val close = TextView(app).apply {
            text = "✕"
            setTextColor(0xFFB0B0B0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            contentDescription = app.getString(R.string.virtual_display_preview_close)
            setOnClickListener { hide() }
        }

        // Own window, so the frame below it can be touch-transparent.
        val header = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xF01C1C1C.toInt())
                setStroke(dp(1), 0x26FFFFFF)
            }
            addView(title)
            addView(close)
        }

        val preview = PreviewView(app, info.displayId).apply {
            // A picture, not something to act on: keep it out of the model's window tree.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xF01C1C1C.toInt())
                setStroke(dp(1), 0x26FFFFFF)
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
        }

        // Scale the preview only: VD geometry and agent screenshot coordinates never change.
        fun resize(requestedWidth: Int) {
            val current = params ?: return
            val view = root ?: return
            val ratio = info.height.toFloat() / info.width.coerceAtLeast(1)
            val availableHeight = (screenH - dp(80)).coerceAtLeast(1)
            val maxWidth = minOf(screenW - dp(16), (availableHeight / ratio).toInt()).coerceAtLeast(1)
            val minWidth = dp(120).coerceAtMost(maxWidth)
            contentWidth = requestedWidth.coerceIn(minWidth, maxWidth)
            contentHeight = (contentWidth * ratio).toInt().coerceAtLeast(1)
            // The two windows are sized independently: the bar stays narrow, the frame follows.
            current.width = minOf(contentWidth, dp(170))
            bodyParams?.let { bodyLp ->
                bodyLp.width = contentWidth
                bodyLp.height = contentHeight
            }
            current.x = current.x.coerceIn(0, (screenW - contentWidth).coerceAtLeast(0))
            current.y = current.y.coerceIn(0, (screenH - contentHeight - dp(28)).coerceAtLeast(0))
            runCatching { windowManager?.updateViewLayout(view, current) }
            moveBody(current.x, current.y)
            lastWidth = contentWidth
        }
        fun scaleButton(label: String, description: Int, factor: Float) = TextView(app).apply {
            text = label
            setTextColor(0xFFE6E6E6.toInt())
            gravity = Gravity.CENTER
            contentDescription = app.getString(description)
            layoutParams = LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener { resize((contentWidth * factor).toInt()) }
        }
        header.addView(scaleButton("−", R.string.virtual_display_preview_shrink, 0.8f), 1)
        header.addView(scaleButton("+", R.string.virtual_display_preview_enlarge, 1.25f), 2)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // Bar: narrow on purpose — it is the only window that can swallow a touch.
        val lp = WindowManager.LayoutParams(
            minOf(contentWidth, dp(170)),
            dp(28),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            // Bottom-left, lifted above the gesture bar so system swipes stay usable.
            y = (screenH - contentHeight - dp(28) - dp(110)).coerceAtLeast(dp(80))
            // Re-open where the user last left it: a detach/attach cycle must not jump.
            if (lastX != Int.MIN_VALUE) {
                x = lastX
                y = lastY
            }
        }

        // Frame: never touchable, so a tap aimed at the app below is not intercepted.
        val bodyLp = WindowManager.LayoutParams(
            contentWidth,
            contentHeight,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lp.x
            y = lp.y + dp(28)
        }

        // Drag by the bar only; the frame below is touch-transparent, so it never forwards touches.
        header.setOnTouchListener { _, event ->
            val current = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragDownX = event.rawX
                    dragDownY = event.rawY
                    dragStartX = current.x
                    dragStartY = current.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val view = root ?: return@setOnTouchListener false
                    current.x = (dragStartX + (event.rawX - dragDownX)).toInt().coerceIn(0, (screenW - contentWidth).coerceAtLeast(0))
                    current.y = (dragStartY + (event.rawY - dragDownY)).toInt().coerceIn(0, (screenH - contentHeight - dp(28)).coerceAtLeast(0))
                    runCatching { windowManager?.updateViewLayout(view, current) }
                    moveBody(current.x, current.y)
                    true
                }
                else -> true
            }
        }

        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        try {
            wm.addView(preview, bodyLp)
            wm.addView(header, lp)
            root = header
            body = preview
            windowManager = wm
            params = lp
            bodyParams = bodyLp
            appContext = app
            shownInfo = info
            resize(if (lastWidth > 0) lastWidth else contentWidth)
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed", t)
            hideInternal()
        }
    }

    /** Keep the frame glued under the bar. */
    private fun moveBody(x: Int, y: Int) {
        val bodyView = body ?: return
        val bodyLp = bodyParams ?: return
        bodyLp.x = x
        bodyLp.y = y + (params?.height ?: 0)
        runCatching { windowManager?.updateViewLayout(bodyView, bodyLp) }
        lastX = x
        lastY = y
    }

    private fun hideInternal() {
        val wm = windowManager
        val barView = root
        val bodyView = body
        if (barView == null && bodyView == null) return
        root = null
        body = null
        params = null
        bodyParams = null
        shownInfo = null
        if (wm != null) {
            if (barView != null) runCatching { wm.removeViewImmediate(barView) }
            if (bodyView != null) runCatching { wm.removeViewImmediate(bodyView) }
        }
        windowManager = null
    }

    /**
     * Draws the newest frame of [displayId], scaled into the view. The bitmap is reused between
     * frames (one allocation per geometry change) and filled on the main thread — a 1080p copy is
     * ~1 ms, well inside a 66 ms tick.
     */
    private class PreviewView(
        context: Context,
        private val displayId: Int,
    ) : View(context) {

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val source = Rect()
        private val destination = Rect()
        private var buffer: Bitmap? = null
        private var lastSeq = -1L
        private val handler = Handler(Looper.getMainLooper())
        private val ticker = object : Runnable {
            override fun run() {
                pump()
                handler.postDelayed(this, TICK_MS)
            }
        }

        init {
            setBackgroundColor(Color.BLACK)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            handler.post(ticker)
        }

        override fun onDetachedFromWindow() {
            handler.removeCallbacks(ticker)
            super.onDetachedFromWindow()
        }

        private fun pump() {
            val spec = VirtualDisplayController.frameSpec(displayId) ?: return
            if (spec.seq == lastSeq) return
            val target = ensureBuffer(spec) ?: return
            if (!VirtualDisplayController.copyFrame(displayId, target)) return
            lastSeq = spec.seq
            source.set(0, 0, spec.width.coerceAtMost(target.width), spec.height.coerceAtMost(target.height))
            invalidate()
        }

        private fun ensureBuffer(spec: VirtualDisplayController.FrameSpec): Bitmap? {
            val current = buffer
            if (current != null && !current.isRecycled &&
                current.width == spec.bufferWidth && current.height == spec.bufferHeight
            ) {
                return current
            }
            val created = runCatching {
                Bitmap.createBitmap(spec.bufferWidth, spec.bufferHeight, Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return null
            current?.let { if (!it.isRecycled) it.recycle() }
            buffer = created
            return created
        }

        override fun onDraw(canvas: Canvas) {
            val bitmap = buffer ?: return
            if (bitmap.isRecycled || source.isEmpty) return
            destination.set(0, 0, width, height)
            canvas.drawBitmap(bitmap, source, destination, paint)
        }
    }
}