package com.clma.app.data.botmode

import com.clma.app.data.datastore.BotUnreadEntry
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.AssistantLibrary
import com.clma.app.data.model.isBot
import kotlin.uuid.Uuid

/**
 * Bot-mode contact list helpers. Assistants stay the source of truth; pin/hide/unread live on
 * [DisplaySetting] so classic mode and assistant detail pages stay untouched.
 */
fun DisplaySetting.isBotModeEnabled(): Boolean = botModeEnabled

/** Classic-mode library: every assistant that is not a bot (all pre-existing data). */
fun Settings.classicAssistants(): List<Assistant> = assistants.filter { !it.isBot }

/** Bot-mode library. */
fun Settings.botAssistants(): List<Assistant> = assistants.filter { it.isBot }

/** The current main bot, if the pointer still points at a bot. */
fun Settings.mainBot(): Assistant? =
    displaySetting.botMainAssistantId?.let { id -> assistants.find { it.id == id && it.isBot } }

fun Settings.isMainBot(assistantId: Uuid): Boolean = mainBot()?.id == assistantId

/**
 * Bot list order: the main bot first (always shown, even if it was hidden), then pinned bots in
 * pin order, then the rest. Only the Bot library is listed.
 */
fun Settings.botsForContactList(): List<Assistant> {
    val main = mainBot()
    val hidden = displaySetting.botHiddenAssistantIds.toSet()
    val pinnedOrder = displaySetting.botPinnedAssistantIds
    val pinnedSet = pinnedOrder.toSet()
    val visible = botAssistants().filter { it.id !in hidden && it.id != main?.id }
    val pinned = pinnedOrder.mapNotNull { id -> visible.find { it.id == id } }
    val rest = visible.filter { it.id !in pinnedSet }
    return listOfNotNull(main) + pinned + rest
}

/**
 * Make sure the Bot library is usable: the first time Bot mode runs it creates the main bot;
 * afterwards it repairs a dangling main pointer by pointing at the first bot (or creating a
 * new main bot when the library is empty). Returns this unchanged when nothing is needed.
 */
fun Settings.ensureBotLibrary(newMainBot: () -> Assistant): Settings {
    if (mainBot() != null) return this
    val existing = botAssistants().firstOrNull()
    if (existing != null) {
        return copy(displaySetting = displaySetting.copy(botMainAssistantId = existing.id))
    }
    val created = newMainBot().copy(library = AssistantLibrary.BOT)
    return copy(
        assistants = assistants + created,
        displaySetting = displaySetting.copy(botMainAssistantId = created.id),
    )
}

/** Point the main-bot pointer at [assistantId]; the previous main bot becomes a normal bot. */
fun Settings.withMainBot(assistantId: Uuid): Settings {
    if (assistants.none { it.id == assistantId && it.isBot }) return this
    return copy(
        displaySetting = displaySetting.copy(
            botMainAssistantId = assistantId,
            botHiddenAssistantIds = displaySetting.botHiddenAssistantIds.filterNot { it == assistantId },
        ),
    )
}

/** The current main bot may not be deleted; replace it first. */
fun Settings.canDeleteAssistant(assistantId: Uuid): Boolean = !isMainBot(assistantId)

/**
 * The current assistant must belong to the active library. Returns settings whose
 * [Settings.assistantId] is in the right library (unchanged when it already is).
 */
fun Settings.withAssistantInLibrary(botMode: Boolean): Settings {
    val current = assistants.find { it.id == assistantId }
    if (current != null && current.isBot == botMode) return this
    val target = if (botMode) mainBot() ?: botAssistants().firstOrNull() else classicAssistants().firstOrNull()
    return if (target == null) this else copy(assistantId = target.id)
}

/** Hidden bots that can be restored from Bot manage / settings. */
fun Settings.botsHiddenForManage(): List<Assistant> {
    val hiddenOrder = displaySetting.botHiddenAssistantIds
    val byId = assistants.associateBy { it.id }
    return hiddenOrder.mapNotNull { byId[it] }.filter { it.isBot }
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
