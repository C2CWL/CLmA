package com.clma.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AmoledColorSchemeTest {
    @Test
    fun `disabled conversion preserves light and ordinary dark schemes`() {
        listOf(lightColorScheme(), darkColorScheme()).forEach { scheme ->
            assertSame(scheme, scheme.withAmoledDarkMode(enabled = false))
        }
    }

    @Test
    fun `page and low container backgrounds are pure black`() {
        val scheme = darkColorScheme().withAmoledDarkMode(enabled = true)
        listOf(
            scheme.background,
            scheme.surface,
            scheme.surfaceDim,
            scheme.surfaceContainerLowest,
            scheme.surfaceContainerLow,
        ).forEach { assertEquals(Color.Black, it) }
    }

    @Test
    fun `cards and dialogs retain neutral dark elevation levels`() {
        val scheme = darkColorScheme().withAmoledDarkMode(enabled = true)
        assertEquals(Color(0xFF101010), scheme.surfaceContainer)
        assertEquals(Color(0xFF181818), scheme.surfaceContainerHigh)
        assertEquals(Color(0xFF222222), scheme.surfaceContainerHighest)
        assertEquals(Color(0xFF222222), scheme.surfaceBright)
        assertEquals(Color(0xFF222222), scheme.surfaceVariant)
    }

    @Test
    fun `tonal elevation does not turn black surfaces grey or accent tinted`() {
        val scheme = darkColorScheme().withAmoledDarkMode(enabled = true)
        listOf(0.dp, 1.dp, 2.dp, 6.dp, 24.dp).forEach { elevation ->
            assertEquals(Color.Black, scheme.surfaceColorAtElevation(elevation))
        }
    }

    @Test
    fun `conversion preserves accents text and contrast roles`() {
        val original = darkColorScheme(
            primary = Color.Red,
            secondary = Color.Green,
            tertiary = Color.Blue,
        )
        val converted = original.withAmoledDarkMode(enabled = true)
        assertEquals(original.primary, converted.primary)
        assertEquals(original.onPrimary, converted.onPrimary)
        assertEquals(original.primaryContainer, converted.primaryContainer)
        assertEquals(original.secondary, converted.secondary)
        assertEquals(original.tertiary, converted.tertiary)
        assertEquals(original.onBackground, converted.onBackground)
        assertEquals(original.onSurface, converted.onSurface)
        assertEquals(original.onSurfaceVariant, converted.onSurfaceVariant)
        assertEquals(original.outline, converted.outline)
        assertEquals(original.error, converted.error)
        assertEquals(original.inverseSurface, converted.inverseSurface)
        assertEquals(original.inverseOnSurface, converted.inverseOnSurface)
    }
}
