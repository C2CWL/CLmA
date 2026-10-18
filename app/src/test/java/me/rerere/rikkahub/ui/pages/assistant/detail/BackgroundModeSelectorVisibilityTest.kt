package me.rerere.rikkahub.ui.pages.assistant.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundModeSelectorVisibilityTest {

    @Test
    fun `hidden only when no wallpaper is set at all`() {
        assertFalse(showsBackgroundModeSelector(null, null, null))
    }

    @Test
    fun `shown for any single wallpaper slot`() {
        assertTrue(showsBackgroundModeSelector("common", null, null))
        assertTrue(showsBackgroundModeSelector(null, "dark", null))
        assertTrue(showsBackgroundModeSelector(null, null, "light"))
    }

    @Test
    fun `still shown after both theme wallpapers are cleared`() {
        // The reviewer's case: GENERAL was picked, then dark/light were removed; the common
        // wallpaper remains, so the selector must stay reachable to switch back to AUTO.
        assertTrue(showsBackgroundModeSelector("common", darkBackground = null, lightBackground = null))
    }
}
