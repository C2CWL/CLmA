package com.clma.app.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.clma.app.R
import com.clma.app.ui.theme.PresetTheme

/**
 * Gemini 冷调：冷白底（#F8FAFD）、近黑深色底（#131314），Google 蓝作强调色（浅色 #1A73E8、深色 #A8C7FA），
 * 冷灰文字。取自 Gemini 界面观感的近似配色，并非官方色板。
 */
val GeminiThemePreset by lazy {
    PresetTheme(
        id = "gemini",
        name = {
            Text(stringResource(id = R.string.theme_name_gemini))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

private val primaryLight = Color(0xFF1A73E8)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFD3E3FD)
private val onPrimaryContainerLight = Color(0xFF041E49)
private val secondaryLight = Color(0xFF5F6368)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFE8EEF6)
private val onSecondaryContainerLight = Color(0xFF1F1F1F)
private val tertiaryLight = Color(0xFF146C2E)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFC4EED0)
private val onTertiaryContainerLight = Color(0xFF072711)
private val errorLight = Color(0xFFB3261E)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFF9DEDC)
private val onErrorContainerLight = Color(0xFF601410)
private val backgroundLight = Color(0xFFF8FAFD)
private val onBackgroundLight = Color(0xFF1F1F1F)
private val surfaceLight = Color(0xFFF8FAFD)
private val onSurfaceLight = Color(0xFF1F1F1F)
private val surfaceVariantLight = Color(0xFFE8EEF6)
private val onSurfaceVariantLight = Color(0xFF5F6368)
private val outlineLight = Color(0xFF9AA0A6)
private val outlineVariantLight = Color(0xFFDADCE0)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF303030)
private val inverseOnSurfaceLight = Color(0xFFF2F2F2)
private val inversePrimaryLight = Color(0xFFA8C7FA)
private val surfaceDimLight = Color(0xFFDDE3EA)
private val surfaceBrightLight = Color(0xFFFFFFFF)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF4F7FB)
private val surfaceContainerLight = Color(0xFFF0F4F9)
private val surfaceContainerHighLight = Color(0xFFE8EEF6)
private val surfaceContainerHighestLight = Color(0xFFE1E7EF)

private val primaryDark = Color(0xFFA8C7FA)
private val onPrimaryDark = Color(0xFF062E6F)
private val primaryContainerDark = Color(0xFF0842A0)
private val onPrimaryContainerDark = Color(0xFFD3E3FD)
private val secondaryDark = Color(0xFFC4C7C5)
private val onSecondaryDark = Color(0xFF2D312F)
private val secondaryContainerDark = Color(0xFF282A2C)
private val onSecondaryContainerDark = Color(0xFFE3E3E3)
private val tertiaryDark = Color(0xFF6DD58C)
private val onTertiaryDark = Color(0xFF0A3818)
private val tertiaryContainerDark = Color(0xFF0F5223)
private val onTertiaryContainerDark = Color(0xFFC4EED0)
private val errorDark = Color(0xFFF2B8B5)
private val onErrorDark = Color(0xFF601410)
private val errorContainerDark = Color(0xFF8C1D18)
private val onErrorContainerDark = Color(0xFFF9DEDC)
private val backgroundDark = Color(0xFF131314)
private val onBackgroundDark = Color(0xFFE3E3E3)
private val surfaceDark = Color(0xFF131314)
private val onSurfaceDark = Color(0xFFE3E3E3)
private val surfaceVariantDark = Color(0xFF282A2C)
private val onSurfaceVariantDark = Color(0xFFC4C7C5)
private val outlineDark = Color(0xFF8E918F)
private val outlineVariantDark = Color(0xFF3C4043)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFE3E3E3)
private val inverseOnSurfaceDark = Color(0xFF303030)
private val inversePrimaryDark = Color(0xFF1A73E8)
private val surfaceDimDark = Color(0xFF131314)
private val surfaceBrightDark = Color(0xFF37393B)
private val surfaceContainerLowestDark = Color(0xFF0E0E0F)
private val surfaceContainerLowDark = Color(0xFF1B1B1C)
private val surfaceContainerDark = Color(0xFF1E1F20)
private val surfaceContainerHighDark = Color(0xFF282A2C)
private val surfaceContainerHighestDark = Color(0xFF333537)

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)
