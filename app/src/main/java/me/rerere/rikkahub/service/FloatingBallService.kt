package me.rerere.rikkahub.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.FLOATING_BALL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.pages.quickchat.QuickChatActivity
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.findPresetTheme
import me.rerere.rikkahub.ui.theme.findThemeById
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.math.abs

private const val TAG = "FloatingBallService"

/**
 * Always-available floating ball.
 *
 * A tiny circular overlay (TYPE_APPLICATION_OVERLAY) that survives across apps so the user can
 * reach the agent without leaving what they are doing:
 *  - tap    → open [QuickChatActivity], a half-screen quick-chat panel
 *  - long   → open the same panel with voice input already recording
 *  - drag   → move it; it snaps to the nearest horizontal edge and remembers where it was
 *
 * While a generation turn runs the ball is also the "agent is working" light: its halo turns grey
 * and a light dot orbits it (see [HaloBallView]). That replaces [AgentOverlay]'s top-of-screen
 * pill whenever the ball is on screen; the pill remains the fallback for users without the ball.
 *
 * Runs as a `specialUse` foreground service so the overlay is not evicted when the app goes to
 * the background. The overlay window itself is what exempts us from Android 14+ background
 * activity-start restrictions, so the panel can be launched from here at any time.
 *
 * Deliberately a no-op when SYSTEM_ALERT_WINDOW has not been granted: the caller (settings page /
 * boot receiver) is expected to have checked, but a stale start must never crash or leave a
 * headless foreground service behind.
 */
class FloatingBallService : Service(), KoinComponent {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.FLOATING_BALL_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.FLOATING_BALL_STOP"

        /** The quick-chat panel came up / went away: the ball hides while it is on screen. */
        const val ACTION_PANEL_OPEN = "me.rerere.rikkahub.action.FLOATING_BALL_PANEL_OPEN"
        const val ACTION_PANEL_CLOSE = "me.rerere.rikkahub.action.FLOATING_BALL_PANEL_CLOSE"

        /** A generation turn started / finished: the ball becomes the "agent is working" light. */
        const val ACTION_WORKING_ON = "me.rerere.rikkahub.action.FLOATING_BALL_WORKING_ON"
        const val ACTION_WORKING_OFF = "me.rerere.rikkahub.action.FLOATING_BALL_WORKING_OFF"
        const val NOTIFICATION_ID = 2003

        private const val PREF_X = "floating_ball_x"
        private const val PREF_Y = "floating_ball_y"
        private const val LONG_PRESS_MS = 420L
        private const val BALL_SIZE_DP = 56

        /** Used only if the app theme cannot be resolved; the default look follows the theme. */
        private const val FALLBACK_BALL_COLOR = 0xFF6750A4.toInt()

        private const val IDLE_ANIM_MS = 220L

        /**
         * The quick-chat panel unfolds out of the ball, so the ball must stay put for the first
         * beat of that (the activity takes a moment to come up) and only then fold away — a
         * little after the panel asks to be shown, so the two animations overlap rather than the
         * ball vanishing into an empty screen.
         */
        private const val BALL_COLLAPSE_DELAY_MS = 120L

        /** The ball folding away into the panel, and popping back out of it. */
        private const val BALL_COLLAPSE_MS = 180L
        private const val BALL_COLLAPSE_END_SCALE = 0.35f
        private const val BALL_SPAWN_MS = 340L
        private const val BALL_SPAWN_START_SCALE = 0.4f
        private const val BALL_SPAWN_OVERSHOOT = 1.6f

        // Same SharedPreferences file/key the UI uses (see ui/hooks/ColorMode.kt). The ball watches
        // it so a light/dark switch re-tints the ball without restarting the service.
        private const val PREFS_NAME = "rikkahub.preferences"
        private const val COLOR_MODE_KEY = "colorMode"
        private const val AMOLED_DARK_KEY = "amoledDark"

        /**
         * Where a user-picked ball image lives. The app's private files dir needs no storage
         * permission and is wiped on uninstall, so a stale settings path can never leak data.
         */
        fun iconFile(context: Context): File = File(context.filesDir, "floating_ball_icon")

        /** On-screen strip of a tucked ball: just the slim handle and a little touch room. */
        private const val TUCKED_STRIP_DP = HaloBallLook.HANDLE_STRIP_DP

