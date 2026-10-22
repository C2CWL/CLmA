package com.clma.app.data.datastore

import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat layout is a three-way selector that supersedes the old cleanLayout switch. Installs that
 * only knew the boolean must keep looking the same until the user picks an option.
 */
class DisplaySettingChatLayoutTest {

    @Test
    fun `chat layout is unset by default and resolves to original`() {
        val setting = DisplaySetting()
        assertNull(setting.chatLayout)
        assertEquals(ChatLayout.ORIGINAL, setting.effectiveChatLayout)
        assertFalse(setting.cleanLayout)
    }

    @Test
    fun `legacy cleanLayout true resolves to clean when chatLayout is unset`() {
        val legacy = """{"cleanLayout":true}"""
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertNull(decoded.chatLayout)
        assertTrue(decoded.cleanLayout)
        assertEquals(ChatLayout.CLEAN, decoded.effectiveChatLayout)
    }

    @Test
    fun `legacy cleanLayout false resolves to original when chatLayout is unset`() {
        val legacy = """{"cleanLayout":false,"showUserAvatar":true}"""
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertEquals(ChatLayout.ORIGINAL, decoded.effectiveChatLayout)
    }

    @Test
    fun `withChatLayout keeps the legacy switch in step`() {
        val original = DisplaySetting().withChatLayout(ChatLayout.ORIGINAL)
        assertEquals(ChatLayout.ORIGINAL, original.chatLayout)
        assertFalse(original.cleanLayout)

        val clean = DisplaySetting().withChatLayout(ChatLayout.CLEAN)
        assertEquals(ChatLayout.CLEAN, clean.chatLayout)
        assertTrue(clean.cleanLayout)

        // CLAUDE maps cleanLayout=true so an older build falls back to the closer chrome.
        val claude = DisplaySetting().withChatLayout(ChatLayout.CLAUDE)
        assertEquals(ChatLayout.CLAUDE, claude.chatLayout)
        assertTrue(claude.cleanLayout)
        assertEquals(ChatLayout.CLAUDE, claude.effectiveChatLayout)
    }

    @Test
    fun `explicit chatLayout round-trips and wins over the legacy switch`() {
        val json = JsonInstant.encodeToString(
            DisplaySetting(chatLayout = ChatLayout.CLAUDE, cleanLayout = false),
        )
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(json)
        assertEquals(ChatLayout.CLAUDE, decoded.chatLayout)
        // Explicit chatLayout wins even if the legacy flag disagrees.
        assertEquals(ChatLayout.CLAUDE, decoded.effectiveChatLayout)
    }

    @Test
    fun `loading indicator style includes claude and leaves the legacy switch alone`() {
        val setting = DisplaySetting().withLoadingIndicatorStyle(LoadingIndicatorStyle.CLAUDE)
        assertEquals(LoadingIndicatorStyle.CLAUDE, setting.loadingIndicatorStyle)
        assertEquals(LoadingIndicatorStyle.CLAUDE, setting.effectiveLoadingIndicatorStyle)
    }
}
