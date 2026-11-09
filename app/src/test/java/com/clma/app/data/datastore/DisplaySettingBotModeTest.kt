package com.clma.app.data.datastore

import com.clma.app.data.botmode.botsForContactList
import com.clma.app.data.botmode.withBotHidden
import com.clma.app.data.botmode.withBotPinned
import com.clma.app.data.model.Assistant
import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** Bot mode defaults off; pin/hide helpers keep assistants as the source of truth. */
class DisplaySettingBotModeTest {

    @Test
    fun `bot mode defaults to off`() {
        assertFalse(DisplaySetting().botModeEnabled)
        assertTrue(DisplaySetting().botPinnedAssistantIds.isEmpty())
        assertTrue(DisplaySetting().botHiddenAssistantIds.isEmpty())
    }

    @Test
    fun `legacy settings without the key read back as off`() {
        val decoded = JsonInstant.decodeFromString<DisplaySetting>("""{"drawerCapsules":true}""")
        assertFalse(decoded.botModeEnabled)
    }

    @Test
    fun `bot mode round-trips`() {
        val json = JsonInstant.encodeToString(DisplaySetting(botModeEnabled = true))
        assertTrue(json.contains("\"botModeEnabled\":true"))
        assertTrue(JsonInstant.decodeFromString<DisplaySetting>(json).botModeEnabled)
    }

    @Test
    fun `contact list pins first and skips hidden`() {
        val a = Assistant(id = Uuid.parse("11111111-1111-1111-1111-111111111111"), name = "A")
        val b = Assistant(id = Uuid.parse("22222222-2222-2222-2222-222222222222"), name = "B")
        val c = Assistant(id = Uuid.parse("33333333-3333-3333-3333-333333333333"), name = "C")
        val settings = Settings(
            assistants = listOf(a, b, c),
            displaySetting = DisplaySetting(
                botModeEnabled = true,
                botPinnedAssistantIds = listOf(c.id),
                botHiddenAssistantIds = listOf(b.id),
            ),
        )
        assertEquals(listOf(c, a), settings.botsForContactList())
    }

    @Test
    fun `pin and hide helpers toggle lists`() {
        val id = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val pinned = DisplaySetting().withBotPinned(id, true)
        assertEquals(listOf(id), pinned.botPinnedAssistantIds)
        val unpinned = pinned.withBotPinned(id, false)
        assertTrue(unpinned.botPinnedAssistantIds.isEmpty())
        val hidden = DisplaySetting().withBotHidden(id, true)
        assertEquals(listOf(id), hidden.botHiddenAssistantIds)
    }
}
