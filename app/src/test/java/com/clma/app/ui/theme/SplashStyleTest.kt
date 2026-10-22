package com.clma.app.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashStyleTest {

    @Test
    fun `splash follows the in-app colour mode`() {
        assertEquals(SplashStyle.CLAUDE, SplashStyle.resolve(ColorMode.SYSTEM))
        assertEquals(SplashStyle.CLAUDE_LIGHT, SplashStyle.resolve(ColorMode.LIGHT))
        assertEquals(SplashStyle.CLAUDE_DARK, SplashStyle.resolve(ColorMode.DARK))
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
