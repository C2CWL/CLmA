package com.clma.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.clma.app.R
import kotlin.uuid.Uuid

/**
 * Lightweight top-of-screen pill that shows while a generation turn is active so the
 * user always knows when the agent is driving the UI. Uses TYPE_APPLICATION_OVERLAY
 * with FLAG_NOT_TOUCHABLE so it never blocks user gestures. No-ops silently if
 * SYSTEM_ALERT_WINDOW has not been granted — overlay is purely informational.
 *
 * When the edge bar is on screen the pill steps aside: a light sweep gliding through the bar *is*
 * the working indicator there (see [FloatingBallService.setWorking]). The pill is the fallback
 * for users who never turned the edge bar on.
 */
object AgentOverlay {
    private const val TAG = "AgentOverlay"

    @Volatile private var view: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Conversations whose turn is currently running.
     *
     * A turn per conversation, not one turn at a time: with several chats replying at once, the
     * old single boolean let the FIRST one to finish clear the indicator while the others were
     * still streaming — the edge bar dropped its "replying" sweep, turned green for a reply that
     * was not the one the user was watching, and once the green was dismissed it fell back to a
     * plain idle pill ("只剩一个灰条"). Counting the live turns keeps the sweep up until the last
     * one ends, and only then offers the "done" state.
     *
     * Mutated only under `synchronized(workingConversations)`; [show]/[hide] run on the generation
     * flow's own dispatcher, [reset] on the service's main looper.
     */
    private val workingConversations = HashSet<String>()

    /** True while at least one conversation is generating (read by the bar / tests). */
    @Volatile
    var isWorking: Boolean = false
        private set

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * A turn started for [conversationId] (null = an unattributed turn, treated as its own key so
     * a caller that has no conversation still gets the indicator).
     */
    fun show(context: Context, conversationId: Uuid? = null, text: String? = null) {
        val app = context.applicationContext
        val label = text ?: app.getString(R.string.agent_overlay_working)
        if (!canShow(app)) {
            Log.d(TAG, "show: SYSTEM_ALERT_WINDOW not granted, no-op")
            return
        }
        val key = conversationId?.toString() ?: UNATTRIBUTED
        val alreadyWorking = synchronized(workingConversations) {
            val wasWorking = workingConversations.isNotEmpty()
            workingConversations.add(key)
            isWorking = true
            wasWorking
        }
        // The edge bar carries the signal whenever it is up; only fall back to the pill (which is
        // what users found abrupt) when there is no bar to animate. A second concurrent turn needs
        // no extra UI — the bar is already sweeping.
        if (FloatingBallService.isShowing) {
            if (!alreadyWorking) FloatingBallService.setWorking(app, true)
            return
        }
        if (!alreadyWorking) mainHandler.post { showInternal(app, label) }
    }

    /** The turn for [conversationId] ended; only the last one clears the indicator. */
    fun hide(context: Context, conversationId: Uuid? = null) {
        val app = context.applicationContext
        val key = conversationId?.toString() ?: UNATTRIBUTED
        val stillWorking = synchronized(workingConversations) {
            workingConversations.remove(key)
            isWorking = workingConversations.isNotEmpty()
            isWorking
        }
        if (stillWorking) return
        // No-op unless the edge bar is actually up (see setWorking), so this is safe unconditionally.
        FloatingBallService.setWorking(app, false)
        mainHandler.post { hideInternal(app) }
    }

    /** All turns are over (service teardown) — drop any straggler ids. */
    fun reset() {
        synchronized(workingConversations) {
            workingConversations.clear()
            isWorking = false
        }
    }

    private const val UNATTRIBUTED = "<unattributed>"

    @SuppressLint("RtlHardcoded")
    private fun showInternal(app: Context, text: String) {
        val existing = view
        if (existing != null) {
            existing.text = text
            return
        }
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val tv = TextView(app).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val pad = (12 * app.resources.displayMetrics.density).toInt()
            val padV = (6 * app.resources.displayMetrics.density).toInt()
            setPadding(pad, padV, pad, padV)
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(0xCC202020.toInt())
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Far enough below the status bar / camera punch-hole that the pill never
            // overlaps the system clock or notch on edge-to-edge devices.
            y = (64 * app.resources.displayMetrics.density).toInt()
        }
        try {
            wm.addView(tv, params)
            view = tv
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed", t)
        }
    }

    private fun hideInternal(app: Context) {
        val v = view ?: return
        view = null
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        try {
            wm.removeViewImmediate(v)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView failed", t)
        }
    }
}
