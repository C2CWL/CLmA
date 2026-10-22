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
    fun `colour swatches map ids, fall back to dark grey and pack ARGB`() {
        assertEquals(EdgeBarColor.DARK_GRAY, EdgeBarColor.DEFAULT)
        assertEquals(EdgeBarColor.DARK_GRAY, EdgeBarColor.fromId(null))
        assertEquals(EdgeBarColor.DARK_GRAY, EdgeBarColor.fromId(0))
        assertEquals(EdgeBarColor.DARK_GRAY, EdgeBarColor.fromId(99))
        EdgeBarColor.entries.forEach { assertEquals(it, EdgeBarColor.fromId(it.id)) }
        assertEquals((1..6).toList(), EdgeBarColor.entries.map { it.id })

        assertEquals(0x8C28282E.toInt(), EdgeBarColor.DARK_GRAY.argb) // 0.55 * 255 = 140 = 0x8C
        assertEquals(0xCC2F80ED.toInt(), EdgeBarColor.BLUE.argb) // 0.8 * 255 = 204 = 0xCC
        assertEquals(0xFFC8C8CD.toInt(), EdgeBarColor.WHITE.outlineArgb)
        assertNull(EdgeBarColor.BLACK.outlineArgb)
    }
}
