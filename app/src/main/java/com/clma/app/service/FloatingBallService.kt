package com.clma.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.clma.app.FLOATING_BALL_NOTIFICATION_CHANNEL_ID
import com.clma.app.R
import com.clma.app.RouteActivity
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.ui.hooks.readStringPreference
import com.clma.app.ui.hooks.writeStringPreference
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.abs

private const val TAG = "FloatingBallService"

/**
 * The always-available edge bar (it replaced the floating ball; the class keeps its old name so
 * the manifest entry, the settings key and every caller stay unchanged).
 *
 * A slim pill ([EdgeBarView]) in a small TYPE_APPLICATION_OVERLAY window, flush with the left or
 * right screen edge, that survives across apps:
 *  - tap                 → bring the main app to the front (an existing task is resumed, not
 *                          duplicated; see [openMainApp]);
 *  - long press, drag    → move the bar up / down along its edge (dropped well across the
 *                          screen it switches edges); the position is saved on release;
 *  - anything else (a plain swipe) does nothing.
 *
 * It is also the "agent is working" light: [AgentOverlay] hands the signal here whenever the bar
 * is on screen (see [setWorking]); a finished reply turns the bar green until the bar is tapped
 * (which opens the app, where the reply is).
 *
 * Runs as a `specialUse` foreground service so the overlay is not evicted when the app goes to
 * the background. The overlay window itself is what exempts us from Android 14+ background
 * activity-start restrictions, so the app can be brought to the front from here at any time.
 *
 * Deliberately a no-op when SYSTEM_ALERT_WINDOW has not been granted: the caller (settings page /
 * boot receiver) is expected to have checked, but a stale start must never crash or leave a
 * headless foreground service behind.
 */
class FloatingBallService : Service(), KoinComponent {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.FLOATING_BALL_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.FLOATING_BALL_STOP"

        /** A generation turn started / finished: the bar becomes the "agent is working" light. */
        const val ACTION_WORKING_ON = "me.rerere.rikkahub.action.FLOATING_BALL_WORKING_ON"
        const val ACTION_WORKING_OFF = "me.rerere.rikkahub.action.FLOATING_BALL_WORKING_OFF"
        const val NOTIFICATION_ID = 2003

        // Same keys the floating ball used, so the bar opens on the side / height the ball was on.
        private const val PREF_X = "floating_ball_x"
        private const val PREF_Y = "floating_ball_y"

