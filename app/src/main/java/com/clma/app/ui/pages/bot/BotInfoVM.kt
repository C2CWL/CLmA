package com.clma.app.ui.pages.bot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clma.app.data.botmode.isBotHidden
import com.clma.app.data.botmode.isBotPinned
import com.clma.app.data.botmode.isBotVisibleTopic
import com.clma.app.data.botmode.isMainBot
import com.clma.app.data.botmode.toTeamPackMarkdown
import com.clma.app.data.botmode.withBotHidden
import com.clma.app.data.botmode.withBotPinned
import com.clma.app.data.botmode.withMainBot
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.Conversation
import com.clma.app.data.model.isBot
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.data.repository.MemoryRepository
import com.clma.app.data.repository.ScheduledJobRepository
import com.clma.app.data.repository.ScheduledJobRunRepository
import com.clma.app.data.botmode.BotRoutineApprovals
import com.clma.app.data.botmode.BotRoutineForm
import com.clma.app.data.botmode.ParkedRoutineRun
import com.clma.app.data.botmode.routinesOfBot
import com.clma.app.data.botmode.toJob
import com.clma.app.data.db.entity.ScheduledJobEntity
import com.clma.app.service.CronJobScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** State and actions of one bot's info page. Every action only touches Bot-library bots. */
class BotInfoVM(
    botId: String,
    private val settingsStore: SettingsStore,
    conversationRepo: ConversationRepository,
    memoryRepository: MemoryRepository,
    private val scheduledJobRepository: ScheduledJobRepository,
    private val scheduledJobRunRepository: ScheduledJobRunRepository,
    private val cronJobScheduler: CronJobScheduler,
    private val appContext: android.content.Context,
) : ViewModel() {
    val id: Uuid = Uuid.parse(botId)

    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    /**
     * This bot's visible topics, newest first: top-level chats plus `[Peer]` threads.
     * Generic sub-agent runs stay filtered out via [isBotVisibleTopic].
     */
    val conversations: StateFlow<List<Conversation>> = conversationRepo.getConversationsOfAssistant(id)
        .map { list -> list.filter(::isBotVisibleTopic).sortedByDescending { it.updateAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memoryCount: StateFlow<Int> = memoryRepository.getMemoriesOfAssistantFlow(id.toString())
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private fun updateBot(transform: (Assistant) -> Assistant) = viewModelScope.launch {
        settingsStore.update { s ->
            s.copy(assistants = s.assistants.map { if (it.id == id && it.isBot) transform(it) else it })
        }
    }

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) updateBot { it.copy(name = trimmed) }
    }

    fun setAvatar(avatar: com.clma.app.data.model.Avatar) = updateBot { it.copy(avatar = avatar) }

    fun setModel(modelId: Uuid) = updateBot { it.copy(chatModelId = modelId) }

    /** null = the Bot default (on); only an explicit "off" is stored (same as the detail page). */
    fun setCostSaving(enabled: Boolean) = updateBot { it.copy(botCostSaving = if (enabled) null else false) }

    fun setMemoryEnabled(enabled: Boolean) = updateBot { it.copy(enableMemory = enabled) }

    fun toggleMcpServer(serverId: Uuid) = updateBot { bot ->
        bot.copy(mcpServers = if (serverId in bot.mcpServers) bot.mcpServers - serverId else bot.mcpServers + serverId)
    }

    fun setPinned(pinned: Boolean) = viewModelScope.launch {
        settingsStore.update { s -> s.copy(displaySetting = s.displaySetting.withBotPinned(id, pinned)) }
    }

    /** The main bot cannot be hidden. */
    fun setHidden(hidden: Boolean) = viewModelScope.launch {
        settingsStore.update { s ->
            if (hidden && s.isMainBot(id)) s else s.copy(displaySetting = s.displaySetting.withBotHidden(id, hidden))
        }
    }

    fun setMain() = viewModelScope.launch { settingsStore.update { s -> s.withMainBot(id) } }

    fun isPinned(s: Settings) = s.displaySetting.isBotPinned(id)
    fun isHidden(s: Settings) = s.displaySetting.isBotHidden(id)

    /** Opening one of the bot's conversations: make the bot current first (a new chat binds to it). */
    fun openConversation(conversationId: Uuid, onReady: (Uuid) -> Unit) = viewModelScope.launch {
        settingsStore.update { s ->
            if (s.assistants.none { it.id == id && it.isBot }) s else s.copy(assistantId = id)
        }
        onReady(conversationId)
    }

    // ------------------------------------------------------------ routines (自动任务)

    /** This bot's scheduled jobs: the existing scheduled_jobs rows whose owner is this bot. */
    val routines: StateFlow<List<ScheduledJobEntity>> = scheduledJobRepository.observeAll()
        .map { routinesOfBot(it, id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Runs of this bot parked on an approval card. */
    val parkedRuns: StateFlow<List<ParkedRoutineRun>> = BotRoutineApprovals.parked
        .map { list -> list.filter { it.assistantId == id.toString() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private suspend fun ownJob(jobId: String): ScheduledJobEntity? =
        scheduledJobRepository.getById(jobId)?.takeIf { it.assistantId == id.toString() }

    /** Create ([existingId] null) or edit a routine, then (re)arm it with the existing scheduler. */
    fun saveRoutine(form: BotRoutineForm, existingId: String?) = viewModelScope.launch(Dispatchers.IO) {
        val existing = existingId?.let { ownJob(it) }
        if (existingId != null && existing == null) return@launch
        val job = form.toJob(assistantId = id, existing = existing, nowMs = System.currentTimeMillis())
        scheduledJobRepository.upsert(job)
        if (job.enabled) cronJobScheduler.schedule(job) else cronJobScheduler.cancel(job.id)
    }

    fun setRoutineEnabled(jobId: String, enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val job = ownJob(jobId) ?: return@launch
        val updated = job.copy(enabled = enabled)
        scheduledJobRepository.update(updated)
        if (enabled) cronJobScheduler.schedule(updated) else cronJobScheduler.cancel(jobId)
    }

    /** Fire now (same path as trigger_job_now); false when the routine is paused or gone. */
    fun runRoutineNow(jobId: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val fired = withContext(Dispatchers.IO) {
            val job = ownJob(jobId)
            if (job == null || !job.enabled) false else { cronJobScheduler.triggerNow(jobId); true }
        }
        onResult(fired)
    }

    fun deleteRoutine(jobId: String) = viewModelScope.launch(Dispatchers.IO) {
        if (ownJob(jobId) == null) return@launch
        cronJobScheduler.cancel(jobId)
        scheduledJobRunRepository.deleteAllForJob(jobId)
        scheduledJobRepository.deleteById(jobId)
        // Runs of this routine still waiting for approval: close their cards and cancel them
        // now (the worker sees the routine is gone), keeping their topics.
        BotRoutineApprovals.ofJob(jobId).forEach {
            com.clma.app.service.BotRoutineRuns.settleNow(appContext, it.conversationId)
        }
    }

    /** Team-pack Markdown with just this bot, readable by the team-pack import. */
    fun teamPackMarkdown(bot: Assistant): String = toTeamPackMarkdown(bot.name, listOf(bot))
}
