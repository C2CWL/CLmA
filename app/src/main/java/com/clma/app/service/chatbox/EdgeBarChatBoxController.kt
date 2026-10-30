package com.clma.app.service.chatbox

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.widget.Toast
import com.clma.ai.ui.UIMessagePart
import com.clma.app.R
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.datastore.getAssistantById
import com.clma.app.data.model.Conversation
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.service.ChatService
import com.clma.app.service.taskcard.TaskCardPhase
import com.clma.app.service.taskcard.TaskCardStatus
import com.clma.app.service.taskcard.TaskCardStatusResolver
import com.clma.app.ui.hooks.readStringPreference
import com.clma.app.ui.hooks.writeStringPreference
import com.clma.app.ui.theme.resolveChatTypeface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Drives the edge bar's floating chat box ([EdgeBarChatBoxView]) from ChatService state.
 *
 * Which conversation it shows when opened: the one generating (the most recent if several);
 * else, when the bar is green, the one that just finished; else the conversation the app last
 * had open (the same `lastConversationId` the app restores on launch), or the most recently
 * updated one of the current assistant; else a fresh conversation.
 *
 * While bound it holds a ChatService conversation reference (like the chat page's ViewModel),
 * so the session stays alive and streams live into the box. Everything runs on the main thread
 * ([scope] is the edge bar service's main scope).
 */
internal class EdgeBarChatBoxController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val anchor: () -> EdgeBarChatBoxView.Anchor?,
    private val isDark: () -> Boolean,
    private val openConversationInApp: (Uuid) -> Unit,
) : EdgeBarChatBoxView.Callbacks {

    private val themedContext = ContextThemeWrapper(context, R.style.Theme_EdgeBarChatBox)
    private var view: EdgeBarChatBoxView? = null

    /** Generating conversations: the job and when we first saw it (elapsedRealtime). */
    private val generating = HashMap<Uuid, Pair<Job, Long>>()

    /** How long the replies that ended lately took (until "done" is announced for them). */
    private val recentlyEnded = HashMap<Uuid, Long>()

    /** The conversation whose reply finished last, and how long that reply took. */
    private var lastFinished: Uuid? = null
    private var lastFinishedElapsed = 0L

    private var boundId: Uuid? = null
    private var bindJob: Job? = null
    private var openJob: Job? = null
    private var conversationsJob: Job? = null
    private var conversation: Conversation? = null
    private var conversations = emptyList<EdgeBarChatBoxView.ConversationOption>()

    val isShowing: Boolean get() = view?.isShowing == true

    fun start() {
        scope.launch {
            chatService.getConversationJobs().collect { jobs -> onJobs(jobs) }
        }
        scope.launch {
            chatService.generationDoneFlow.collect { id ->
                // The job may or may not be unregistered yet when "done" is announced.
                val elapsed = generating[id]?.let { SystemClock.elapsedRealtime() - it.second }
                    ?: recentlyEnded[id]
                    ?: return@collect
                lastFinished = id
                lastFinishedElapsed = elapsed
                if (id == boundId) render()
            }
        }
        scope.launch {
            settingsStore.settingsFlow.collect { settings ->
                val typeface = settings.displaySetting.resolveChatTypeface(context)
                view?.setChatTypeface(typeface)
            }
        }
    }

    /** Opens the box; [preferFinished] = the bar was green (show the reply that just finished). */
    fun open(preferFinished: Boolean) {
        openJob?.cancel()
        openJob = scope.launch {
            val id = runCatching { pickConversation(preferFinished) }
                .onFailure { Log.w(TAG, "pick conversation failed", it) }
                .getOrNull() ?: Uuid.random()
            bind(id)
            show()
        }
    }

    fun close() {
        openJob?.cancel()
        view?.dismiss()
        unbind()
    }

    fun onThemeChanged() {
        view?.setDarkMode(isDark())
    }

    fun destroy() {
        close()
    }

    // ---- Conversation choice / binding ----

    private suspend fun pickConversation(preferFinished: Boolean): Uuid {
        newestGenerating()?.let { return it }
        if (preferFinished) lastFinished?.let { return it }
        context.readStringPreference(LAST_CONVERSATION_KEY)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.takeIf { conversationRepo.existsConversationById(it) }
            ?.let { return it }
        val assistantId = settingsStore.settingsFlow.value.assistantId
        return conversationRepo.getRecentConversations(assistantId, limit = 3)
            .maxByOrNull { it.updateAt }?.id
            ?: Uuid.random()
    }

    private fun bind(id: Uuid) {
        if (boundId == id && bindJob != null) return
        unbind()
        boundId = id
        conversation = null
        chatService.addConversationReference(id)
        // Load it from the database (or set up a new one) unless it is generating right now,
        // in which case its live session already holds everything.
        if (!isGeneratingNow(id)) {
            scope.launch {
                runCatching { chatService.initializeConversation(id) }
                    .onFailure { Log.w(TAG, "initializeConversation failed", it) }
            }
        }
        bindJob = scope.launch {
            chatService.getConversationFlow(id).collect { c ->
                if (boundId != id) return@collect
                conversation = c
                render()
            }
        }
    }

    private fun unbind() {
        bindJob?.cancel()
        bindJob = null
        boundId?.let { chatService.removeConversationReference(it) }
        boundId = null
        conversation = null
    }

    private fun onJobs(jobs: Map<Uuid, Job?>) {
        val now = SystemClock.elapsedRealtime()
        val active = jobs.mapNotNull { (id, job) -> job?.let { id to it } }.toMap()
        for ((id, entry) in generating) {
            if (id !in active) recentlyEnded[id] = now - entry.second
        }
        if (recentlyEnded.size > 16) recentlyEnded.clear()
        generating.keys.retainAll(active.keys)
        for ((id, job) in active) {
            val previous = generating[id]
            if (previous == null || previous.first !== job) generating[id] = job to now
        }
        val isGenerating = boundId?.let { isGeneratingNow(it) } ?: false
        conversations = conversations.map {
            it.copy(working = isGeneratingNow(Uuid.parse(it.id)))
        }
        render()
    }

    private fun newestGenerating(): Uuid? = generating.maxByOrNull { it.value.second }?.key

    /**
     * True when [id] is generating. [generating] only tracks jobs this window watched start, so a
     * reply already in flight when the window opened looks idle here — and a send would start a
     * second message instead of interjecting. The service's session flag is what the main chat UI
     * uses, so consult it as well and keep both surfaces agreeing.
     */
    private fun isGeneratingNow(id: Uuid): Boolean =
        chatService.isGenerating(id) || generating.containsKey(id)

    // ---- Rendering ----

    private fun show() {
        val a = anchor()
        if (conversationsJob == null) observeConversations()
        if (a == null) {
            unbind()
            return
        }
        val typeface = settingsStore.settingsFlow.value.displaySetting.resolveChatTypeface(context)
        val v = view ?: EdgeBarChatBoxView(themedContext, this).also { view = it }
        v.show(currentState(), a, isDark(), typeface)
        if (!v.isShowing) unbind()
    }

    private fun observeConversations() {
        conversationsJob = scope.launch {
            val assistantId = settingsStore.settingsFlow.value.assistantId
            conversationRepo.getConversationsOfAssistant(assistantId).collect { recent ->
                conversations = recent.take(30).map(::conversationOption)
                if (isShowing) render()
            }
        }
    }

    private fun conversationOption(c: Conversation): EdgeBarChatBoxView.ConversationOption {
        val settings = settingsStore.settingsFlow.value
        return EdgeBarChatBoxView.ConversationOption(
            id = c.id.toString(),
            title = c.title.ifBlank { context.getString(R.string.chat_box_untitled) },
            assistantName = settings.getAssistantById(c.assistantId)?.name
                ?: context.getString(R.string.chat_box_assistant_default),
            working = isGeneratingNow(c.id),
            selected = c.id == boundId,
        )
    }

    private fun render() {
        val v = view ?: return
        if (!v.isShowing) return
        v.update(currentState())
    }

    private fun currentState(): EdgeBarChatBoxView.State {
        val id = boundId
        val c = conversation
        val messages = c?.currentMessages.orEmpty()
        val start = id?.let { generating[it]?.second }
        val mode = when {
            start != null -> EdgeBarChatBoxView.Mode.Working(
                status = if (messages.isEmpty()) {
                    TaskCardStatus(TaskCardPhase.Thinking, 1, "")
                } else {
                    TaskCardStatusResolver.resolve(messages)
                },
                startedAtMs = start,
            )
            id != null && id == lastFinished -> EdgeBarChatBoxView.Mode.Done(lastFinishedElapsed)
            else -> EdgeBarChatBoxView.Mode.Idle(
                assistantName = assistantName(c),
                updatedAtMs = c?.updateAt?.toEpochMilli() ?: 0L,
            )
        }
        return EdgeBarChatBoxView.State(
            title = c?.title.orEmpty(),
            mode = mode,
            items = ChatBoxItems.build(messages),
            conversations = conversations.map { it.copy(selected = it.id == id?.toString()) },
        )
    }

    private fun assistantName(c: Conversation?): String {
        val settings = settingsStore.settingsFlow.value
        val name = c?.let { settings.getAssistantById(it.assistantId)?.name }.orEmpty()
        return name.ifBlank { context.getString(R.string.chat_box_assistant_default) }
    }

    // ---- EdgeBarChatBoxView.Callbacks ----

    override fun onSelectConversation() {
        if (conversationsJob != null) return
        observeConversations()
    }

    override fun onConversationSelected(id: String) {
        val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return
        if (uuid != boundId) bind(uuid)
        render()
    }

    override fun onNewConversation() {
        bind(Uuid.random())
        render()
    }

    override fun onOpen() {
        val id = boundId ?: return
        close()
        openConversationInApp(id)
    }

    override fun onStop() {
        val id = boundId ?: return
        scope.launch {
            runCatching { chatService.stopGeneration(id) }
                .onFailure { Log.w(TAG, "stopGeneration failed", it) }
        }
    }

    override fun onToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String,
        scope: ChatService.ApprovalScope,
        toolName: String,
    ) {
        val id = boundId ?: return
        chatService.handleToolApproval(
            conversationId = id,
            toolCallId = toolCallId,
            approved = approved,
            reason = reason,
            scope = scope,
            toolName = toolName,
        )
    }

    override fun onToolAnswer(toolCallId: String, answer: String) {
        val id = boundId ?: return
        chatService.handleToolApproval(
            conversationId = id,
            toolCallId = toolCallId,
            approved = true,
            answer = answer,
        )
    }

    override fun onSend(text: String) {
        val id = boundId ?: return
        val parts = listOf(UIMessagePart.Text(text))
        if (isGeneratingNow(id)) {
            val accepted = runCatching { chatService.interjectMessage(id, parts) }
                .onFailure { Log.w(TAG, "interjectMessage failed", it) }
                .getOrDefault(false)
            if (accepted) {
                view?.clearInput()
            } else {
                // Keep the text in the field so it can be sent again.
                toast(R.string.chat_box_interject_failed)
            }
            return
        }
        runCatching { chatService.sendMessage(id, parts) }
            .onSuccess {
                view?.clearInput()
                if (lastFinished == id) lastFinished = null
                // The app reopens this conversation next time, like after sending in the app.
                context.writeStringPreference(LAST_CONVERSATION_KEY, id.toString())
            }
            .onFailure {
                Log.w(TAG, "sendMessage failed", it)
                toast(R.string.chat_box_send_failed)
            }
    }

    override fun onDismissed() {
        unbind()
    }

    private fun toast(res: Int) {
        Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "EdgeBarChatBox"

        /** Same key ChatVM writes (ui/pages/chat/ChatVM.kt). */
        const val LAST_CONVERSATION_KEY = "lastConversationId"
    }
}