        /** Starts (or re-starts) the ball. Idempotent — a running service just re-shows its view. */
        fun start(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = ACTION_START
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "start failed", it) }
        }

        /**
         * Stops the ball. Uses startService (not stopService) so the running instance handles
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
         * Tells a running ball that the quick-chat panel opened / closed, so it can step out of
         * the way (hidden while the panel is up) and come back afterwards. Deliberately a plain
         * startService: the ball is already a foreground service whenever the panel can be open,
         * and a no-op command on a dead instance must not resurrect it.
         */
        fun notifyPanel(context: Context, open: Boolean) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = if (open) ACTION_PANEL_OPEN else ACTION_PANEL_CLOSE
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "notifyPanel failed", it) }
        }

        /**
         * True while the overlay view is actually on screen. [AgentOverlay] reads this to decide
         * whether the ball can carry the "agent is working" signal or whether it still needs the
         * top-of-screen pill as a fallback.
         */
        @Volatile
        var isShowing: Boolean = false
            private set

        /**
         * Tells the ball that a generation turn started / finished, so it can turn its halo into
         * the orbiting-light-dot "thinking" state. A no-op while the ball is not on screen (so
         * the caller keeps its own indicator instead of resurrecting a dead service).
         */
        fun setWorking(context: Context, working: Boolean) {
            if (!isShowing) return
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = if (working) ACTION_WORKING_ON else ACTION_WORKING_OFF
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "setWorking failed", it) }
        }

        /**
         * True while the user is holding the ball down to talk.
         *
         * The ball's long-press opens the quick-chat panel and starts recording; recording ends the
         * moment the finger leaves the ball. The panel owns the recorder (ASR is a Compose-side
         * thing), so it observes this flag rather than the service poking it: both live in the same
         * process, so a plain StateFlow is race-free, whereas an intent hand-off would not be — a
         * quick release would otherwise land before the panel had even come up.
         */
        private val _voiceHold = MutableStateFlow(false)
        val voiceHoldActive: StateFlow<Boolean> = _voiceHold.asStateFlow()
    }

    private val settingsStore: SettingsStore by inject()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ballView: HaloBallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var longPressFired = false

    /** True between ACTION_WORKING_ON and ACTION_WORKING_OFF — the driver of the orbit animation. */
    private var working = false

    /**
     * Pushes the orbit animation state onto the view. The dot only spins while a turn is running
     * *and* the quick-chat panel is not covering the ball — an invisible ball has no business
     * redrawing itself 60 times a second.
     */
    private fun syncWorking() {
        ballView?.setWorking(working && !panelOpen)
    }

    /** Cache so repainting on unrelated settings changes does not re-decode the picked image. */
    private var loadedIconPath: String? = null
    private var loadedIconStamp: Long = 0L
    private var loadedIconBitmap: Bitmap? = null

    /** Light/dark switch is a plain SharedPreferences write, not a Settings change: watch it too. */
    private val themePrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == COLOR_MODE_KEY || key == AMOLED_DARK_KEY) {
                // Callbacks fire on whichever thread wrote the pref; the overlay must repaint on main.
                mainHandler.post { applyAppearance() }
            }
        }

    /** True while the ball is tucked into the edge (dimmed, half off-screen) waiting to be woken. */
    private var hidden = false

    /** True while the half-screen quick-chat panel is up; the ball stays out of its way. */
    private var panelOpen = false
    private var motionAnimator: ValueAnimator? = null

    private val longPressRunnable = Runnable {
        // Only fire if the finger is still down and we have not turned this into a drag.
        if (ballView != null) {
            longPressFired = true
            // Hold-to-talk: the panel records while the finger is down and stops when it lifts.
            _voiceHold.value = true
            openQuickChat(voice = true)
        }
    }

    private val idleRunnable = Runnable {
        if (!panelOpen && ballView != null && !hidden) setHidden(true)
    }

    /** Folds the ball away a beat after the panel starts unfolding (see [BALL_COLLAPSE_DELAY_MS]). */
    private val collapseRunnable = Runnable { collapseBall() }

    override fun onCreate() {
        super.onCreate()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(themePrefsListener)
        // Theme colour / picked icon can change at any time while the ball is up; repaint live.
        // A no-op until the overlay view exists, and showBall() paints once itself.
        scope.launch {
            settingsStore.settingsFlow.collect { applyAppearance() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PANEL_OPEN -> {
                panelOpen = true
                if (ballView == null) {
                    stopSelf()
                } else {
                    // The panel unfolds out of the ball, so hand the ball over a beat later: it
                    // folds away as the panel grows, instead of blinking out first.
                    mainHandler.removeCallbacks(idleRunnable)
                    motionAnimator?.cancel()
                    mainHandler.removeCallbacks(collapseRunnable)
                    mainHandler.postDelayed(collapseRunnable, BALL_COLLAPSE_DELAY_MS)
                    syncWorking()
                }
                return START_NOT_STICKY
            }

            ACTION_PANEL_CLOSE -> {
                // Only a panel that actually came up can hand the ball back: a close with no
                // matching open (the activity was torn down before its first frame) must leave
                // the ball exactly as it is.
                val panelCameUp = panelOpen
                panelOpen = false
                if (ballView == null) {
                    stopSelf()
                } else {
                    mainHandler.removeCallbacks(collapseRunnable)
                    if (panelCameUp) {
                        // The panel folded back into the spot the ball belongs to — pop it out.
                        ballView?.visibility = View.VISIBLE
                        if (hidden) {
                            // A panel is only ever opened from an awake ball, but stay safe: a
                            // tucked ball is woken (that animation owns position and opacity).
                            setHidden(false)
                        } else {
                            spawnBall()
                            scheduleIdle()
                        }
                        syncWorking()
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_WORKING_ON -> {
                if (ballView == null) {
                    stopSelf()
                } else {
                    working = true
                    // The ball is the status light while a turn runs, so it must not tuck itself
                    // away mid-turn. A ball that is already tucked stays tucked (per the agreed
                    // behaviour) — we only stop the countdown from tucking an awake one.
                    mainHandler.removeCallbacks(idleRunnable)
                    syncWorking()
                }
                return START_NOT_STICKY
            }

            ACTION_WORKING_OFF -> {
                if (ballView == null) {
                    stopSelf()
                } else {
                    working = false
                    syncWorking()
                    scheduleIdle()
                }
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
                showBall()
                scheduleIdle()
            }
        }
        // NOT sticky: if the user turned the ball off we must not resurrect it after a kill.
        // App start / boot are what bring it back when the setting is still on.
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = ballView ?: return
        val params = ballParams ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        mainHandler.post {
            // Night mode is part of the configuration, so the themed ball may need a repaint.
            applyAppearance()
            if (hidden) {
                // Recompute the tucked position against the new width/orientation.
                tuckIntoEdge()
            } else {
                snapToEdge(wm, view, params)
                scheduleIdle()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(themePrefsListener)
        }
        hideBall()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    // ---- Overlay view ----

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun showBall() {
        if (ballView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = resources.displayMetrics.density
        val size = (BALL_SIZE_DP * density).toInt()

        val container = HaloBallView(this).apply {
            contentDescription = getString(R.string.quick_chat_title)
            isClickable = true
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val margin = (12 * density).toInt()
        val defaultX = screenWidth - size - margin
        val defaultY = (screenHeight * 0.62f).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = readStringPreference(PREF_X)?.toIntOrNull() ?: defaultX
            y = readStringPreference(PREF_Y)?.toIntOrNull() ?: defaultY
        }

        try {
            wm.addView(container, params)
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed", t)
            return
        }
        ballView = container
        ballParams = params
        isShowing = true
        // Paint colour + icon from the current theme / user picks (never hard-coded).
        applyAppearance()
        attachTouch(container, params, wm)
        // Clamp into range once the view exists — a saved position from another orientation, or
        // a first-run default near the bottom, could otherwise sit partly off-screen.
        mainHandler.post { snapToEdge(wm, container, params) }
        // Born, not blinked into existence: the ball pops out wherever it belongs.
        spawnBall()
    }

    private fun hideBall() {
        val view = ballView ?: return
        ballView = null
        ballParams = null
        isShowing = false
        working = false
        longPressFired = false
        hidden = false
        _voiceHold.value = false
        loadedIconPath = null
        loadedIconStamp = 0L
        loadedIconBitmap = null
        mainHandler.removeCallbacks(longPressRunnable)
        mainHandler.removeCallbacks(idleRunnable)
        mainHandler.removeCallbacks(collapseRunnable)
        motionAnimator?.cancel()
        motionAnimator = null
        view.animate().cancel()
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "removeView failed", it) }
    }

    // ---- Appearance ----

    /**
     * Paints the ball from the current settings: the halo is stroked in the app theme's primary
     * colour, or in the colour the user picked; a user-picked image, when there is one, replaces
     * the halo instead. Cheap and idempotent — safe to call on every settings change.
     */
    private fun applyAppearance() {
        val ball = ballView ?: return
        val settings = settingsStore.settingsFlow.value
        ball.accentColor = settings.floatingBallColor ?: themePrimaryColor()
        applyBacking(ball)
        ball.setIcon(
            settings.floatingBallIconPath
                .takeIf { it.isNotBlank() }
                ?.let { loadIcon(it) }
        )
    }

    /** The app theme's primary colour, honouring dynamic colour / dark mode / custom themes. */
    private fun themePrimaryColor(): Int = runCatching {
        val settings = settingsStore.settingsFlow.value
        val dark = when (colorMode()) {
            ColorMode.SYSTEM ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES

            ColorMode.LIGHT -> false
            ColorMode.DARK -> true
        }
        val scheme = if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
        } else {
            (findThemeById(settings.themeId, settings.customThemes) ?: findPresetTheme(settings.themeId))
                .getColorScheme(dark)
        }
        scheme.primary.toArgb()
    }.getOrDefault(FALLBACK_BALL_COLOR)

    /**
     * Resting backing colours from the app theme: translucent surface disc (overlays cannot blur
     * the apps behind them) and an outlineVariant hairline. Keeps the view's defaults on failure.
     */
    private fun applyBacking(ball: HaloBallView) {
        val scheme = runCatching { backingScheme() }.getOrNull() ?: return
        ball.surfaceColor = HaloBallLook.withAlphaFraction(scheme.surface.toArgb(), HaloBallLook.BACKING_ALPHA)
        ball.outlineColor = scheme.outlineVariant.toArgb()
    }

    /** Same theme resolution as [themePrimaryColor] (dynamic colour / dark mode / custom themes). */
    private fun backingScheme(): ColorScheme {
        val settings = settingsStore.settingsFlow.value
        val dark = when (colorMode()) {
            ColorMode.SYSTEM ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES

            ColorMode.LIGHT -> false
            ColorMode.DARK -> true
        }
        return if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
        } else {
            (findThemeById(settings.themeId, settings.customThemes) ?: findPresetTheme(settings.themeId))
                .getColorScheme(dark)
        }
    }

    private fun colorMode(): ColorMode {
        val raw = readStringPreference(COLOR_MODE_KEY)
        return ColorMode.entries.firstOrNull { it.name == raw } ?: ColorMode.SYSTEM
    }

    /** Decodes the picked image, downsampled to roughly the ball size; cached by path + mtime. */
    private fun loadIcon(path: String): Bitmap? {
        val stamp = File(path).lastModified()
        if (loadedIconPath == path && loadedIconStamp == stamp) return loadedIconBitmap
        val bitmap = decodeIcon(path)
        loadedIconPath = path
        loadedIconStamp = stamp
        loadedIconBitmap = bitmap
        return bitmap
    }

    private fun decodeIcon(path: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val target = (BALL_SIZE_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(
        view: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var startedHidden = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Any interaction pushes the auto-tuck countdown back.
                    mainHandler.removeCallbacks(idleRunnable)
                    // A tucked-away ball is woken by the touch itself, but the touch is no longer
                    // swallowed: it can be dragged out of the edge straight away. It only *opens
                    // the panel* on the next tap, so the "tap once to wake" rule still holds.
                    startedHidden = hidden
                    if (hidden) setHidden(false, instant = true)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressFired = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // Once the hold-to-talk gesture has armed, keep the ball still: the finger is
                    // talking, not dragging.
                    if (longPressFired) return@setOnTouchListener true
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (longPressFired) {
                        // Finger up: hold-to-talk ends and the recorder stops. Only now may the ball
                        // fold away into the panel — it was kept visible so it could keep the touch.
                        _voiceHold.value = false
                        if (panelOpen) mainHandler.post { collapseBall() }
                    }
                    when {
                        dragging -> snapToEdge(wm, view, params)
                        !longPressFired && !startedHidden -> openQuickChat(voice = false)
                    }
                    startedHidden = false
                    dragging = false
                    scheduleIdle()
                    true
                }

                else -> false
            }
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun snapToEdge(wm: WindowManager, view: View, params: WindowManager.LayoutParams) {
        val density = resources.displayMetrics.density
        val margin = (8 * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val size = view.width.takeIf { it > 0 } ?: params.width
        val maxX = (screenWidth - size - margin).coerceAtLeast(margin)
        val maxY = (screenHeight - size - margin).coerceAtLeast(margin)

        params.x = if (params.x + size / 2 < screenWidth / 2) margin else maxX
        params.y = params.y.coerceIn(margin, maxY)
        runCatching { wm.updateViewLayout(view, params) }
        writeStringPreference(PREF_X, params.x.toString())
        writeStringPreference(PREF_Y, params.y.toString())
    }

    private fun openQuickChat(voice: Boolean) {
        try {
            // No stock window animation for the panel: it plays its own unfold out of the ball, and
            // the system's launch transition fighting that is what made the panel stutter — and
            // look half-animated — over the launcher. The intent also carries
            // FLAG_ACTIVITY_NO_ANIMATION, and the activity overrides its own open/close transition
            // to nothing, so opening/closing is entirely ours.
            @Suppress("DEPRECATION")
            val options = ActivityOptions.makeCustomAnimation(this, 0, 0)
            startActivity(
                QuickChatActivity.intent(this, voice, ballSide(), ballCenterY()),
                options.toBundle(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "openQuickChat failed", t)
        }
    }

    /** `"left"`/`"right"` — which edge the ball sits on, so the panel can mirror itself. */
    private fun ballSide(): String {
        val params = ballParams ?: return "right"
        val size = ballView?.width?.takeIf { it > 0 } ?: params.width
        val screenWidth = resources.displayMetrics.widthPixels
        return if (params.x + size / 2 >= screenWidth / 2) "right" else "left"
    }

    /** Vertical centre of the ball, in screen pixels; the panel anchors itself to this. */
    private fun ballCenterY(): Int {
        val params = ballParams ?: return 0
        val size = ballView?.height?.takeIf { it > 0 } ?: params.height
        return params.y + size / 2
    }

    // ---- Auto tuck (idle) ----

    private fun idleSeconds(): Int = settingsStore.settingsFlow.value.floatingBallIdleSeconds

    private fun hiddenAlpha(): Float =
        settingsStore.settingsFlow.value.floatingBallHiddenAlpha.coerceIn(10, 100) / 100f

    /** (Re)starts the countdown that tucks the ball away once the user stops bothering with it. */
    private fun scheduleIdle() {
        mainHandler.removeCallbacks(idleRunnable)
        if (panelOpen || hidden || working || ballView == null) return
        val seconds = idleSeconds()
        if (seconds <= 0) return
        mainHandler.postDelayed(idleRunnable, seconds * 1000L)
    }

    private fun setHidden(hide: Boolean, instant: Boolean = false) {
        if (hidden == hide) return
        hidden = hide
        if (hide) {
            mainHandler.removeCallbacks(idleRunnable)
            tuckIntoEdge()
        } else {
            wakeUp(instant)
            scheduleIdle()
        }
    }

    /**
     * Collapses the ball into a slim handle on the edge it was snapped to: all but a thin strip of
     * the window slides off-screen, the view draws only the handle in that strip, and it dims to
     * the hidden-opacity setting.
     */
    private fun tuckIntoEdge() {
        val view = ballView ?: return
        val params = ballParams ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val size = view.width.takeIf { it > 0 } ?: params.width
        val screenWidth = resources.displayMetrics.widthPixels
        val onRight = params.x + size / 2 >= screenWidth / 2
        val strip = (TUCKED_STRIP_DP * resources.displayMetrics.density).toInt()
        val targetX = HaloBallLook.tuckedX(onRight, screenWidth, size, strip).toFloat()
        view.setHandle(on = true, onRight = onRight)
        animateTo(view, params, wm, targetX, hiddenAlpha())
    }

    /**
     * Brings a tucked-away ball back out to its remembered edge, at full opacity. [instant] skips
     * the slide — used when the user grabs the ball: it must be under the finger at once, ready to
     * be dragged, instead of still sliding in from the edge.
     */
    private fun wakeUp(instant: Boolean = false) {
        val view = ballView ?: return
        val params = ballParams ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = resources.displayMetrics.density
        val size = view.width.takeIf { it > 0 } ?: params.width
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val margin = (8 * density).toInt()
        val maxX = (screenWidth - size - margin).coerceAtLeast(margin)
        val maxY = (screenHeight - size - margin).coerceAtLeast(margin)
        params.y = params.y.coerceIn(margin, maxY)
        val restingX = readStringPreference(PREF_X)?.toIntOrNull()?.coerceIn(margin, maxX)
            ?: if (params.x + size / 2 >= screenWidth / 2) maxX else margin
        // Back to the full ball before it moves, so it slides (or jumps) out whole.
        view.setHandle(on = false)
        if (instant) {
            motionAnimator?.cancel()
            view.animate().cancel()
            params.x = restingX
            view.alpha = 1f
            runCatching { wm.updateViewLayout(view, params) }
        } else {
            animateTo(view, params, wm, restingX.toFloat(), 1f)
        }
        writeStringPreference(PREF_X, restingX.toString())
        writeStringPreference(PREF_Y, params.y.toString())
    }

    private fun animateTo(
        view: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
        targetX: Float,
        targetAlpha: Float,
    ) {
        motionAnimator?.cancel()
        // A running spawn/collapse owns scale and alpha; the tuck/wake animation must take over.
        view.animate().cancel()
        val startX = params.x.toFloat()
        val startAlpha = view.alpha
        motionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = IDLE_ANIM_MS
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                params.x = Math.round(startX + (targetX - startX) * fraction)
                view.alpha = startAlpha + (targetAlpha - startAlpha) * fraction
                runCatching { wm.updateViewLayout(view, params) }
            }
            start()
        }
    }

    // ---- Spawn / collapse ----

    /**
     * Folds the ball away into the quick-chat panel: it shrinks and fades from where it sits, then
     * goes invisible. Only stays hidden if the panel is still up by the time it finishes, so a
     * panel dismissed mid-collapse never leaves the ball stranded.
     */
    private fun collapseBall() {
        val view = ballView ?: return
        if (panelOpen && voiceHoldActive.value) {
            // The finger is still down for hold-to-talk. Hiding the ball now would rip the touch
            // out from under the gesture (INVISIBLE cancels it) and end the recording early — so
            // the ball stays put, under the user's finger, until the hold is released.
            return
        }
        if (panelOpen) {
            // The panel is unfolding out of the ball — the ball becomes it.
            view.animate().cancel()
            view.animate()
                .scaleX(BALL_COLLAPSE_END_SCALE)
                .scaleY(BALL_COLLAPSE_END_SCALE)
                .alpha(0f)
                .setDuration(BALL_COLLAPSE_MS)
                .setInterpolator(AccelerateInterpolator(1.6f))
                .withEndAction {
                    if (panelOpen && ballView === view) {
                        view.visibility = View.INVISIBLE
                        view.scaleX = 1f
                        view.scaleY = 1f
                    }
                }
                .start()
        } else {
            // The panel closed while we were waiting: nothing to fold into, just stay visible.
            view.visibility = View.VISIBLE
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = 1f
        }
    }

    /**
     * Pops the ball back out of where the panel folded into — a springy overshoot, so the ball
     * reads as re-forming rather than simply appearing. Also used the first time the ball is put
     * on screen.
     */
    private fun spawnBall() {
        val view = ballView ?: return
        view.animate().cancel()
        view.scaleX = BALL_SPAWN_START_SCALE
        view.scaleY = BALL_SPAWN_START_SCALE
        view.alpha = 0f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(BALL_SPAWN_MS)
            .setInterpolator(OvershootInterpolator(BALL_SPAWN_OVERSHOOT))
            .start()
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
