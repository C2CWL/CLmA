package com.clma.app.data.botmode

import com.clma.app.data.datastore.BotUnreadEntry
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * Bot-mode contact list helpers. Assistants stay the source of truth; pin/hide/unread live on
 * [DisplaySetting] so classic mode and assistant detail pages stay untouched.
 */
fun DisplaySetting.isBotModeEnabled(): Boolean = botModeEnabled

fun Settings.botsForContactList(): List<Assistant> {
    val hidden = displaySetting.botHiddenAssistantIds.toSet()
    val pinnedOrder = displaySetting.botPinnedAssistantIds
    val pinnedSet = pinnedOrder.toSet()
    val visible = assistants.filter { it.id !in hidden }
    val pinned = pinnedOrder.mapNotNull { id -> visible.find { it.id == id } }
    val rest = visible.filter { it.id !in pinnedSet }
    return pinned + rest
}

/** Hidden bots that can be restored from Bot manage / settings. */
fun Settings.botsHiddenForManage(): List<Assistant> {
    val hiddenOrder = displaySetting.botHiddenAssistantIds
    val byId = assistants.associateBy { it.id }
    return hiddenOrder.mapNotNull { byId[it] }
}

fun DisplaySetting.withBotPinned(assistantId: Uuid, pinned: Boolean): DisplaySetting {
    val without = botPinnedAssistantIds.filterNot { it == assistantId }
    return copy(
        botPinnedAssistantIds = if (pinned) listOf(assistantId) + without else without,
    )
}

fun DisplaySetting.withBotHidden(assistantId: Uuid, hidden: Boolean): DisplaySetting {
    val without = botHiddenAssistantIds.filterNot { it == assistantId }
    return copy(
        botHiddenAssistantIds = if (hidden) without + assistantId else without,
    )
}

fun DisplaySetting.isBotPinned(assistantId: Uuid): Boolean =
    botPinnedAssistantIds.contains(assistantId)

fun DisplaySetting.isBotHidden(assistantId: Uuid): Boolean =
    botHiddenAssistantIds.contains(assistantId)

/**
 * Unread for Bot mode: one entry per conversation that finished (or needs attention) while the
 * user was looking at another chat. Badge count = entries for that assistant. Selecting the bot
 * clears every entry for it.
 */
fun DisplaySetting.botUnreadCount(assistantId: Uuid): Int =
    botUnreadEntries.count { it.assistantId == assistantId }

fun DisplaySetting.withBotUnreadMarked(
    conversationId: Uuid,
    assistantId: Uuid,
    maxEntries: Int = 200,
): DisplaySetting {
    if (botUnreadEntries.any { it.conversationId == conversationId }) return this
    val next = botUnreadEntries + BotUnreadEntry(conversationId, assistantId)
    return copy(botUnreadEntries = if (next.size <= maxEntries) next else next.takeLast(maxEntries))
}

fun DisplaySetting.withBotUnreadClearedForAssistant(assistantId: Uuid): DisplaySetting {
    if (botUnreadEntries.none { it.assistantId == assistantId }) return this
    return copy(botUnreadEntries = botUnreadEntries.filterNot { it.assistantId == assistantId })
}

fun DisplaySetting.withBotUnreadClearedConversation(conversationId: Uuid): DisplaySetting {
    if (botUnreadEntries.none { it.conversationId == conversationId }) return this
    return copy(botUnreadEntries = botUnreadEntries.filterNot { it.conversationId == conversationId })
}
