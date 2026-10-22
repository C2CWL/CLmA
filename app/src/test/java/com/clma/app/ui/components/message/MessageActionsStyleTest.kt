package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageActionsStyleTest {

    @Test
    fun `no photo keeps the original look`() {
        for (sdk in listOf(26, 30, 31, 35)) for (haze in listOf(true, false)) {
            val style = messageActionsStyle(photoShown = false, sdkInt = sdk, hasHazeState = haze)
            assertEquals(DEFAULT_MESSAGE_ACTIONS_STYLE, style)
            assertFalse(style.onSurfaceInk)
            assertEquals(1f, style.iconAlpha, 0f)
            assertEquals(0.6f, style.timeAlpha, 0f)
            assertFalse(style.plated)
        }
    }

    @Test
    fun `photo on API 31+ uses the usage line's frosted pill`() {
        val style = messageActionsStyle(photoShown = true, sdkInt = 31, hasHazeState = true)
        val usage = usageLineStyle(photoShown = true, sdkInt = 31, hasHazeState = true)
        assertTrue(style.onSurfaceInk)
        assertNotNull(style.frost)
        assertEquals(usage.frost, style.frost)
        assertNull(style.plateAlpha)
        assertEquals(usage.inkAlpha, style.timeAlpha, 0f)
        assertTrue(style.iconAlpha >= style.timeAlpha)
    }

    @Test
    fun `photo without blur falls back to the flat title plate like the usage line`() {
        for ((sdk, haze) in listOf(30 to true, 31 to false, 26 to false)) {
            val style = messageActionsStyle(photoShown = true, sdkInt = sdk, hasHazeState = haze)
            val usage = usageLineStyle(photoShown = true, sdkInt = sdk, hasHazeState = haze)
            assertNull(style.frost)
            assertEquals(usage.plateAlpha, style.plateAlpha)
            assertTrue(style.plated)
            assertEquals(usage.inkAlpha, style.timeAlpha, 0f)
        }
    }
}
