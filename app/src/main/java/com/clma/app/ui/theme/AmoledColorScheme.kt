package com.clma.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

internal fun ColorScheme.withAmoledDarkMode(enabled: Boolean): ColorScheme {
    if (!enabled) return this

    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        // Settings scaffolds and top bars use this role as their page background.
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color(0xFF101010),
        surfaceContainerHigh = Color(0xFF181818),
        surfaceContainerHighest = Color(0xFF222222),
        surfaceBright = Color(0xFF222222),
        surfaceVariant = Color(0xFF222222),
        // Prevent tonal elevation from tinting a black surface with the accent colour.
        surfaceTint = Color.Black,
    )
}
