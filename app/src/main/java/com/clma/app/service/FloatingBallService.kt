package com.clma.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ContextThemeWrapper
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.clma.app.FLOATING_BALL_NOTIFICATION_CHANNEL_ID
import com.clma.app.R
import com.clma.app.RouteActivity
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.service.EdgeBarLook.Edge
import com.clma.app.service.taskcard.EdgeBarTaskCardView
import com.clma.app.service.taskcard.TaskCardPhase
import com.clma.app.service.taskcard.TaskCardStatus
import com.clma.app.service.taskcard.TaskCardStatusResolver
import com.clma.app.ui.hooks.COLOR_MODE_KEY
import com.clma.app.ui.hooks.readStringPreference
import com.clma.app.ui.hooks.writeStringPreference
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.abs
import kotlin.uuid.Uuid

private const val TAG = "FloatingBallService"

/**
 * The always-available edge bar (it replaced the floating ball; the class keeps its old name so
 * the manifest entry, the settings key and every caller stay unchanged).
 *
 * A slim pill ([EdgeBarView]) in a small TYPE_APPLICATION_OVERLAY window, flush with the left or
 * right screen edge, or lying horizontally just under the status bar, that survives across apps:
 *  - tap                 → bring the main app to the front (an existing task is resumed, not
 *                          duplicated; see [openMainApp]); while a chat reply is generating it
 *                          opens the task card instead ([EdgeBarTaskCardView]: progress, a
 *                          "补充一句" input, 停止 / 打开), unless that is switched off in settings;
 *  - long press, drag    → move the bar freely; on release it snaps to the left, right or top
 *                          edge ([EdgeBarLook.edgeAfterDrag]); edge and position are saved;
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

        /** [EdgeBarLook.SAVED_EDGE_TOP] for the top edge; missing / anything else = side from [PREF_X]. */
        private const val PREF_EDGE = "floating_ball_edge"
        private const val PREF_EDGE_SIDE = "side"

        /** Centre x of the bar on the top edge. */
        private const val PREF_TOP_X = "floating_ball_top_x"

        /** A tap on the bar that arrives this soon after the card closed (outside tap) only closed it. */
        private const val CARD_TAP_GRACE_MS = 250L

        /** The app's SharedPreferences file (see ui/hooks/SharedPreferences.kt). */
        private const val PREFS_NAME = "rikkahub.preferences"

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
    private val chatService: ChatService by inject()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var barView: EdgeBarView? = null
    private var barParams: WindowManager.LayoutParams? = null

    /** True between ACTION_WORKING_ON and ACTION_WORKING_OFF. */
    private var working = false

    /** A turn finished and the user has not tapped the bar (opened the app) since. */
    private var unseenDone = false

    /** The edge the bar is snapped to. */
    private var edge = Edge.RIGHT

    // ---- Task card (tap the bar while a reply is generating) ----

    private val themedContext by lazy {
        ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_NoActionBar)
    }
    private var taskCard: EdgeBarTaskCardView? = null

    /** Generating conversations: the job and when we first saw it (elapsedRealtime). */
    private val generating = HashMap<Uuid, Pair<Job, Long>>()
    private var cardTarget: Uuid? = null
    private var cardBindJob: Job? = null
    private var cardTitle = ""
    private var cardStatus: TaskCardStatus? = null

    /** uptimeMillis when the card last closed itself (tap outside, arrow, back). */
    private var cardDismissedAt = Long.MIN_VALUE / 2

    private val cardCallbacks = object : EdgeBarTaskCardView.Callbacks {
        override fun onStop() {
            val id = cardTarget ?: return
            scope.launch {
                runCatching { chatService.stopGeneration(id) }
                    .onFailure { Log.w(TAG, "stopGeneration failed", it) }
            }
        }

        override fun onOpen() {
            val id = cardTarget ?: return
            closeCard()
            openConversation(id)
        }

        override fun onSend(text: String) {
            val id = cardTarget
            val accepted = id != null && runCatching {
                chatService.interjectMessage(id, listOf(UIMessagePart.Text(text)))
            }.onFailure { Log.w(TAG, "interjectMessage failed", it) }.getOrDefault(false)
            if (accepted) {
                taskCard?.clearInput()
                toast(R.string.task_card_interject_sent)
            } else {
                // Keep the text in the field so it can be sent again.
                toast(R.string.task_card_interject_failed)
            }
        }

        override fun onDismissed() {
            cardDismissedAt = SystemClock.uptimeMillis()
            unbindCard()
        }
    }

    /**
     * The app's colour-mode setting changed (Settings → theme): the "follow theme" swatch repaints.
     * Held in a field because SharedPreferences only keeps a weak reference to its listeners.
     */
    private val colorModeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == COLOR_MODE_KEY) mainHandler.post { applyDarkMode(resources.configuration) }
    }

    /** Set by the long-press timer; from then on the gesture moves the bar instead of tapping. */
    private var dragArmed = false

    private val longPressRunnable = Runnable {
        val view = barView ?: return@Runnable
        val params = barParams ?: return@Runnable
        dragArmed = true
        closeCard()
        // From here the window moves freely: place it by its absolute top-left corner.
        params.x = barLeft(params)
        params.gravity = Gravity.TOP or Gravity.LEFT
        updateLayout(view, params)
        clearGestureExclusion(view)
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
        // Which conversations are generating: the task card shows the most recent one.
        scope.launch {
            chatService.getConversationJobs().collect { jobs -> onJobs(jobs) }
        }
        // Same preferences file the app's theme reads its colour mode from (see ui/hooks).
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(colorModeListener)
    }

    /** Light / dark look of the bar, decided like the app's own theme; see [EdgeBarLook.isDarkMode]. */
    private fun isDarkMode(config: Configuration): Boolean = EdgeBarLook.isDarkMode(
        colorMode = readStringPreference(COLOR_MODE_KEY),
        systemNight = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
    )

    private fun applyDarkMode(config: Configuration) {
        barView?.darkMode = isDarkMode(config)
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
                closeCard()
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
        // System light / dark switch: the "follow theme" swatch repaints.
        applyDarkMode(newConfig)
        // Rotation / resize: keep the edge, re-clamp the position against the new screen.
        mainHandler.post {
            closeCard()
            val view = barView ?: return@post
            val params = barParams ?: return@post
            if (dragArmed) return@post
            applyEdge(view, params, edge, centerX = params.x + params.width / 2, sideTop = params.y)
            updateLayout(view, params)
            updateGestureExclusion(view)
        }
    }

    override fun onDestroy() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(colorModeListener)
        scope.cancel()
        closeCard()
        hideBar()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    // ---- Overlay view ----

    @SuppressLint("RtlHardcoded")
    private fun showBar() {
        if (barView != null) return
        val wm = windowManager() ?: return
        val height = dpPx(EdgeBarLook.TOUCH_HEIGHT_DP)
        val screen = screenSize()
        // Existing users keep their side and height; the top edge is a new saved value.
        val savedEdge = EdgeBarLook.edgeFromSaved(
            readStringPreference(PREF_EDGE),
            readStringPreference(PREF_X)?.toIntOrNull(),
            screen.width,
        )
        val savedTop = readStringPreference(PREF_Y)?.toIntOrNull()
            ?: EdgeBarLook.defaultTop(height, screen.height)
        val savedCenterX = readStringPreference(PREF_TOP_X)?.toIntOrNull() ?: (screen.width / 2)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            dpPx(EdgeBarLook.TOUCH_WIDTH_DP),
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // x / y are measured from the screen's top-left corner (the insets are handled
                // by clampTop / the top edge's y), the same frame as the task card's window.
                setFitInsetsTypes(0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val view = EdgeBarView(this).apply {
            barColor = settingsStore.settingsFlow.value.floatingBallBarColor
            darkMode = isDarkMode(resources.configuration)
            contentDescription = getString(R.string.setting_floating_ball_bar_a11y)
            isClickable = true
            isHapticFeedbackEnabled = true
        }
        // Side bars are placed by gravity, so they stay flush with their edge across rotations.
        edge = savedEdge
        applyEdge(view, params, savedEdge, centerX = savedCenterX, sideTop = savedTop)
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
        view.setOnClickListener { onBarTap(SystemClock.uptimeMillis()) }
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
        var downAt = 0L
        /** The finger moved before the long press armed: a swipe, which does nothing. */
        var swiped = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downAt = event.downTime
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
                        // Free 2-D drag (centred on the finger); the edge is decided on release.
                        val screen = screenSize()
                        val x = (event.rawX - params.width / 2f).toInt()
                            .coerceIn(0, (screen.width - params.width).coerceAtLeast(0))
                        val y = (event.rawY - params.height / 2f).toInt()
                            .coerceIn(0, (screen.height - params.height).coerceAtLeast(0))
                        if (x != params.x || y != params.y) {
                            params.x = x
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
                            val newEdge = if (cancelled) {
                                edge
                            } else {
                                EdgeBarLook.edgeAfterDrag(event.rawX, event.rawY, screen.width, screen.height, edge)
                            }
                            edge = newEdge
                            val centerX = params.x + params.width / 2
                            val sideTop = params.y + params.height / 2 - dpPx(EdgeBarLook.TOUCH_HEIGHT_DP) / 2
                            applyEdge(view, params, newEdge, centerX = centerX, sideTop = sideTop)
                            updateLayout(view, params)
                            updateGestureExclusion(view)
                            savePosition(newEdge, params)
                        }
                        // A cancelled gesture (e.g. taken by the system) is never a tap.
                        !cancelled && !swiped -> onBarTap(downAt)
                    }
                    dragArmed = false
                    swiped = false
                    true
                }

                else -> false
            }
        }
    }

    private fun savePosition(edge: Edge, params: WindowManager.LayoutParams) {
        if (edge == Edge.TOP) {
            writeStringPreference(PREF_EDGE, EdgeBarLook.SAVED_EDGE_TOP)
            writeStringPreference(PREF_TOP_X, (params.x + params.width / 2).toString())
            return
        }
        val screen = screenSize()
        writeStringPreference(PREF_EDGE, PREF_EDGE_SIDE)
        writeStringPreference(
            PREF_X,
            EdgeBarLook.savedXFor(edge == Edge.RIGHT, screen.width, params.width).toString(),
        )
        writeStringPreference(PREF_Y, params.y.toString())
    }

    /**
     * Sizes and places the window for [edge]: a vertical touch area flush with the left / right
     * edge (placed by gravity, height [sideTop] clamped), or a horizontal one just under the
     * status bar centred on [centerX] (kept on screen).
     */
    @SuppressLint("RtlHardcoded")
    private fun applyEdge(
        view: EdgeBarView,
        params: WindowManager.LayoutParams,
        edge: Edge,
        centerX: Int,
        sideTop: Int,
    ) {
        val thin = dpPx(EdgeBarLook.TOUCH_WIDTH_DP)
        val long = dpPx(EdgeBarLook.TOUCH_HEIGHT_DP)
        val screen = screenSize()
        view.horizontal = edge == Edge.TOP
        if (edge == Edge.TOP) {
            params.width = long
            params.height = thin
            params.gravity = Gravity.TOP or Gravity.LEFT
            params.x = EdgeBarLook.topBarLeft(centerX, long, screen.width)
            params.y = screen.topInset
        } else {
            view.onRight = edge == Edge.RIGHT
            params.width = thin
            params.height = long
            params.gravity = Gravity.TOP or (if (edge == Edge.RIGHT) Gravity.RIGHT else Gravity.LEFT)
            params.x = 0
            params.y = clampTop(sideTop)
        }
    }

    /** Absolute left of the bar's window (side bars are placed by gravity with x = 0). */
    private fun barLeft(params: WindowManager.LayoutParams): Int = when (edge) {
        Edge.TOP -> params.x
        Edge.RIGHT -> screenSize().width - params.width
        Edge.LEFT -> 0
    }

    // ---- Tap / task card ----

    /**
     * A tap on the bar (gesture that went down at [downAt], uptimeMillis). While a chat reply is
     * generating (and the setting is on) it toggles the task card; otherwise it opens the app.
     */
    private fun onBarTap(downAt: Long) {
        // The touch that went down on the bar already closed the open card (as a tap outside it).
        if (cardDismissedAt >= downAt - CARD_TAP_GRACE_MS) return
        val card = taskCard
        if (card != null && card.isShowing) {
            closeCard()
            return
        }
        val target = if (working && settingsStore.settingsFlow.value.displaySetting.edgeBarTaskCard) {
            newestGenerating()
        } else {
            null
        }
        if (target != null && openCard(target)) return
        openMainApp()
    }

    private fun newestGenerating(): Uuid? = generating.maxByOrNull { it.value.second }?.key

    private fun onJobs(jobs: Map<Uuid, Job?>) {
        val now = SystemClock.elapsedRealtime()
        val active = jobs.mapNotNull { (id, job) -> job?.let { id to it } }.toMap()
        generating.keys.retainAll(active.keys)
        for ((id, job) in active) {
            val previous = generating[id]
            if (previous == null || previous.first !== job) generating[id] = job to now
        }
        val card = taskCard ?: return
        if (!card.isShowing) return
        // Finished while open: close (the bar turns green as usual); a newer reply takes over.
        val newest = newestGenerating()
        when {
            newest == null -> closeCard()
            newest != cardTarget -> {
                bindCard(newest)
                card.update(cardContent(newest))
            }
        }
    }

    private fun openCard(id: Uuid): Boolean {
        val view = barView ?: return false
        val params = barParams ?: return false
        val screen = screenSize()
        val anchor = EdgeBarTaskCardView.Anchor(
            edge = edge,
            barLeft = barLeft(params),
            barTop = params.y,
            barWidth = params.width,
            barHeight = params.height,
            screenWidth = screen.width,
            screenHeight = screen.height,
            topInset = screen.topInset,
            bottomInset = screen.bottomInset,
        )
        bindCard(id)
        val card = taskCard ?: EdgeBarTaskCardView(themedContext, cardCallbacks).also { taskCard = it }
        card.show(cardContent(id), anchor)
        if (!card.isShowing) {
            unbindCard()
            return false
        }
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        return true
    }

    private fun closeCard() {
        taskCard?.hide()
        unbindCard()
    }

    /** Follows [id]'s title and messages while the card shows it. */
    private fun bindCard(id: Uuid) {
        if (cardTarget == id && cardBindJob != null) return
        cardBindJob?.cancel()
        cardTarget = id
        cardTitle = ""
        cardStatus = null
        cardBindJob = scope.launch {
            chatService.getConversationFlow(id).collect { conversation ->
                if (cardTarget != id) return@collect
                cardTitle = conversation.title
                cardStatus = TaskCardStatusResolver.resolve(conversation.currentMessages)
                taskCard?.update(cardContent(id))
            }
        }
    }

    private fun unbindCard() {
        cardBindJob?.cancel()
        cardBindJob = null
        cardTarget = null
    }

    private fun cardContent(id: Uuid) = EdgeBarTaskCardView.Content(
        title = cardTitle,
        status = cardStatus ?: TaskCardStatus(TaskCardPhase.Thinking, 1, ""),
        startedAtMs = generating[id]?.second ?: SystemClock.elapsedRealtime(),
    )

    /** Opens the app on [id], like the generation notification's tap (ChatService.getPendingIntent). */
    private fun openConversation(id: Uuid) {
        val intent = Intent(this, RouteActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra("conversationId", id.toString())
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "open conversation failed", it) }
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
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
        // The back gesture only lives on the side edges.
        view.systemGestureExclusionRects = if (edge == Edge.TOP) {
            emptyList()
        } else {
            listOf(Rect(0, 0, params.width, params.height))
        }
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
