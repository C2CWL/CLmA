package com.clma.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

internal fun ColorScheme.toMiuixColors(dark: Boolean): Colors {
    val m = if (dark) miuixDarkColorScheme() else miuixLightColorScheme()
    return Colors(
        primary = primary,
        onPrimary = onPrimary,
        primaryVariant = primaryContainer,
        onPrimaryVariant = onPrimaryContainer,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        disabledPrimary = m.disabledPrimary,
        disabledOnPrimary = m.disabledOnPrimary,
        disabledPrimaryButton = m.disabledPrimaryButton,
        disabledOnPrimaryButton = m.disabledOnPrimaryButton,
        disabledPrimarySlider = m.disabledPrimarySlider,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryVariant = m.secondaryVariant,
        onSecondaryVariant = m.onSecondaryVariant,
        disabledSecondary = m.disabledSecondary,
        disabledOnSecondary = m.disabledOnSecondary,
        disabledSecondaryVariant = m.disabledSecondaryVariant,
        disabledOnSecondaryVariant = m.disabledOnSecondaryVariant,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        secondaryContainerVariant = m.secondaryContainerVariant,
        onSecondaryContainerVariant = m.onSecondaryContainerVariant,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        tertiaryContainerVariant = m.tertiaryContainerVariant,
        background = background,
        onBackground = onBackground,
        onBackgroundVariant = onSurfaceVariant,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceSecondary = onSurfaceVariant.copy(alpha = 0.8f),
        onSurfaceVariantSummary = onSurfaceVariant.copy(alpha = 0.6f),
        onSurfaceVariantActions = onSurfaceVariant.copy(alpha = 0.4f),
        disabledOnSurface = onSurface.copy(alpha = 0.38f),
        surfaceContainer = surfaceContainer,
        onSurfaceContainer = onSurface,
        onSurfaceContainerVariant = onSurfaceVariant,
        surfaceContainerHigh = surfaceContainerHigh,
        onSurfaceContainerHigh = onSurfaceVariant,
        surfaceContainerHighest = surfaceContainerHighest,
        onSurfaceContainerHighest = onSurface,
        outline = outline,
        dividerLine = outlineVariant,
        windowDimming = m.windowDimming,
        sliderKeyPoint = m.sliderKeyPoint,
        sliderKeyPointForeground = primary,
        sliderBackground = m.sliderBackground,
    )
}

internal fun Typography.toMiuixTextStyles(): TextStyles = TextStyles(
    main = bodyLarge,
    paragraph = bodyLarge,
    body1 = bodyLarge,
    body2 = bodyMedium,
    button = labelLarge,
    footnote1 = bodySmall,
    footnote2 = labelSmall,
    headline1 = titleLarge,
    headline2 = titleMedium,
    subtitle = titleSmall,
    title1 = headlineLarge,
    title2 = headlineMedium,
    title3 = titleLarge,
    title4 = titleMedium,
)