        /** Starts (or re-starts) the bar. Idempotent — a running service just re-shows its view. */
        fun start(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = ACTION_START
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "start failed", it) }
        }

        /**
         * Stops the bar. Uses startService (not stopService) so the running instance handles
         * ACTION_STOP on its own looper and tears its overlay view down first.
         */
        fun stop(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "stop failed", it) }
        }

        /**
         * True while the bar is actually on screen. [AgentOverlay] reads this to decide whether
         * the bar can carry the "agent is working" signal or whether it still needs its
         * top-of-screen pill as a fallback.
         */
        @Volatile
        var isShowing: Boolean = false
            private set

        /**
         * Tells the bar that a generation turn started / finished. A no-op while the bar is not
         * on screen (so the caller keeps its own indicator instead of resurrecting a dead service).
         */
        fun setWorking(context: Context, working: Boolean) {
            if (!isShowing) return
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = if (working) ACTION_WORKING_ON else ACTION_WORKING_OFF
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "setWorking failed", it) }
        }
    }

    private val settingsStore: SettingsStore by inject()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var barView: EdgeBarView? = null
    private var barParams: WindowManager.LayoutParams? = null

    /** True between ACTION_WORKING_ON and ACTION_WORKING_OFF. */
    private var working = false

    /** A turn finished and the user has not tapped the bar (opened the app) since. */
    private var unseenDone = false

    /** Set by the long-press timer; from then on the gesture moves the bar instead of tapping. */
    private var dragArmed = false

    private val longPressRunnable = Runnable {
        val view = barView ?: return@Runnable
        dragArmed = true
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onCreate() {
        super.onCreate()
        // Colour changes from the settings page repaint the running bar live.
        scope.launch {
            settingsStore.settingsFlow.collect { settings ->
                barView?.barColor = settings.floatingBallBarColor
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_WORKING_ON -> {
                working = true
                unseenDone = false
                if (barView == null) stopSelf() else syncBar()
                return START_NOT_STICKY
            }

            ACTION_WORKING_OFF -> {
                working = false
                // The bar turns green until the user taps it (which opens the app).
                unseenDone = true
                if (barView == null) stopSelf() else syncBar()
                return START_NOT_STICKY
            }

            else -> {
                if (!Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!startForegroundCompat()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                showBar()
            }
        }
        // NOT sticky: if the user turned the bar off we must not resurrect it after a kill.
        // App start / boot are what bring it back when the setting is still on.
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation / resize: keep the side, re-clamp the height against the new screen.
        mainHandler.post {
            val view = barView ?: return@post
            val params = barParams ?: return@post
            params.y = clampTop(params.y)
            updateLayout(view, params)
            updateGestureExclusion(view)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        hideBar()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    // ---- Overlay view ----

    @SuppressLint("RtlHardcoded")
    private fun showBar() {
        if (barView != null) return
        val wm = windowManager() ?: return
        val width = dpPx(EdgeBarLook.TOUCH_WIDTH_DP)
        val height = dpPx(EdgeBarLook.TOUCH_HEIGHT_DP)
        val screen = screenSize()
        val onRight = EdgeBarLook.onRightFromSavedX(
            readStringPreference(PREF_X)?.toIntOrNull(),
            screen.width,
        )
        val savedTop = readStringPreference(PREF_Y)?.toIntOrNull()
            ?: EdgeBarLook.defaultTop(height, screen.height)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Placed by gravity, so the bar stays flush with its edge across rotations / resizes.
            gravity = Gravity.TOP or (if (onRight) Gravity.RIGHT else Gravity.LEFT)
            x = 0
            y = savedTop
        }
        params.y = clampTop(params.y)

        val view = EdgeBarView(this).apply {
            this.onRight = onRight
            barColor = settingsStore.settingsFlow.value.floatingBallBarColor
            contentDescription = getString(R.string.setting_floating_ball_bar_a11y)
            isClickable = true
            isHapticFeedbackEnabled = true
        }
        try {
            wm.addView(view, params)
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed", t)
            return
        }
        barView = view
        barParams = params
        isShowing = true
        attachTouch(view, params)
        // Touches are handled in attachTouch; this is what TalkBack / switch access trigger.
        view.setOnClickListener { openMainApp() }
        syncBar()
    }

    private fun hideBar() {
        val view = barView ?: return
        mainHandler.removeCallbacks(longPressRunnable)
        clearGestureExclusion(view)
        barView = null
        barParams = null
        isShowing = false
        working = false
        unseenDone = false
        dragArmed = false
        val wm = windowManager() ?: return
        runCatching { wm.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "removeView failed", it) }
    }

    /** Pushes the current status onto the bar. */
    private fun syncBar() {
        val view = barView ?: return
        view.signal = EdgeBarLook.signal(working = working, unseenDone = unseenDone)
        updateGestureExclusion(view)
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun attachTouch(view: EdgeBarView, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startY = 0
        /** The finger moved before the long press armed: a swipe, which does nothing. */
        var swiped = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startY = params.y
                    swiped = false
                    dragArmed = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.postDelayed(longPressRunnable, EdgeBarLook.LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (dragArmed) {
                        // Vertical only while dragging; the side is decided on release.
                        val y = clampTop((startY + dy).toInt())
                        if (y != params.y) {
                            params.y = y
                            updateLayout(view, params)
                        }
                    } else if (!swiped && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        // Low sensitivity: moving before the long press is recognised is a plain
                        // swipe — never a drag, never a tap.
                        swiped = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                    when {
                        dragArmed -> {
                            val screen = screenSize()
                            val onRight = if (cancelled) {
                                view.onRight
                            } else {
                                EdgeBarLook.onRightAfterDrag(event.rawX, screen.width, view.onRight)
                            }
                            if (onRight != view.onRight) {
                                view.onRight = onRight
                                params.gravity = Gravity.TOP or (if (onRight) Gravity.RIGHT else Gravity.LEFT)
                                updateLayout(view, params)
                            }
                            updateGestureExclusion(view)
                            savePosition(onRight, params)
                        }
                        // A cancelled gesture (e.g. taken by the system) is never a tap.
                        !cancelled && !swiped -> openMainApp()
                    }
                    dragArmed = false
                    swiped = false
                    true
                }

                else -> false
            }
        }
    }

    private fun savePosition(onRight: Boolean, params: WindowManager.LayoutParams) {
        val screen = screenSize()
        writeStringPreference(PREF_X, EdgeBarLook.savedXFor(onRight, screen.width, params.width).toString())
        writeStringPreference(PREF_Y, params.y.toString())
    }

    /**
     * Brings the main app to the front, the way its launcher icon does: the package's launch intent
     * (whichever launcher alias is currently enabled) with FLAG_ACTIVITY_NEW_TASK, so an existing
     * task is resumed instead of a second one being created; REORDER_TO_FRONT + SINGLE_TOP so a
     * running main activity is brought up rather than duplicated. Also counts as "seen" for a
     * finished reply, so the green done state clears.
     */
    private fun openMainApp() {
        if (unseenDone) {
            unseenDone = false
            syncBar()
        }
        try {
            val intent = (packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, RouteActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "openMainApp failed", t)
        }
    }

    // ---- Geometry ----

    private class ScreenSize(val width: Int, val height: Int, val topInset: Int, val bottomInset: Int)

    private fun windowManager(): WindowManager? = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private fun dpPx(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()

    /** Full screen size plus the status / navigation bar heights the bar must stay clear of. */
    private fun screenSize(): ScreenSize {
        val wm = windowManager()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            runCatching {
                val metrics = wm.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val bounds = metrics.bounds
                return ScreenSize(bounds.width(), bounds.height(), insets.top, insets.bottom)
            }
        }
        val dm = resources.displayMetrics
        @SuppressLint("DiscouragedApi", "InternalInsetResource")
        val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val status = if (statusId > 0) resources.getDimensionPixelSize(statusId) else dpPx(24f)
        return ScreenSize(dm.widthPixels, dm.heightPixels, status, 0)
    }

    private fun clampTop(top: Int): Int {
        val screen = screenSize()
        return EdgeBarLook.clampTop(
            top = top,
            windowHeight = dpPx(EdgeBarLook.TOUCH_HEIGHT_DP),
            screenHeight = screen.height,
            topInset = screen.topInset,
            bottomInset = screen.bottomInset,
            margin = dpPx(EdgeBarLook.EDGE_MARGIN_DP),
        )
    }

    private fun updateLayout(view: View, params: WindowManager.LayoutParams) {
        val wm = windowManager() ?: return
        runCatching { wm.updateViewLayout(view, params) }
    }

    /**
     * Keeps the system edge-back gesture off the bar's own small touch area (API 29+, well under
     * the 200dp per-edge budget), so a long-press drag on the bar is not taken as "back" and
     * cancelled. Back still works everywhere else along the edge.
     */
    private fun updateGestureExclusion(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val params = barParams ?: return
        view.systemGestureExclusionRects = listOf(Rect(0, 0, params.width, params.height))
    }

    private fun clearGestureExclusion(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        view.systemGestureExclusionRects = emptyList()
    }

    // ---- Foreground ----

    private fun startForegroundCompat(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // Some OEM ROMs reject the specialUse FGS type even with the manifest declaration.
            Log.e(TAG, "Failed to start foreground service", e)
            false
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val stopIntent = Intent(this, FloatingBallService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, FLOATING_BALL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_floating_ball_running))
            .setContentText(getString(R.string.notification_floating_ball_hint))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .addAction(0, getString(R.string.notification_floating_ball_stop), stopPendingIntent)
            .build()
    }
}
