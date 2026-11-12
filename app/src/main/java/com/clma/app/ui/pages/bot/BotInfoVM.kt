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

    /** Team-pack Markdown with just this bot, readable by the team-pack import. */
    fun teamPackMarkdown(bot: Assistant): String = toTeamPackMarkdown(bot.name, listOf(bot))
}
