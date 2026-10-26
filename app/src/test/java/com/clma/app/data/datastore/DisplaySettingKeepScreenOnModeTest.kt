package com.clma.app.data.datastore

import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证屏幕常亮模式的默认值、序列化与向后兼容性。
 */
class DisplaySettingKeepScreenOnModeTest {

    @Test
    fun `keep screen on mode is while generating by default`() {
        assertEquals(KeepScreenOnMode.WHILE_GENERATING, DisplaySetting().keepScreenOnMode)
    }

    @Test
    fun `stored settings without the key read back as while generating`() {
        val legacy = """{"showUserAvatar":false,"showTokenUsage":true}"""
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertEquals(KeepScreenOnMode.WHILE_GENERATING, decoded.keepScreenOnMode)
        assertEquals(false, decoded.showUserAvatar)
    }

    @Test
    fun `never mode round-trips`() {
        val json = JsonInstant.encodeToString(DisplaySetting(keepScreenOnMode = KeepScreenOnMode.NEVER))
        assertEquals(true, json.contains("\"keepScreenOnMode\":\"never\""))
        assertEquals(
            KeepScreenOnMode.NEVER,
            JsonInstant.decodeFromString<DisplaySetting>(json).keepScreenOnMode,
        )
    }

    @Test
    fun `always in chat mode round-trips`() {
        val json = JsonInstant.encodeToString(DisplaySetting(keepScreenOnMode = KeepScreenOnMode.ALWAYS_IN_CHAT))
        assertEquals(true, json.contains("\"keepScreenOnMode\":\"always_in_chat\""))
        assertEquals(
            KeepScreenOnMode.ALWAYS_IN_CHAT,
            JsonInstant.decodeFromString<DisplaySetting>(json).keepScreenOnMode,
        )
    }

    @Test
    fun `while generating mode round-trips`() {
        val json = JsonInstant.encodeToString(DisplaySetting(keepScreenOnMode = KeepScreenOnMode.WHILE_GENERATING))
        assertEquals(true, json.contains("\"keepScreenOnMode\":\"while_generating\""))
        assertEquals(
            KeepScreenOnMode.WHILE_GENERATING,
            JsonInstant.decodeFromString<DisplaySetting>(json).keepScreenOnMode,
        )
    }

    @Test
    fun `fromSerialName handles unknown, null and case variations safely`() {
        assertEquals(KeepScreenOnMode.WHILE_GENERATING, KeepScreenOnMode.fromSerialName(null))
        assertEquals(KeepScreenOnMode.WHILE_GENERATING, KeepScreenOnMode.fromSerialName(""))
        assertEquals(KeepScreenOnMode.WHILE_GENERATING, KeepScreenOnMode.fromSerialName("unknown_value"))
        assertEquals(KeepScreenOnMode.NEVER, KeepScreenOnMode.fromSerialName("NEVER"))
        assertEquals(KeepScreenOnMode.ALWAYS_IN_CHAT, KeepScreenOnMode.fromSerialName("always_in_chat"))
    }
}
