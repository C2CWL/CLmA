package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.pages.chat.PHOTO_TITLE_PLATE_ALPHA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLineStyleTest {

    @Test fun `no photo - quiet secondary, no plate, never frosted`() {
        for (sdk in listOf(26, 30, 31, 35)) for (haze in listOf(true, false)) {
            val style = usageLineStyle(photoShown = false, sdkInt = sdk, hasHazeState = haze)
            assertEquals(UsageLineInk.SECONDARY, style.ink)
            assertEquals(0.5f, style.inkAlpha, 0f)
            assertNull(style.plateAlpha)
            assertNull(style.frost)
        }
    }

    @Test fun `photo on API 31+ - frosted pill with the shared plate values`() {
        val style = usageLineStyle(photoShown = true, sdkInt = 31, hasHazeState = true)
        assertEquals(UsageLineInk.ON_SURFACE, style.ink)
        assertEquals(0.85f, style.inkAlpha, 0f)
        assertNull(style.plateAlpha)
        assertEquals(UsageFrostSpec(tintAlpha = 0.70f, blurRadiusDp = 20f), style.frost)
        // The blur carries part of the contrast, so the tint is lighter than the flat plate.
        assertTrue(style.frost!!.tintAlpha < PHOTO_TITLE_PLATE_ALPHA)
    }

    @Test fun `photo below API 31 - flat title plate`() {
        val style = usageLineStyle(photoShown = true, sdkInt = 30, hasHazeState = true)
        assertEquals(UsageLineInk.ON_SURFACE, style.ink)
        assertEquals(0.72f, style.inkAlpha, 0f)
        assertEquals(PHOTO_TITLE_PLATE_ALPHA, style.plateAlpha!!, 0f)
        assertNull(style.frost)
    }

    @Test fun `photo without haze state - flat title plate`() {
        val style = usageLineStyle(photoShown = true, sdkInt = 35, hasHazeState = false)
        assertEquals(PHOTO_TITLE_PLATE_ALPHA, style.plateAlpha!!, 0f)
        assertNull(style.frost)
    }

    @Test fun `frost spec needs photo, haze and API 31`() {
        assertNull(usageFrostSpec(photoShown = false, sdkInt = 35, hasHazeState = true))
        assertNull(usageFrostSpec(photoShown = true, sdkInt = 30, hasHazeState = true))
        assertNull(usageFrostSpec(photoShown = true, sdkInt = 35, hasHazeState = false))
        assertEquals(31, USAGE_FROST_MIN_SDK)
    }
}
