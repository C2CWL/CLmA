package com.clma.app.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarfieldMeteorProgressTest {

    @Test
    fun `hidden for most of the period`() {
        assertNull(meteorProgressAtElapsed(0f, periodMs = 7_500f, visibleMs = 1_200f))
        assertNull(meteorProgressAtElapsed(6_000f, periodMs = 7_500f, visibleMs = 1_200f))
    }

    @Test
    fun `starts near zero when the streak becomes visible`() {
        val p = meteorProgressAtElapsed(6_300f, periodMs = 7_500f, visibleMs = 1_200f)
        requireNotNull(p)
        assertTrue(p in 0f..0.05f)
    }

    @Test
    fun `reaches one at the end of the visible window`() {
        val p = meteorProgressAtElapsed(7_499f, periodMs = 7_500f, visibleMs = 1_200f)
        requireNotNull(p)
        assertEquals(1f, p, 0.02f)
    }

    @Test
    fun `wraps cleanly into the next period`() {
        assertNull(meteorProgressAtElapsed(7_500f, periodMs = 7_500f, visibleMs = 1_200f))
    }
}
