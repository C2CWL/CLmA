package com.clma.app.service.island

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.widget.Toast
import com.clma.ai.ui.UIMessagePart
import com.clma.app.AppScope
import com.clma.app.R
import com.clma.app.RouteActivity
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.service.ChatService
import com.clma.app.service.FloatingBallService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Drives the top island overlay ([TopIslandView]) from ChatService state.
 *
 * - Shows the most recently started generating conversation as a capsule (tap to expand).
 * - Hidden while the app is in the foreground showing that very conversation's chat page.
 * - When the turn finishes (generationDoneFlow) it shows "已完成" for 3 s; when it ends without
 *   finishing (stop / error) it shows "已停止" briefly.
 * - Turns that are not chat generations (automation, Telegram) only raise a plain capsule
 *   through [onGenericTurnStarted] / [onGenericTurnEnded] (called by AgentOverlay).
 *
 * Needs the overlay permission and the `enableTopIsland` setting; without either it stays hidden
 * and the ongoing generation notification (with its 停止 action) is the fallback.
 */
object TopIslandController {
    private const val TAG = "TopIsland"

    private val visibleChat = MutableStateFlow<Uuid?>(null)
    private val genericTurns = MutableStateFlow(0)

    internal val visibleChatFlow: StateFlow<Uuid?> = visibleChat.asStateFlow()
    internal val genericTurnsFlow: StateFlow<Int> = genericTurns.asStateFlow()

    @Volatile
    private var started = false

    /** The chat page for [id] is on screen (resumed). */
    fun setVisibleChat(id: Uuid) {
        visibleChat.value = id
    }

    /** The chat page for [id] left the screen. */
    fun clearVisibleChat(id: Uuid) {
        visibleChat.compareAndSet(id, null)
    }

    fun onGenericTurnStarted() {
        genericTurns.update { it + 1 }
    }

    fun onGenericTurnEnded() {
        genericTurns.update { (it - 1).coerceAtLeast(0) }
    }

    /** Idempotent; call once from Application.onCreate on the main thread. */
    fun start(app: Application, chatService: ChatService, settingsStore: SettingsStore, appScope: AppScope) {
        if (started) return
        started = true
        runCatching { TopIslandRuntime(app, chatService, settingsStore, appScope).start() }
            .onFailure { Log.e(TAG, "start failed", it) }
    }
}

