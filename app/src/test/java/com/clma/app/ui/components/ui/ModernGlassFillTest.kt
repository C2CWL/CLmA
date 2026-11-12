package com.clma.app.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernGlassFillTest {

    @Test
    fun `glass fill scales with the slider and has no floor`() {
        assertEquals(0f, modernGlassFillAlpha(0f), 0f)
        assertEquals(0.34f, modernGlassFillAlpha(0.4f), 1e-6f)
        assertEquals(0.85f, modernGlassFillAlpha(1f), 0f)
        assertEquals(0f, modernGlassFillAlpha(-1f), 0f)
        assertEquals(0.85f, modernGlassFillAlpha(3f), 0f)
    }

    @Test
    fun `glass fill is well below the flat-card floor at the default slider`() {
        // modernPanelAlpha keeps a 55% floor so a flat card stays readable. Using that
        // (times 0.9) on glass made a 66% slab at the default 40% opacity. The glass
        // fill must stay at the same 34% as the clean panels.
        val slider = 0.4f
        val oldCover = modernPanelAlpha(slider) * 0.9f
        assertEquals(0.657f, oldCover, 1e-4f)
        assertEquals(cleanGlassFillAlpha(slider), modernGlassFillAlpha(slider), 0f)
        assertTrue(modernGlassFillAlpha(slider) < oldCover)
        // At 0 the flat card is still half-opaque; glass must go fully clear.
        assertTrue(modernPanelAlpha(0f) >= 0.55f)
        assertEquals(0f, modernGlassFillAlpha(0f), 0f)
    }
}
