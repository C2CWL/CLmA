package me.rerere.rikkahub.ui.theme

import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontScaleTest {

    private val eps = 1e-6f

    @Test
    fun `new settings default to off and unchanged scale`() {
        val d = DisplaySetting()
        assertEquals(ChatFontFamily.DEFAULT, d.uiFontFamily)
        assertEquals("", d.uiCustomFontPath)
        assertEquals(1.0f, d.uiFontScale, eps)
        assertEquals(1.0f, d.fontSizeRatio, eps)
    }

    @Test
    fun `snap rounds to whole percent and clamps`() {
        assertEquals(0.87f, FontScale.snap(0.8712f, FontScale.CHAT_RANGE), eps)
        assertEquals(1.23f, FontScale.snap(1.2349f, FontScale.UI_RANGE), eps)
        assertEquals(0.5f, FontScale.snap(0.1f, FontScale.UI_RANGE), eps)
        assertEquals(2.0f, FontScale.snap(9f, FontScale.UI_RANGE), eps)
        assertEquals(1.73f, FontScale.snap(1.7312f, FontScale.UI_RANGE), eps)
        assertEquals(1.0f, FontScale.snap(Float.NaN, FontScale.UI_RANGE), eps)
    }

    @Test
    fun `percent label rounds old stepped values instead of truncating`() {
        assertEquals(88, FontScale.percentLabel(0.875f))
        assertEquals(100, FontScale.percentLabel(1.0f))
        assertEquals(63, FontScale.percentLabel(0.625f))
    }

    @Test
    fun `parse percent accepts integers with optional percent sign and clamps`() {
        assertEquals(0.87f, FontScale.parsePercent("87", FontScale.CHAT_RANGE)!!, eps)
        assertEquals(1.2f, FontScale.parsePercent(" 120 ", FontScale.UI_RANGE)!!, eps)
        assertEquals(0.95f, FontScale.parsePercent("95%", FontScale.UI_RANGE)!!, eps)
        assertEquals(2.0f, FontScale.parsePercent("500", FontScale.CHAT_RANGE)!!, eps)
        assertEquals(0.5f, FontScale.parsePercent("10", FontScale.UI_RANGE)!!, eps)
        assertEquals(1.8f, FontScale.parsePercent("180", FontScale.UI_RANGE)!!, eps)
        assertEquals(2.0f, FontScale.parsePercent("999", FontScale.UI_RANGE)!!, eps)
    }

    @Test
    fun `parse percent ignores invalid input`() {
        assertNull(FontScale.parsePercent("", FontScale.UI_RANGE))
        assertNull(FontScale.parsePercent("abc", FontScale.UI_RANGE))
        assertNull(FontScale.parsePercent("1.5", FontScale.UI_RANGE))
        assertNull(FontScale.parsePercent("%", FontScale.UI_RANGE))
    }

    @Test
    fun `ui scale multiplies the system font scale and clamps corrupt values`() {
        assertEquals(1.3f, FontScale.effectiveFontScale(1.3f, 1.0f), eps)
        assertEquals(1.56f, FontScale.effectiveFontScale(1.3f, 1.2f), eps)
        assertEquals(2.0f, FontScale.effectiveFontScale(1.0f, 7f), eps)
        assertEquals(0.5f, FontScale.effectiveFontScale(1.0f, 0.2f), eps)
        assertEquals(1.0f, FontScale.effectiveFontScale(1.0f, Float.NaN), eps)
    }

    @Test
    fun `ui font applies only when a non-default font is chosen`() {
        assertFalse(FontScale.shouldApplyUiFont(ChatFontFamily.DEFAULT, ""))
        assertFalse(FontScale.shouldApplyUiFont(ChatFontFamily.DEFAULT, "fonts/ui_font.1.ttf"))
        assertFalse(FontScale.shouldApplyUiFont(ChatFontFamily.CUSTOM, ""))
        assertTrue(FontScale.shouldApplyUiFont(ChatFontFamily.CUSTOM, "fonts/ui_font.1.ttf"))
        assertTrue(FontScale.shouldApplyUiFont(ChatFontFamily.SERIF, ""))
        assertTrue(FontScale.shouldApplyUiFont(ChatFontFamily.MONOSPACE, ""))
    }

    @Test
    fun `effective font scale is capped at 200 percent`() {
        val eps = 0.0001f
        // The reviewer's case: accessibility 2.0 × UI 2.0 stays at 2.0, not 4.0.
        assertEquals(2.0f, FontScale.effectiveFontScale(2.0f, 2.0f), eps)
        assertEquals(2.0f, FontScale.effectiveFontScale(1.5f, 1.6f), eps)
        assertEquals(1.92f, FontScale.effectiveFontScale(1.6f, 1.2f), eps)
        // Shrinking is never capped.
        assertEquals(1.0f, FontScale.effectiveFontScale(2.0f, 0.5f), eps)
    }

    @Test
    fun `cap never shrinks a larger system scale`() {
        val eps = 0.0001f
        assertEquals(2.2f, FontScale.effectiveFontScale(2.2f, 1.0f), eps)
        assertEquals(2.2f, FontScale.effectiveFontScale(2.2f, 1.5f), eps)
        assertEquals(1.1f, FontScale.effectiveFontScale(2.2f, 0.5f), eps)
    }

    @Test
    fun `default UI scale is exactly the system scale`() {
        for (system in listOf(0.85f, 1.0f, 1.3f, 2.0f)) {
            assertEquals(system, FontScale.effectiveFontScale(system, FontScale.DEFAULT), 0f)
        }
    }

    @Test
    fun `preview ratio is relative to the applied scale`() {
        val eps = 0.0001f
        assertEquals(1f, FontScale.previewRatio(1.0f, currentFontScale = 1.2f, candidateUiScale = 1.2f), eps)
        assertEquals(1.5f, FontScale.previewRatio(1.0f, currentFontScale = 1.0f, candidateUiScale = 1.5f), eps)
        // Capped: 1.5 × 2.0 → 2.0, shown relative to the current 1.5.
        assertEquals(2.0f / 1.5f, FontScale.previewRatio(1.5f, currentFontScale = 1.5f, candidateUiScale = 2.0f), eps)
        assertEquals(1f, FontScale.previewRatio(1.0f, currentFontScale = 0f, candidateUiScale = 2.0f), eps)
    }
}
