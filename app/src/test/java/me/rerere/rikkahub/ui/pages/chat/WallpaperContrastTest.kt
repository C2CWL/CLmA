package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the light/dark split for a chat photo. The dark theme's veil is the
 * historical 0.20 → 0.50 (a dark photo is unchanged by it). The light theme must
 * not paint that same ramp in white.
 */
class WallpaperContrastTest {

    @Test fun `a photo is shown only when a background is set and the mesh gradient is off`() {
        assertTrue(chatPhotoShown("file://a", useGradientBackground = false))
        assertFalse(chatPhotoShown(null, useGradientBackground = false))
        assertFalse(chatPhotoShown("file://a", useGradientBackground = true))
    }

    @Test fun `light theme does not veil a photo`() {
        val scrim = wallpaperScrim(darkTheme = false)
        assertEquals(0f, scrim.topAlpha, 0f)
        assertEquals(0f, scrim.bottomAlpha, 0f)
    }

    @Test fun `dark theme keeps the historical veil`() {
        val scrim = wallpaperScrim(darkTheme = true)
        assertEquals(0.20f, scrim.topAlpha, 0f)
        assertEquals(0.50f, scrim.bottomAlpha, 0f)
    }

    @Test fun `title fade is unchanged without a photo and in the dark theme`() {
        val noPhoto = titleBarFade(darkTheme = false, photoShown = false)
        val darkPhoto = titleBarFade(darkTheme = true, photoShown = true)
        val historical = TitleBarFade(topAlpha = 1f, midAlpha = 0.75f, bottomAlpha = 0f)
        assertEquals(historical, noPhoto)
        assertEquals(historical, darkPhoto)
    }

    @Test fun `light photo fade covers the status icons and clears at the bottom`() {
        val fade = titleBarFade(darkTheme = false, photoShown = true)
        assertTrue(fade.topAlpha >= 0.7f)
        assertTrue(fade.midAlpha < 0.75f)
        assertEquals(0f, fade.bottomAlpha, 0f)
    }

    @Test fun `bare assistant text gets a plate only on a light-theme photo`() {
        assertTrue(assistantTextNeedsPhotoPlate(darkTheme = false, photoShown = true, showAssistantBubble = false))
        assertFalse(assistantTextNeedsPhotoPlate(darkTheme = true, photoShown = true, showAssistantBubble = false))
        assertFalse(assistantTextNeedsPhotoPlate(darkTheme = false, photoShown = false, showAssistantBubble = false))
        assertFalse(assistantTextNeedsPhotoPlate(darkTheme = false, photoShown = true, showAssistantBubble = true))
    }

    @Test fun `loose text gets a plate on a light-theme photo even when bubbles are on`() {
        assertTrue(looseTextNeedsPhotoPlate(darkTheme = false, photoShown = true))
        assertFalse(looseTextNeedsPhotoPlate(darkTheme = true, photoShown = true))
        assertFalse(looseTextNeedsPhotoPlate(darkTheme = false, photoShown = false))
    }
}
