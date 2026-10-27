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
 * No-ops silently when SYSTEM_ALERT_WINDOW has not been granted.
 */
object VirtualDisplayPreviewWindow {

    private const val TAG = "VDPreview"

    /** ~15 fps. Smooth enough to follow what the agent is doing, cheap on a 1080p display. */
    private const val TICK_MS = 66L

    private val main = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var shownDisplayId = -1

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
            if (shownDisplayId == displayId) hideInternal()
        }
    }

    /** Close the preview regardless of which display it shows. */
    fun hide() {
        main.post { hideInternal() }
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

        val header = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(0x33FFFFFF) }
            addView(title)
            addView(close)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(28),
            )
        }

        val preview = PreviewView(app, info.displayId).apply {
            layoutParams = LinearLayout.LayoutParams(contentWidth, contentHeight)
        }

        val container = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
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
            addView(header)
            addView(preview)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            // Bottom-left, lifted above the gesture bar so system swipes stay usable.
            y = (screenH - contentHeight - dp(28) - dp(110)).coerceAtLeast(dp(80))
        }

        // Drag by the header only, so future in-place interaction (tap/swipe forwarding) can own
        // the frame itself.
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
                    current.x = (dragStartX + (event.rawX - dragDownX)).toInt().coerceAtLeast(0)
                    current.y = (dragStartY + (event.rawY - dragDownY)).toInt().coerceAtLeast(0)
                    runCatching { windowManager?.updateViewLayout(view, current) }
                    true
                }
                else -> true
            }
        }

        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        try {
            wm.addView(container, lp)
            root = container
            windowManager = wm
            params = lp
            shownDisplayId = info.displayId
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed", t)
        }
    }

    private fun hideInternal() {
        val view = root ?: return
        root = null
        params = null
        shownDisplayId = -1
        try {
            windowManager?.removeViewImmediate(view)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView failed", t)
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