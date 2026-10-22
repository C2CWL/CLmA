package com.clma.app.data.datastore

import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingIndicatorStyleTest {

    @Test
    fun `unset style resolves to the app icon whatever the legacy switch says`() {
        assertEquals(LoadingIndicatorStyle.APP_ICON, resolveLoadingIndicatorStyle(null, useAppIconStyle = true))
        assertEquals(LoadingIndicatorStyle.APP_ICON, resolveLoadingIndicatorStyle(null, useAppIconStyle = false))
    }

    @Test
    fun `picked style wins over the legacy switch`() {
        for (flag in listOf(true, false)) {
            for (style in LoadingIndicatorStyle.entries) {
                assertEquals(style, resolveLoadingIndicatorStyle(style, flag))
            }
        }
    }

    @Test
    fun `fresh settings show the app icon`() {
        assertEquals(LoadingIndicatorStyle.APP_ICON, DisplaySetting().effectiveLoadingIndicatorStyle)
    }

    @Test
    fun `picking keeps the legacy switch in step`() {
        val off = DisplaySetting(useAppIconStyleLoadingIndicator = false)
        assertTrue(off.withLoadingIndicatorStyle(LoadingIndicatorStyle.APP_ICON).useAppIconStyleLoadingIndicator)
        // New styles have no legacy equivalent: the switch is left as it was.
        for (style in listOf(
            LoadingIndicatorStyle.ATOM_DOTS,
            LoadingIndicatorStyle.BREATHING_ORB,
            LoadingIndicatorStyle.TWIN_ORBIT,
        )) {
            assertFalse(off.withLoadingIndicatorStyle(style).useAppIconStyleLoadingIndicator)
            assertTrue(DisplaySetting().withLoadingIndicatorStyle(style).useAppIconStyleLoadingIndicator)
            assertEquals(style, off.withLoadingIndicatorStyle(style).effectiveLoadingIndicatorStyle)
        }
    }

    @Test
    fun `every style survives a save and load`() {
        for (style in LoadingIndicatorStyle.entries) {
            val json = JsonInstant.encodeToString(DisplaySetting().withLoadingIndicatorStyle(style))
            val decoded = JsonInstant.decodeFromString<DisplaySetting>(json)
            assertEquals(style, decoded.loadingIndicatorStyle)
        }
    }

    @Test
    fun `removed and unknown saved styles fall back to the app icon`() {
        for (name in listOf("default", "shimmer", "sparkle", "claude", "something_new")) {
            val decoded = JsonInstant.decodeFromString<DisplaySetting>(
                """{"loadingIndicatorStyle":"$name","useAppIconStyleLoadingIndicator":false,"userNickname":"keep me"}"""
            )
            assertEquals(LoadingIndicatorStyle.APP_ICON, decoded.loadingIndicatorStyle)
            assertEquals(LoadingIndicatorStyle.APP_ICON, decoded.effectiveLoadingIndicatorStyle)
            // The rest of the display setting is not thrown away.
            assertEquals("keep me", decoded.userNickname)
        }
    }

    @Test
    fun `missing or null saved style also shows the app icon`() {
        val missing = JsonInstant.decodeFromString<DisplaySetting>("""{"useAppIconStyleLoadingIndicator":false}""")
        assertEquals(LoadingIndicatorStyle.APP_ICON, missing.effectiveLoadingIndicatorStyle)
        val explicitNull = JsonInstant.decodeFromString<DisplaySetting>("""{"loadingIndicatorStyle":null}""")
        assertEquals(LoadingIndicatorStyle.APP_ICON, explicitNull.effectiveLoadingIndicatorStyle)
    }
}
