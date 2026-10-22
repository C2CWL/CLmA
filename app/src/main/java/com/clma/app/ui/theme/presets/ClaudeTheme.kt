package com.clma.app.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.clma.app.R
import com.clma.app.ui.theme.PresetTheme

/**
 * Claude 暖调：羊皮纸米白底（#FAF8F5）、暖近黑深色底（#1F1E1D），珊瑚/陶土橙作强调色，暖灰文字。
 * 取自 Claude 界面观感的近似配色，并非官方色板。浅色主色用 #BC5A38（白字对比度 ≥ 4.5:1），
 * 更亮的品牌珊瑚 #D97757 留给发送键等图形元素（见启动页资源）。
 */
val ClaudeThemePreset by lazy {
    PresetTheme(
        id = "claude",
        name = {
            Text(stringResource(id = R.string.theme_name_claude))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

private val primaryLight = Color(0xFFBC5A38)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFF5E2D6)
private val onPrimaryContainerLight = Color(0xFF7A3420)
private val secondaryLight = Color(0xFF7A7066)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFECE7DF)
private val onSecondaryContainerLight = Color(0xFF2E2A26)
private val tertiaryLight = Color(0xFF9A6C3E)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFEFE0C8)
private val onTertiaryContainerLight = Color(0xFF4F3A1E)
private val errorLight = Color(0xFFB3261E)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFF9DEDC)
private val onErrorContainerLight = Color(0xFF601410)
private val backgroundLight = Color(0xFFFAF8F5)
private val onBackgroundLight = Color(0xFF2E2A26)
private val surfaceLight = Color(0xFFFAF8F5)
private val onSurfaceLight = Color(0xFF2E2A26)
private val surfaceVariantLight = Color(0xFFECE7DF)
private val onSurfaceVariantLight = Color(0xFF6F665C)
private val outlineLight = Color(0xFFA89E92)
private val outlineVariantLight = Color(0xFFD6CEC2)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF34302C)
private val inverseOnSurfaceLight = Color(0xFFF5F1EB)
private val inversePrimaryLight = Color(0xFFF0A88C)
private val surfaceDimLight = Color(0xFFE6E1D8)
private val surfaceBrightLight = Color(0xFFFFFFFF)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF6F3EE)
private val surfaceContainerLight = Color(0xFFF2EFE9)
private val surfaceContainerHighLight = Color(0xFFECE7DF)
private val surfaceContainerHighestLight = Color(0xFFE6E1D8)

private val primaryDark = Color(0xFFE08A6D)
private val onPrimaryDark = Color(0xFF3A170A)
private val primaryContainerDark = Color(0xFF7A3A24)
private val onPrimaryContainerDark = Color(0xFFFFDBCD)
private val secondaryDark = Color(0xFFC9C0B4)
private val onSecondaryDark = Color(0xFF36312B)
private val secondaryContainerDark = Color(0xFF3A3631)
private val onSecondaryContainerDark = Color(0xFFECE7DF)
private val tertiaryDark = Color(0xFFE0BE8C)
private val onTertiaryDark = Color(0xFF422C0C)
private val tertiaryContainerDark = Color(0xFF5C4525)
private val onTertiaryContainerDark = Color(0xFFFFDDB0)
private val errorDark = Color(0xFFF2B8B5)
private val onErrorDark = Color(0xFF601410)
private val errorContainerDark = Color(0xFF8C1D18)
private val onErrorContainerDark = Color(0xFFF9DEDC)
private val backgroundDark = Color(0xFF1F1E1D)
private val onBackgroundDark = Color(0xFFECE8E1)
private val surfaceDark = Color(0xFF1F1E1D)
private val onSurfaceDark = Color(0xFFECE8E1)
private val surfaceVariantDark = Color(0xFF3A3631)
private val onSurfaceVariantDark = Color(0xFFB8AFA4)
private val outlineDark = Color(0xFF8A8279)
private val outlineVariantDark = Color(0xFF45413B)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFECE8E1)
private val inverseOnSurfaceDark = Color(0xFF34302C)
private val inversePrimaryDark = Color(0xFFBC5A38)
private val surfaceDimDark = Color(0xFF1F1E1D)
private val surfaceBrightDark = Color(0xFF44423E)
private val surfaceContainerLowestDark = Color(0xFF171614)
private val surfaceContainerLowDark = Color(0xFF242321)
private val surfaceContainerDark = Color(0xFF2A2927)
private val surfaceContainerHighDark = Color(0xFF33312E)
private val surfaceContainerHighestDark = Color(0xFF3D3A36)

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
