package com.clma.app.ui.pages.bot

import androidx.navigation3.runtime.NavKey
import com.clma.app.Screen
import com.clma.app.data.botmode.isBotAssistantId
import com.clma.app.data.datastore.Settings
import kotlin.uuid.Uuid

/**
 * Bot mode and the classic layout use different roots: [Screen.BotHome] vs a [Screen.Chat].
 * These helpers keep the back stack on the right root; classic stacks never contain BotHome,
 * so every helper is a no-op for them.
 */

/** Shared pref mirror of `displaySetting.botModeEnabled`, read synchronously at cold start. */
const val BOT_MODE_ROOT_PREF_KEY = "bot_mode_root_enabled"

/** Initial back stack at cold start. */
fun initialRootStack(botMode: Boolean, chatIds: List<String>, deepLinkConversationId: String?): List<NavKey> =
    if (botMode) {
        // A deep link is resolved by the activity's intent handling once the real settings and
        // the conversation's library are known ([conversationLinkTarget]); never push the classic
        // chat page onto the Bot root.
        listOf(Screen.BotHome)
    } else {
        chatIds.map { Screen.Chat(it) }
    }

/**
 * Move [stack] onto the root for [botMode] after the switch flips (or when the real settings
 * arrive after a cold start). Screens pushed above the root — e.g. the settings page where the
 * switch lives — stay, so the user is not yanked away from where they are.
 */
fun reconcileRootStack(stack: MutableList<NavKey>, botMode: Boolean, newChat: () -> Screen.Chat) {
    if (botMode) {
        if (stack.firstOrNull() == Screen.BotHome) return
        while (stack.firstOrNull() is Screen.Chat) stack.removeAt(0)
        stack.add(0, Screen.BotHome)
    } else {
        if (Screen.BotHome !in stack) return
        // Bot chats, bot info and channel pages belong to Bot mode too; classic opens
        // conversations in its own page.
        stack.removeAll {
            it == Screen.BotHome || it is Screen.BotChat || it is Screen.BotInfo || it is Screen.BotChannel
        }
        if (stack.firstOrNull() !is Screen.Chat) stack.add(0, newChat())
    }
}

/**
 * "Replace the stack with [screen]" — but in Bot mode the Bot home stays underneath, so Back
 * from a chat returns to the bot list instead of leaving the app.
 */
fun resetStackTo(stack: MutableList<NavKey>, screen: NavKey) {
    val keepBotHome = stack.firstOrNull() == Screen.BotHome && screen != Screen.BotHome
    stack.clear()
    if (keepBotHome) stack.add(Screen.BotHome)
    stack.add(screen)
}

/** Whether [stack] already is exactly what [resetStackTo] would produce. */
fun isStackResetTo(stack: List<NavKey>, screen: NavKey): Boolean {
    val expected = if (stack.firstOrNull() == Screen.BotHome && screen != Screen.BotHome) {
        listOf(Screen.BotHome, screen)
    } else {
        listOf(screen)
    }
    return stack == expected
}

/** Conversation id carried by a conversation route (classic or Bot chat), else null. */
fun NavKey.routeConversationId(): String? = when (this) {
    is Screen.Chat -> id
    is Screen.BotChat -> conversationId
    else -> null
}

/**
 * Where "open conversation [conversationId]" goes on the current root. [owner] is the assistant
 * the stored conversation is bound to (null: not created yet, it will belong to the current
 * assistant). Returns null when the conversation lives in the other library: the link is
 * refused instead of switching mode behind the user's back (switching stays an explicit setting).
 */
fun conversationLinkTarget(
    botRoot: Boolean,
    conversationId: String,
    owner: Uuid?,
    settings: Settings,
): Screen? {
    if (owner == null) {
        return if (botRoot) Screen.BotChat(settings.assistantId.toString(), conversationId) else Screen.Chat(conversationId)
    }
    val ownerIsBot = settings.isBotAssistantId(owner)
    return when {
        botRoot && ownerIsBot -> Screen.BotChat(owner.toString(), conversationId)
        !botRoot && !ownerIsBot -> Screen.Chat(conversationId)
        else -> null
    }
}

/**
 * [stack] with every conversation page that belongs to the other library removed (a Bot
 * conversation in a classic chat page or vice versa, e.g. restored at cold start). A Bot
 * conversation in a classic page on the Bot root moves to its Bot chat page. [owners] maps the
 * conversation ids of the stack to their assistant (missing / null = not created yet, kept).
 * Returns [stack] itself when nothing needs to change. Nothing is deleted from storage.
 */
fun libraryGuardedStack(
    stack: List<NavKey>,
    settings: Settings,
    owners: Map<String, Uuid?>,
    newChat: () -> Screen.Chat,
): List<NavKey> {
    val botRoot = stack.firstOrNull() == Screen.BotHome
    var changed = false
    val guarded = stack.mapNotNull { key ->
        val id = key.routeConversationId() ?: return@mapNotNull key
        val owner = owners[id] ?: return@mapNotNull key
        val ownerIsBot = settings.isBotAssistantId(owner)
        when {
            key is Screen.BotChat && botRoot && ownerIsBot -> key
            key is Screen.Chat && !botRoot && !ownerIsBot -> key
            key is Screen.Chat && botRoot && ownerIsBot -> {
                changed = true
                Screen.BotChat(owner.toString(), id)
            }
            else -> {
                changed = true
                null
            }
        }
    }
    if (!changed) return stack
    if (!botRoot && guarded.firstOrNull() !is Screen.Chat) return listOf(newChat()) + guarded
    return guarded
}
