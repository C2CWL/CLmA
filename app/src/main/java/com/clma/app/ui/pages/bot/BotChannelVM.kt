package com.clma.app.ui.pages.bot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clma.app.data.botmode.botMembers
import com.clma.app.data.botmode.botPreviewLine
import com.clma.app.data.botmode.channelMessageText
import com.clma.app.data.botmode.channelTargets
import com.clma.app.data.botmode.hasPendingApproval
import com.clma.app.data.botmode.latestTopLevelConversation
import com.clma.app.data.botmode.withBotChannelRemoved
import com.clma.app.data.botmode.withBotChannelUpserted
import com.clma.app.data.datastore.BotChannel
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.Conversation
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.service.ChatService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** One member of a channel with its real latest-conversation state (nothing made up). */
data class BotChannelMemberRow(
    val bot: Assistant,
    val conversationId: Uuid?,
    val preview: String,
    val updatedAt: java.time.Instant?,
    val generating: Boolean,
    val pendingApproval: Boolean,
)

/** Result of the last channel send: which members got it. */
data class BotChannelSendResult(val sentTo: List<String>, val failed: List<String>)

@OptIn(ExperimentalCoroutinesApi::class)
class BotChannelVM(
    channelId: String,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
) : ViewModel() {
    private val id = Uuid.parse(channelId)

    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    val channel: StateFlow<BotChannel?> = settingsStore.settingsFlow
        .map { s -> s.displaySetting.botChannels.find { it.id == id } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsStore.settingsFlow.value.displaySetting.botChannels.find { it.id == id })

    private val members: Flow<List<Assistant>> = settingsStore.settingsFlow
        .map { s -> s.displaySetting.botChannels.find { it.id == id }?.botMembers(s).orEmpty() }
        .distinctUntilChanged()

    private val generatingIds: Flow<Set<Uuid>> = chatService.getConversationJobs()
        .map { jobs -> jobs.filterValues { it?.isActive == true }.keys }
        .distinctUntilChanged()

    val rows: StateFlow<List<BotChannelMemberRow>> = members
        .flatMapLatest { bots ->
            if (bots.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(bots.map { bot -> latestOf(bot.id).map { bot to it } }) { it.toList() }
            }
        }
        .let { latest ->
            combine(latest, generatingIds) { pairs, generating ->
                pairs.map { (bot, conv) ->
                    BotChannelMemberRow(
                        bot = bot,
                        conversationId = conv?.id,
                        preview = botPreviewLine(conv),
                        updatedAt = conv?.updateAt,
                        generating = conv?.id != null && conv.id in generating,
                        pendingApproval = hasPendingApproval(conv),
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun latestOf(botId: Uuid): Flow<Conversation?> =
        conversationRepo.getConversationsOfAssistant(botId)
            .map { latestTopLevelConversation(it) }
            .distinctUntilChanged { a, b -> a?.id == b?.id && a?.updateAt == b?.updateAt }
            .mapLatest { summary ->
                summary?.let { runCatching { conversationRepo.getConversationById(it.id) }.getOrNull() ?: it }
            }

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _lastResult = MutableStateFlow<BotChannelSendResult?>(null)
    val lastResult: StateFlow<BotChannelSendResult?> = _lastResult.asStateFlow()

    /** Members [text] would reach right now (@-mentions, or everyone). */
    fun targetsFor(text: String, bots: List<Assistant>): List<Assistant> {
        val ids = channelTargets(text, bots).toSet()
        return bots.filter { it.id in ids }
    }

    /**
     * Sends [text] to the targeted members, each into its own conversation, through the normal
     * chat pipeline. The current assistant is restored afterwards so the bot list is unchanged.
     */
    fun send(text: String) = viewModelScope.launch {
        val s = settingsStore.settingsFlow.value
        val ch = s.displaySetting.botChannels.find { it.id == id } ?: return@launch
        val bots = ch.botMembers(s)
        val targets = targetsFor(text, bots)
        if (text.isBlank() || targets.isEmpty()) return@launch
        _sending.value = true
        val previousAssistant = s.assistantId
        val names = bots.map { it.name }
        val message = channelMessageText(ch, names, text)
        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()
        try {
            targets.forEach { bot ->
                val result = runCatching { chatService.sendToBotConversation(bot.id, message) }.getOrNull()
                if (result != null) sent += bot.name else failed += bot.name
            }
        } finally {
            settingsStore.update { cur -> if (cur.assistants.any { it.id == previousAssistant }) cur.copy(assistantId = previousAssistant) else cur }
            _sending.value = false
            _lastResult.value = BotChannelSendResult(sent, failed)
        }
    }

    /** Opens a member's chat; the member becomes current first so a new chat binds to it. */
    fun openMember(row: BotChannelMemberRow, onReady: (Uuid) -> Unit) = viewModelScope.launch {
        settingsStore.update { cur -> cur.copy(assistantId = row.bot.id) }
        onReady(row.conversationId ?: Uuid.random())
    }

    fun save(next: BotChannel) = viewModelScope.launch {
        settingsStore.update { cur -> cur.copy(displaySetting = cur.displaySetting.withBotChannelUpserted(next)) }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        settingsStore.update { cur -> cur.copy(displaySetting = cur.displaySetting.withBotChannelRemoved(id)) }
        onDone()
    }
}
