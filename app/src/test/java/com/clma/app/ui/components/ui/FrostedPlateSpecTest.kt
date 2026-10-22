package me.rerere.rikkahub.ui.components.ui

import me.rerere.rikkahub.ui.pages.chat.PHOTO_TITLE_PLATE_ALPHA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks when the title plate and drawer cards turn frosted and with which values. Every other
 * case returns null, and the caller keeps its previous flat look.
 */
class FrostedPlateSpecTest {

    @Test fun `no photo keeps the flat look on any API level`() {
        assertNull(frostedPlateSpec(photoShown = false, sdkInt = 30))
        assertNull(frostedPlateSpec(photoShown = false, sdkInt = 35))
    }

    @Test fun `below API 31 a photo keeps the flat plate`() {
        assertNull(frostedPlateSpec(photoShown = true, sdkInt = 30))
        assertNull(frostedPlateSpec(photoShown = true, sdkInt = 26))
    }

    @Test fun `API 31 and up frost over a photo`() {
        assertNotNull(frostedPlateSpec(photoShown = true, sdkInt = FROSTED_PLATE_MIN_SDK))
        val spec = frostedPlateSpec(photoShown = true, sdkInt = 35)!!
        assertEquals(FROSTED_PLATE_TINT_ALPHA, spec.tintAlpha, 0f)
        assertEquals(FROSTED_PLATE_BLUR_DP, spec.blurRadiusDp, 0f)
    }

    @Test fun `the frosted tint is lighter than the flat title plate it replaces`() {
        assertTrue(FROSTED_PLATE_TINT_ALPHA < PHOTO_TITLE_PLATE_ALPHA)
        assertTrue(FROSTED_PLATE_TINT_ALPHA >= 0.6f) // still enough for text on a blurred photo
    }
}
