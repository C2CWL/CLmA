package com.clma.app.ui.pages.bot

import androidx.navigation3.runtime.NavKey
import com.clma.app.Screen
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
        assertEquals(listOf(Screen.BotHome, Screen.Chat("d")), initialRootStack(true, listOf("a", "d"), "d"))
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
}