private class TopIslandRuntime(
    private val app: Application,
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
) : TopIslandView.Callbacks {

    private data class Snapshot(
        val jobs: Map<Uuid, Job>,
        val enabled: Boolean,
        val visibleChat: Uuid?,
        val foreground: Boolean,
        val genericTurns: Int,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val themedContext = ContextThemeWrapper(app, android.R.style.Theme_DeviceDefault_NoActionBar)
    private var view: TopIslandView? = null

    /** Active generation per conversation: the job instance and when we first saw it. */
    private val startedAt = HashMap<Uuid, Pair<Job, Long>>()

    /** Elapsed time recorded when generationDoneFlow fired for a still-registered job. */
    private val doneElapsed = HashMap<Uuid, Long>()

    private var snapshot = Snapshot(emptyMap(), true, null, false, 0)
    private var target: Uuid? = null
    private var targetTitle: String = ""
    private var targetStatus: TopIslandStatus? = null
    private var bindJob: kotlinx.coroutines.Job? = null

    private var transientMode: TopIslandView.Mode? = null
    private var transientConversation: Uuid? = null
    private var transientUntil = 0L
    private var genericStartedAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            val v = view ?: return
            if (!v.isShowing) return
            v.tick()
            handler.postDelayed(this, TICK_MS)
        }
    }
    private val expiry = Runnable { refresh() }

    fun start() {
        appScope.launch(Dispatchers.Main.immediate) {
            combine(
                chatService.getConversationJobs(),
                settingsStore.settingsFlow.map { it.displaySetting.enableTopIsland }.distinctUntilChanged(),
                TopIslandController.visibleChatFlow,
                chatService.isForeground,
                TopIslandController.genericTurnsFlow,
            ) { jobs, enabled, visible, foreground, generic ->
                Snapshot(
                    jobs = jobs.mapNotNull { (id, job) -> job?.let { id to it } }.toMap(),
                    enabled = enabled,
                    visibleChat = visible,
                    foreground = foreground,
                    genericTurns = generic,
                )
            }.collect { onSnapshot(it) }
        }
        appScope.launch(Dispatchers.Main.immediate) {
            chatService.generationDoneFlow.collect { id ->
                val start = startedAt[id]?.second ?: return@collect
                doneElapsed[id] = now() - start
            }
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun onSnapshot(s: Snapshot) {
        val now = now()
        if (s.genericTurns > 0 && snapshot.genericTurns == 0) genericStartedAt = now
        snapshot = s

        // Generations that ended since the last snapshot.
        val ended = startedAt.keys - s.jobs.keys
        for (id in ended) {
            val start = startedAt.remove(id)?.second ?: now
            val done = doneElapsed.remove(id)
            if (id == target) {
                transientConversation = id
                if (done != null) {
                    transientMode = TopIslandView.Mode.Done(done)
                    transientUntil = now + DONE_VISIBLE_MS
                } else {
                    transientMode = TopIslandView.Mode.Stopped
                    transientUntil = now + STOPPED_VISIBLE_MS
                }
                Log.d(TAG, "turn ended for $id after ${now - start} ms, done=${done != null}")
            }
        }
        // New generations (or a conversation that moved on to its next queued turn).
        for ((id, job) in s.jobs) {
            val previous = startedAt[id]
            if (previous == null || previous.first !== job) {
                startedAt[id] = job to now
                doneElapsed.remove(id)
            }
        }
        refresh()
    }

    private fun refresh() {
        handler.removeCallbacks(expiry)
        val s = snapshot
        if (!s.enabled || !Settings.canDrawOverlays(app)) {
            bind(null)
            hideView()
            return
        }
        val candidate = startedAt.maxByOrNull { it.value.second }?.key
        if (candidate != target) bind(candidate)

        fun onItsChatPage(id: Uuid?) = id != null && s.foreground && s.visibleChat == id

        val now = now()
        val transient = transientMode
        when {
            candidate != null && !onItsChatPage(candidate) -> {
                transientMode = null
                renderRunning()
            }
            candidate != null -> hideView()
            transient != null && now < transientUntil && !onItsChatPage(transientConversation) -> {
                render(transient)
                handler.postDelayed(expiry, transientUntil - now)
            }
            s.genericTurns > 0 && !FloatingBallService.isShowing -> {
                transientMode = null
                render(TopIslandView.Mode.Generic(genericStartedAt))
            }
            else -> {
                transientMode = null
                hideView()
            }
        }
    }

    /** Follows the target conversation's title and messages while it is the island's subject. */
    private fun bind(id: Uuid?) {
        if (id == target && (id == null || bindJob != null)) return
        bindJob?.cancel()
        bindJob = null
        target = id
        targetTitle = ""
        targetStatus = null
        if (id == null) return
        bindJob = appScope.launch(Dispatchers.Main.immediate) {
            chatService.getConversationFlow(id).conflate().collect { conversation ->
                if (target != id) return@collect
                targetTitle = conversation.title
                targetStatus = TopIslandStatusResolver.resolve(conversation.currentMessages)
                val v = view
                if (v != null && v.isShowing && transientMode == null && startedAt.containsKey(id)) {
                    renderRunning()
                }
            }
        }
    }

    private fun renderRunning() {
        val id = target ?: return
        val start = startedAt[id]?.second ?: return
        render(
            TopIslandView.Mode.Running(
                title = targetTitle,
                status = targetStatus ?: TopIslandStatus(TopIslandPhase.Thinking, 1, ""),
                startedAtMs = start,
            )
        )
    }

    private fun render(mode: TopIslandView.Mode) {
        val v = view ?: TopIslandView(themedContext, this).also { view = it }
        val wasShowing = v.isShowing
        v.render(mode)
        if (!wasShowing && v.isShowing) {
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, TICK_MS)
        }
    }

    private fun hideView() {
        handler.removeCallbacks(ticker)
        view?.hide()
    }

    // ---- TopIslandView.Callbacks ----

    override fun onStop() {
        val id = target ?: return
        appScope.launch {
            runCatching { chatService.stopGeneration(id) }
                .onFailure { Log.w(TAG, "stopGeneration failed", it) }
        }
    }

    override fun onOpen() {
        val id = target ?: return
        openConversation(id)
    }

    override fun onSend(text: String) {
        val id = target
        val accepted = id != null && runCatching {
            chatService.interjectMessage(id, listOf(UIMessagePart.Text(text)))
        }.onFailure { Log.w(TAG, "interjectMessage failed", it) }.getOrDefault(false)
        if (accepted) {
            view?.clearInput()
            toast(R.string.top_island_interject_sent)
        } else {
            toast(R.string.top_island_interject_failed)
        }
    }

    override fun onDoneTapped() {
        val id = transientConversation ?: return
        transientMode = null
        hideView()
        openConversation(id)
    }

    private fun openConversation(id: Uuid) {
        // Same target as the generation notifications' content intent (ChatService.getPendingIntent).
        val intent = Intent(app, RouteActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra("conversationId", id.toString())
        }
        // The visible overlay window exempts us from background-activity-start limits.
        runCatching { app.startActivity(intent) }
            .onFailure { Log.w(TAG, "open conversation failed", it) }
    }

    private fun toast(res: Int) {
        Toast.makeText(app, res, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "TopIsland"
        const val TICK_MS = 400L
        const val DONE_VISIBLE_MS = 3_000L
        const val STOPPED_VISIBLE_MS = 1_500L
    }
}
