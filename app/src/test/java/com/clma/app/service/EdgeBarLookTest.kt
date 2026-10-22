package com.clma.app.service

import com.clma.app.data.datastore.EdgeBarColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeBarLookTest {

    @Test
    fun `side comes from the saved x of the old ball or the bar, default right`() {
        val w = 1080
        assertTrue(EdgeBarLook.onRightFromSavedX(null, w))
        // Old ball: resting left (margin), tucked left (negative), resting / tucked right.
        assertFalse(EdgeBarLook.onRightFromSavedX(21, w))
        assertFalse(EdgeBarLook.onRightFromSavedX(-70, w))
        assertTrue(EdgeBarLook.onRightFromSavedX(w - 168, w))
        assertTrue(EdgeBarLook.onRightFromSavedX(w - 79, w))
        // What the bar itself saves round-trips.
        assertTrue(EdgeBarLook.onRightFromSavedX(EdgeBarLook.savedXFor(true, w, 84), w))
        assertFalse(EdgeBarLook.onRightFromSavedX(EdgeBarLook.savedXFor(false, w, 84), w))
    }

    @Test
    fun `drag keeps the bar inside the usable band`() {
        // 2400px screen, 100px status bar, 60px nav bar, 20px margin, 200px window.
        fun clamp(top: Int) = EdgeBarLook.clampTop(top, 200, 2400, 100, 60, 20)
        assertEquals(120, clamp(-500))
        assertEquals(120, clamp(0))
        assertEquals(1000, clamp(1000))
        assertEquals(2400 - 60 - 20 - 200, clamp(5000))
        // A band too small for the window sticks to its top instead of crashing coerceIn.
        assertEquals(120, EdgeBarLook.clampTop(500, 2000, 1000, 100, 60, 20))
    }

    @Test
    fun `only a drop well across the screen switches sides`() {
        val w = 1000
        assertTrue(EdgeBarLook.onRightAfterDrag(950f, w, wasOnRight = true))
        assertTrue(EdgeBarLook.onRightAfterDrag(500f, w, wasOnRight = true))
        assertFalse(EdgeBarLook.onRightAfterDrag(200f, w, wasOnRight = true))
        assertFalse(EdgeBarLook.onRightAfterDrag(500f, w, wasOnRight = false))
        assertTrue(EdgeBarLook.onRightAfterDrag(800f, w, wasOnRight = false))
    }

    @Test
    fun `default position centres the bar at 62 percent`() {
        assertEquals(1488 - 100, EdgeBarLook.defaultTop(200, 2400))
    }

    @Test
    fun `colour swatches map ids, fall back to follow-theme and pack ARGB`() {
        assertEquals(EdgeBarColor.FOLLOW_THEME, EdgeBarColor.DEFAULT)
        assertEquals(1, EdgeBarColor.DEFAULT.id)
        assertEquals(EdgeBarColor.FOLLOW_THEME, EdgeBarColor.fromId(null))
        assertEquals(EdgeBarColor.FOLLOW_THEME, EdgeBarColor.fromId(0))
        assertEquals(EdgeBarColor.FOLLOW_THEME, EdgeBarColor.fromId(99))
        EdgeBarColor.entries.forEach { assertEquals(it, EdgeBarColor.fromId(it.id)) }
        assertEquals((1..6).toList(), EdgeBarColor.entries.map { it.id })

        assertEquals(0xCC2F80ED.toInt(), EdgeBarColor.BLUE.argb(dark = false)) // 0.8 * 255 = 204 = 0xCC
        assertEquals(0xFFC8C8CD.toInt(), EdgeBarColor.WHITE.outlineArgb(dark = false))
        assertNull(EdgeBarColor.BLACK.outlineArgb(dark = false))
    }

    @Test
    fun `follow-theme swatch is cream in light mode and warm dark grey in dark mode`() {
        val c = EdgeBarColor.FOLLOW_THEME
        assertTrue(c.followsTheme)
        assertEquals(0xEBF6F1E7.toInt(), c.argb(dark = false))
        assertEquals(0xFFCDC3B5.toInt(), c.outlineArgb(dark = false))
        assertEquals(0xC73E3934.toInt(), c.argb(dark = true))
        assertEquals(0x1AFFF4E4, c.outlineArgb(dark = true))
    }

    @Test
    fun `fixed swatches ignore dark mode and keep their old values`() {
        val expected = mapOf(
            EdgeBarColor.LIGHT_GRAY to 0xD9AAACB2.toInt(), // 0.85 -> 0xD9
            EdgeBarColor.BLACK to 0xD9141416.toInt(),
            EdgeBarColor.WHITE to 0xF2FAFAFC.toInt(), // 0.95 -> 0xF2
            EdgeBarColor.BLUE to 0xCC2F80ED.toInt(),
            EdgeBarColor.TEAL to 0xCC14A38B.toInt(),
        )
        expected.forEach { (color, argb) ->
            assertFalse(color.followsTheme)
            assertEquals(argb, color.argb(dark = false))
            assertEquals(argb, color.argb(dark = true))
            assertEquals(color.outlineArgb(dark = false), color.outlineArgb(dark = true))
        }
    }

    @Test
    fun `dark mode follows the app setting first, then the system night mode`() {
        assertFalse(EdgeBarLook.isDarkMode("LIGHT", systemNight = true))
        assertTrue(EdgeBarLook.isDarkMode("DARK", systemNight = false))
        assertTrue(EdgeBarLook.isDarkMode("SYSTEM", systemNight = true))
        assertFalse(EdgeBarLook.isDarkMode("SYSTEM", systemNight = false))
        assertTrue(EdgeBarLook.isDarkMode(null, systemNight = true))
        assertFalse(EdgeBarLook.isDarkMode("garbage", systemNight = false))
    }

    @Test
    fun `light fills are told apart from dark ones`() {
        assertTrue(EdgeBarLook.isLightFill(EdgeBarColor.FOLLOW_THEME.argb(dark = false)))
        assertFalse(EdgeBarLook.isLightFill(EdgeBarColor.FOLLOW_THEME.argb(dark = true)))
        assertTrue(EdgeBarLook.isLightFill(EdgeBarColor.LIGHT_GRAY.argb(dark = false)))
        assertTrue(EdgeBarLook.isLightFill(EdgeBarColor.WHITE.argb(dark = false)))
        assertFalse(EdgeBarLook.isLightFill(EdgeBarColor.BLACK.argb(dark = false)))
        assertFalse(EdgeBarLook.isLightFill(EdgeBarColor.BLUE.argb(dark = false)))
        assertFalse(EdgeBarLook.isLightFill(EdgeBarColor.TEAL.argb(dark = false)))
    }

    @Test
    fun `saved edge keeps old sides and adds top`() {
        val w = 1080
        assertEquals(EdgeBarLook.Edge.RIGHT, EdgeBarLook.edgeFromSaved(null, null, w))
        assertEquals(EdgeBarLook.Edge.LEFT, EdgeBarLook.edgeFromSaved(null, 0, w))
        assertEquals(EdgeBarLook.Edge.RIGHT, EdgeBarLook.edgeFromSaved("right", w - 84, w))
        assertEquals(EdgeBarLook.Edge.TOP, EdgeBarLook.edgeFromSaved(EdgeBarLook.SAVED_EDGE_TOP, 0, w))
    }

    @Test
    fun `a drop near the top snaps to the top, otherwise to a side`() {
        val w = 1080
        val h = 2400
        fun drop(x: Float, y: Float, was: EdgeBarLook.Edge) = EdgeBarLook.edgeAfterDrag(x, y, w, h, was)
        assertEquals(EdgeBarLook.Edge.TOP, drop(540f, 100f, EdgeBarLook.Edge.RIGHT))
        assertEquals(EdgeBarLook.Edge.TOP, drop(10f, 300f, EdgeBarLook.Edge.LEFT))
        // Side to side keeps the "well across" rule.
        assertEquals(EdgeBarLook.Edge.RIGHT, drop(500f, 1200f, EdgeBarLook.Edge.RIGHT))
        assertEquals(EdgeBarLook.Edge.LEFT, drop(300f, 1200f, EdgeBarLook.Edge.RIGHT))
        assertEquals(EdgeBarLook.Edge.LEFT, drop(600f, 1200f, EdgeBarLook.Edge.LEFT))
        assertEquals(EdgeBarLook.Edge.RIGHT, drop(800f, 1200f, EdgeBarLook.Edge.LEFT))
        // Coming down from the top picks the nearer half.
        assertEquals(EdgeBarLook.Edge.LEFT, drop(400f, 1200f, EdgeBarLook.Edge.TOP))
        assertEquals(EdgeBarLook.Edge.RIGHT, drop(600f, 1200f, EdgeBarLook.Edge.TOP))
    }

    @Test
    fun `top bar stays on screen`() {
        assertEquals(500, EdgeBarLook.topBarLeft(540, 80, 1080))
        assertEquals(0, EdgeBarLook.topBarLeft(10, 80, 1080))
        assertEquals(1000, EdgeBarLook.topBarLeft(1075, 80, 1080))
    }
}
