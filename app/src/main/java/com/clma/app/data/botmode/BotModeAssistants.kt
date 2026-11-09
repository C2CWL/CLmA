package com.clma.app.data.botmode

import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * Bot-mode contact list helpers. Assistants stay the source of truth; pin/hide live on
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
