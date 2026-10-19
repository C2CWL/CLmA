package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.ui.components.ui.FROSTED_PLATE_BLUR_DP
import me.rerere.rikkahub.ui.components.ui.FROSTED_PLATE_TINT_ALPHA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerFrostTest {

    @Test
    fun `same gating as the shared frosted plate`() {
        assertNull(drawerFrostedPlateSpec(photoShown = false, sdkInt = 35, darkTheme = false))
        assertNull(drawerFrostedPlateSpec(photoShown = true, sdkInt = 30, darkTheme = true))
    }

    @Test
    fun `drawer cards use a lighter tint per theme`() {
        val dark = drawerFrostedPlateSpec(photoShown = true, sdkInt = 31, darkTheme = true)!!
        val light = drawerFrostedPlateSpec(photoShown = true, sdkInt = 31, darkTheme = false)!!
        assertEquals(DRAWER_FROST_TINT_ALPHA_DARK, dark.tintAlpha, 0f)
        assertEquals(DRAWER_FROST_TINT_ALPHA_LIGHT, light.tintAlpha, 0f)
        assertTrue(dark.tintAlpha in 0.35f..0.45f)
        assertTrue(light.tintAlpha in 0.40f..0.50f)
        assertTrue(dark.tintAlpha < FROSTED_PLATE_TINT_ALPHA && light.tintAlpha < FROSTED_PLATE_TINT_ALPHA)
        // Blur is unchanged.
        assertEquals(FROSTED_PLATE_BLUR_DP, dark.blurRadiusDp, 0f)
    }
}
