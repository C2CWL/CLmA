package com.clma.app.ui.pages.bot

import androidx.navigation3.runtime.NavKey
import com.clma.app.Screen
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.AssistantLibrary
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotNavigationTest {

    private val newChat = { Screen.Chat("new") }

    @Test
    fun `cold start root follows the mode`() {
        assertEquals(listOf(Screen.Chat("a"), Screen.Chat("b")), initialRootStack(false, listOf("a", "b"), "b"))
        assertEquals(listOf(Screen.BotHome), initialRootStack(true, listOf("a"), null))
        // The Bot root never starts on the classic chat page; the link is resolved per library later.
        assertEquals(listOf(Screen.BotHome), initialRootStack(true, listOf("a", "d"), "d"))
    }

    @Test
    fun `turning bot mode on swaps the chat root and keeps the settings page on top`() {
        val stack = mutableListOf<NavKey>(Screen.Chat("a"), Screen.Setting, Screen.SettingChatLayout)
        reconcileRootStack(stack, botMode = true, newChat = newChat)
        assertEquals(listOf(Screen.BotHome, Screen.Setting, Screen.SettingChatLayout), stack)
        // Idempotent.
        reconcileRootStack(stack, botMode = true, newChat = newChat)
        assertEquals(listOf(Screen.BotHome, Screen.Setting, Screen.SettingChatLayout), stack)
    }

    @Test
    fun `turning bot mode off puts a chat back at the root`() {
        val stack = mutableListOf<NavKey>(Screen.BotHome, Screen.Setting, Screen.SettingChatLayout)
        reconcileRootStack(stack, botMode = false, newChat = newChat)
        assertEquals(listOf(Screen.Chat("new"), Screen.Setting, Screen.SettingChatLayout), stack)

        val chatOnTop = mutableListOf<NavKey>(Screen.BotHome, Screen.Chat("x"))
        reconcileRootStack(chatOnTop, botMode = false, newChat = newChat)
        assertEquals(listOf(Screen.Chat("x")), chatOnTop)
    }

    @Test
    fun `classic stacks are untouched when bot mode stays off`() {
        val stack = mutableListOf<NavKey>(Screen.Chat("a"), Screen.Setting)
        reconcileRootStack(stack, botMode = false, newChat = newChat)
        assertEquals(listOf(Screen.Chat("a"), Screen.Setting), stack)
    }

    @Test
    fun `reset keeps bot home underneath only in bot mode`() {
        val classic = mutableListOf<NavKey>(Screen.Chat("a"), Screen.Setting)
        resetStackTo(classic, Screen.Chat("b"))
        assertEquals(listOf(Screen.Chat("b")), classic)
        assertTrue(isStackResetTo(classic, Screen.Chat("b")))

        val bot = mutableListOf<NavKey>(Screen.BotHome, Screen.Chat("a"), Screen.Setting)
        assertFalse(isStackResetTo(bot, Screen.Chat("b")))
        resetStackTo(bot, Screen.Chat("b"))
        assertEquals(listOf(Screen.BotHome, Screen.Chat("b")), bot)
        assertTrue(isStackResetTo(bot, Screen.Chat("b")))
    }

    @Test
    fun `turning bot mode off drops bot chats with bot home`() {
        val stack = mutableListOf<NavKey>(Screen.BotHome, Screen.BotChat("b", "c"), Screen.Setting)
        reconcileRootStack(stack, botMode = false, newChat = newChat)
        assertEquals(listOf(Screen.Chat("new"), Screen.Setting), stack)
    }

    @Test
    fun `turning bot mode off drops bot info and channel pages too`() {
        val stack = mutableListOf<NavKey>(
            Screen.BotHome,
            Screen.BotChat("b", "c"),
            Screen.BotInfo("b", "c"),
            Screen.BotChannel("ch"),
            Screen.Setting,
        )
        reconcileRootStack(stack, botMode = false, newChat = newChat)
        assertEquals(listOf(Screen.Chat("new"), Screen.Setting), stack)
    }

    private val classic = Assistant(id = Uuid.parse("11111111-1111-1111-1111-111111111111"), name = "Classic")
    private val bot = Assistant(id = Uuid.parse("22222222-2222-2222-2222-222222222222"), name = "Bot", library = AssistantLibrary.BOT)
    private val settings = Settings(
        assistantId = bot.id,
        assistants = listOf(classic, bot),
        displaySetting = DisplaySetting(botModeEnabled = true),
    )

    @Test
    fun `conversation links stay inside the library of the current root`() {
        // Bot root: Bot conversations open in their bot's chat page, classic ones are refused.
        assertEquals(Screen.BotChat(bot.id.toString(), "c1"), conversationLinkTarget(true, "c1", bot.id, settings))
        assertNull(conversationLinkTarget(true, "c2", classic.id, settings))
        // Classic root: the mirror image.
        assertEquals(Screen.Chat("c2"), conversationLinkTarget(false, "c2", classic.id, settings))
        assertNull(conversationLinkTarget(false, "c1", bot.id, settings))
        // Orphans (assistant deleted) are classic, as all pre-existing data.
        assertEquals(Screen.Chat("c3"), conversationLinkTarget(false, "c3", Uuid.random(), settings))
        // Not created yet: belongs to the current assistant on the current root.
        assertEquals(Screen.BotChat(bot.id.toString(), "n"), conversationLinkTarget(true, "n", null, settings))
        assertEquals(Screen.Chat("n"), conversationLinkTarget(false, "n", null, settings))
    }

    @Test
    fun `classic root drops bot conversations restored into the classic chat page`() {
        val stack = listOf<NavKey>(Screen.Chat("botConv"), Screen.Setting)
        val guarded = libraryGuardedStack(stack, settings, mapOf("botConv" to bot.id), newChat)
        assertEquals(listOf(Screen.Chat("new"), Screen.Setting), guarded)
    }

    @Test
    fun `bot root moves bot conversations to the bot page and drops classic ones`() {
        val stack = listOf<NavKey>(Screen.BotHome, Screen.Chat("botConv"), Screen.Chat("classicConv"), Screen.Setting)
        val owners = mapOf("botConv" to bot.id, "classicConv" to classic.id)
        assertEquals(
            listOf(Screen.BotHome, Screen.BotChat(bot.id.toString(), "botConv"), Screen.Setting),
            libraryGuardedStack(stack, settings, owners, newChat),
        )
        val botChatOfClassic = listOf<NavKey>(Screen.BotHome, Screen.BotChat(bot.id.toString(), "classicConv"))
        assertEquals(listOf(Screen.BotHome), libraryGuardedStack(botChatOfClassic, settings, owners, newChat))
    }

    @Test
    fun `matching stacks and new conversations are left alone`() {
        val classicStack = listOf<NavKey>(Screen.Chat("classicConv"), Screen.Chat("brandNew"))
        val owners = mapOf("classicConv" to classic.id, "brandNew" to null)
        assertSame(classicStack, libraryGuardedStack(classicStack, settings, owners, newChat))
        val botStack = listOf<NavKey>(Screen.BotHome, Screen.BotChat(bot.id.toString(), "botConv"), Screen.BotInfo(bot.id.toString()))
        assertSame(botStack, libraryGuardedStack(botStack, settings, mapOf("botConv" to bot.id), newChat))
    }
}
