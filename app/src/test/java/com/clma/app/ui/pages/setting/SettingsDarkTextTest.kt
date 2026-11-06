package com.clma.app.ui.pages.setting

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDarkTextTest {
    @Test
    fun `dark settings text is an opaque grey blend, not translucent white`() {
        val nearWhite = Color(0xFFF0DEDF)
        val blended = blendOver(nearWhite, SettingsPageBackgroundDark, 0.68f)

        assertEquals(Color.Black, SettingsPageBackgroundDark)
        assertEquals(1f, blended.alpha)
        assertTrue(blended.red < 0.72f)
        assertTrue(blended.green < 0.72f)
        assertTrue(blended.blue < 0.72f)
    }
}
