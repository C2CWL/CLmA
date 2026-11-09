package com.clma.app.ui.pages.bot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clma.app.data.botmode.BotHomeRow
import com.clma.app.data.botmode.botPreviewLine
import com.clma.app.data.botmode.botUnreadCount
import com.clma.app.data.botmode.botsForContactList
import com.clma.app.data.botmode.hasPendingApproval
import com.clma.app.data.botmode.isBotPinned
import com.clma.app.data.botmode.latestTopLevelConversation
import com.clma.app.data.botmode.sortBotHomeRows
import com.clma.app.data.botmode.withBotHidden
import com.clma.app.data.botmode.withBotPinned
import com.clma.app.data.botmode.withBotUnreadClearedForAssistant
import com.clma.app.data.botmode.withBotUnreadMarked
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.Conversation
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.service.ChatService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** A bot list row plus the assistant it belongs to, ready for the Bot home list. */
data class BotHomeItem(val bot: Assistant, val row: BotHomeRow)

@OptIn(ExperimentalCoroutinesApi::class)
class BotHomeVM(
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    chatService: ChatService,
) : ViewModel() {

    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    /** Latest top-level conversation of each visible bot, with its messages loaded for the preview. */
    private val latestByBot: Flow<Map<Uuid, Conversation?>> = settingsStore.settingsFlow
        .map { s -> s.botsForContactList().map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(ids.map { id -> latestConversationOf(id).map { id to it } }) { it.toMap() }
            }
        }

    private fun latestConversationOf(assistantId: Uuid): Flow<Conversation?> =
        conversationRepo.getConversationsOfAssistant(assistantId)
            .map { latestTopLevelConversation(it) }
            .distinctUntilChanged { a, b -> a?.id == b?.id && a?.updateAt == b?.updateAt }
            .mapLatest { summary ->
                // The list flow carries no messages; load them once per change for the preview.
                summary?.let { runCatching { conversationRepo.getConversationById(it.id) }.getOrNull() ?: it }
            }

    private val generatingIds: Flow<Set<Uuid>> = chatService.getConversationJobs()
        .map { jobs -> jobs.filterValues { it?.isActive == true }.keys }
        .distinctUntilChanged()

    val items: StateFlow<List<BotHomeItem>> = combine(
        settingsStore.settingsFlow,
        latestByBot,
        generatingIds,
    ) { s, latest, generating ->
        val ds = s.displaySetting
        val bots = s.botsForContactList()
        val rows = bots.map { bot ->
            val conv = latest[bot.id]
            BotHomeRow(
                assistantId = bot.id,
                latestConversationId = conv?.id,
                preview = botPreviewLine(conv),
                updatedAt = conv?.updateAt,
                unread = ds.botUnreadCount(bot.id),
                pinned = ds.isBotPinned(bot.id),
                pendingApproval = hasPendingApproval(conv),
                generating = conv?.id != null && conv.id in generating,
            )
        }
        val byId = bots.associateBy { it.id }
        sortBotHomeRows(rows, ds.botPinnedAssistantIds).mapNotNull { row ->
            byId[row.assistantId]?.let { BotHomeItem(it, row) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateDisplaySetting(next: com.clma.app.data.datastore.DisplaySetting) = viewModelScope.launch {
        settingsStore.update { s -> s.copy(displaySetting = next) }
    }

    fun togglePin(bot: Assistant) = viewModelScope.launch {
        settingsStore.update { s ->
            val pinned = !s.displaySetting.isBotPinned(bot.id)
            s.copy(displaySetting = s.displaySetting.withBotPinned(bot.id, pinned))
        }
    }

    fun hide(bot: Assistant) = viewModelScope.launch {
        settingsStore.update { s -> s.copy(displaySetting = s.displaySetting.withBotHidden(bot.id, true)) }
    }

    /** Marks the bot unread (its latest conversation); clears it when it already is. */
    fun toggleUnread(row: BotHomeRow) = viewModelScope.launch {
        settingsStore.update { s ->
            val ds = s.displaySetting
            val next = if (row.unread > 0) {
                ds.withBotUnreadClearedForAssistant(row.assistantId)
            } else {
                val convId = row.latestConversationId ?: return@update s
                ds.withBotUnreadMarked(convId, row.assistantId)
            }
            s.copy(displaySetting = next)
        }
    }

    /**
     * Opening a bot: make it the current assistant and clear its unread, then hand back the
     * conversation to show (its latest, or a fresh one when it has none).
     */
    fun open(row: BotHomeRow, onReady: (conversationId: Uuid) -> Unit) = viewModelScope.launch {
        settingsStore.update { s ->
            s.copy(
                assistantId = row.assistantId,
                displaySetting = s.displaySetting.withBotUnreadClearedForAssistant(row.assistantId),
            )
        }
        onReady(row.latestConversationId ?: Uuid.random())
    }
}
