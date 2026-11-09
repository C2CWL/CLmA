package com.clma.app.ui.pages.bot

import androidx.navigation3.runtime.NavKey
import com.clma.app.Screen

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
        buildList {
            add(Screen.BotHome)
            if (deepLinkConversationId != null) add(Screen.Chat(deepLinkConversationId))
        }
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
        // Bot chats belong to Bot mode too; classic opens conversations in its own page.
        stack.removeAll { it == Screen.BotHome || it is Screen.BotChat }
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
