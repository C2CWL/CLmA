package com.clma.app.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashStyleTest {

    @Test
    fun `visual style reads the theme id and treats dynamic colour as other`() {
        assertEquals(VisualStyle.CLAUDE, VisualStyle.current("claude", dynamicColor = false))
        assertEquals(VisualStyle.GEMINI, VisualStyle.current("gemini", dynamicColor = false))
        assertEquals(VisualStyle.OTHER, VisualStyle.current("sakura", dynamicColor = false))
        assertEquals(VisualStyle.OTHER, VisualStyle.current("gemini", dynamicColor = true))
        assertEquals(listOf(VisualStyle.CLAUDE, VisualStyle.GEMINI), VisualStyle.selectable)
    }

    @Test
    fun `splash follows gemini and colour mode, everything else falls back to claude`() {
        assertEquals(SplashStyle.GEMINI, SplashStyle.resolve("gemini", false, ColorMode.SYSTEM))
        assertEquals(SplashStyle.GEMINI_LIGHT, SplashStyle.resolve("gemini", false, ColorMode.LIGHT))
        assertEquals(SplashStyle.GEMINI_DARK, SplashStyle.resolve("gemini", false, ColorMode.DARK))
        assertEquals(SplashStyle.CLAUDE, SplashStyle.resolve("claude", false, ColorMode.SYSTEM))
        assertEquals(SplashStyle.CLAUDE_LIGHT, SplashStyle.resolve("claude", false, ColorMode.LIGHT))
        assertEquals(SplashStyle.CLAUDE_DARK, SplashStyle.resolve("ocean", false, ColorMode.DARK))
        assertEquals(SplashStyle.CLAUDE, SplashStyle.resolve("gemini", true, ColorMode.SYSTEM))
        assertEquals(SplashStyle.CLAUDE, SplashStyle.resolve("some-custom-uuid", false, ColorMode.SYSTEM))
    }

    /** The system persists splash themes by name: every SplashStyle must exist, and the manifest default stays Claude. */
    @Test
    fun `splash style names exist in resources and the manifest uses the claude default`() {
        val styles = File("src/main/res/values/themes_splash.xml").readText()
        SplashStyle.entries.forEach { style ->
            assertTrue("missing style ${style.styleName}", styles.contains("<style name=\"${style.styleName}\""))
        }
        val night = File("src/main/res/values-night/themes_splash.xml").readText()
        assertTrue(night.contains("<style name=\"${SplashStyle.CLAUDE.styleName}\""))
        assertTrue(night.contains("<style name=\"${SplashStyle.GEMINI.styleName}\""))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:theme=\"@style/${SplashStyle.CLAUDE.styleName}\""))
    }
}
