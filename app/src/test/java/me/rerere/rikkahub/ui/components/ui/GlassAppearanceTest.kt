package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassAppearanceTest {

    @Test
    fun `light theme uses haze's light glass response`() {
        assertEquals(
            GlassAppearance(ambientResponse = 0.15f, contrast = 0f, whitePoint = 0.55f, chromaMultiplier = 1.5f),
            glassAppearance(darkTheme = false),
        )
    }

    @Test
    fun `dark theme uses haze's dark glass response`() {
        assertEquals(
            GlassAppearance(ambientResponse = 0.08f, contrast = 0.08f, whitePoint = -0.22f, chromaMultiplier = 1.1f),
            glassAppearance(darkTheme = true),
        )
    }

    @Test
    fun `light glass is brighter than dark glass`() {
        assertTrue(glassAppearance(false).whitePoint > glassAppearance(true).whitePoint)
    }
}
