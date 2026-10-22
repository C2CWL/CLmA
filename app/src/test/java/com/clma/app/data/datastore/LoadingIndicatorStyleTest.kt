package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingIndicatorStyleTest {

    @Test
    fun `unset style follows the legacy switch`() {
        assertEquals(LoadingIndicatorStyle.APP_ICON, resolveLoadingIndicatorStyle(null, useAppIconStyle = true))
        assertEquals(LoadingIndicatorStyle.DEFAULT, resolveLoadingIndicatorStyle(null, useAppIconStyle = false))
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
    fun `fresh settings reproduce the old default`() {
        assertEquals(LoadingIndicatorStyle.APP_ICON, DisplaySetting().effectiveLoadingIndicatorStyle)
    }

    @Test
    fun `picking keeps the legacy switch in step`() {
        val off = DisplaySetting(useAppIconStyleLoadingIndicator = false)
        assertTrue(off.withLoadingIndicatorStyle(LoadingIndicatorStyle.APP_ICON).useAppIconStyleLoadingIndicator)
        assertFalse(DisplaySetting().withLoadingIndicatorStyle(LoadingIndicatorStyle.DEFAULT).useAppIconStyleLoadingIndicator)
        // Shimmer has no legacy equivalent: the switch is left as it was.
        assertFalse(off.withLoadingIndicatorStyle(LoadingIndicatorStyle.SHIMMER).useAppIconStyleLoadingIndicator)
        assertTrue(DisplaySetting().withLoadingIndicatorStyle(LoadingIndicatorStyle.SHIMMER).useAppIconStyleLoadingIndicator)
        assertEquals(
            LoadingIndicatorStyle.SHIMMER,
            off.withLoadingIndicatorStyle(LoadingIndicatorStyle.SHIMMER).effectiveLoadingIndicatorStyle,
        )
    }

    @Test
    fun `sparkle keeps the legacy switch`() {
        val off = DisplaySetting(useAppIconStyleLoadingIndicator = false)
        val picked = off.withLoadingIndicatorStyle(LoadingIndicatorStyle.SPARKLE)
        assertFalse(picked.useAppIconStyleLoadingIndicator)
        assertEquals(LoadingIndicatorStyle.SPARKLE, picked.effectiveLoadingIndicatorStyle)
        assertTrue(DisplaySetting().withLoadingIndicatorStyle(LoadingIndicatorStyle.SPARKLE).useAppIconStyleLoadingIndicator)
    }
}
