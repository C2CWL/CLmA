package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import me.rerere.rikkahub.data.datastore.DisplaySetting

/**
 * The app typography: [Typography] unchanged, or every baseline and emphasized style with the
 * UI font ([DisplaySetting.uiFontFamily]) when one is chosen. Independent of the chat font,
 * which chat content keeps applying on top through [ChatFontProvider].
 */
@Composable
fun rememberAppTypography(displaySetting: DisplaySetting): Typography {
    val context = LocalContext.current
    val apply = FontScale.shouldApplyUiFont(displaySetting.uiFontFamily, displaySetting.uiCustomFontPath)
    return remember(apply, displaySetting.uiFontFamily, displaySetting.uiCustomFontPath) {
        if (apply) Typography.withFontFamily(displaySetting.resolveUiFontFamily(context)) else Typography
    }
}

/**
 * The system font scale before [DisplaySetting.uiFontScale] was applied. Provided by the
 * outermost [RikkahubTheme] so a nested theme (e.g. the terminal's forced-dark one) does not
 * multiply the scale a second time.
 */
internal val LocalBaseFontScale = staticCompositionLocalOf<Float?> { null }

/** [LocalDensity] with `fontScale = systemFontScale × uiFontScale`; `density` (dp) is untouched. */
@Composable
fun rememberAppDensity(displaySetting: DisplaySetting): Pair<Density, Float> {
    val density = LocalDensity.current
    val base = LocalBaseFontScale.current ?: density.fontScale
    val fontScale = FontScale.effectiveFontScale(base, displaySetting.uiFontScale)
    val scaled = remember(density, fontScale) {
        if (fontScale == density.fontScale) density else Density(density.density, fontScale)
    }
    return scaled to base
}

private fun TextStyle.with(fontFamily: FontFamily) = copy(fontFamily = fontFamily)

fun Typography.withFontFamily(fontFamily: FontFamily): Typography = copy(
    displayLarge = displayLarge.with(fontFamily),
    displayMedium = displayMedium.with(fontFamily),
    displaySmall = displaySmall.with(fontFamily),
    headlineLarge = headlineLarge.with(fontFamily),
    headlineMedium = headlineMedium.with(fontFamily),
    headlineSmall = headlineSmall.with(fontFamily),
    titleLarge = titleLarge.with(fontFamily),
    titleMedium = titleMedium.with(fontFamily),
    titleSmall = titleSmall.with(fontFamily),
    bodyLarge = bodyLarge.with(fontFamily),
    bodyMedium = bodyMedium.with(fontFamily),
    bodySmall = bodySmall.with(fontFamily),
    labelLarge = labelLarge.with(fontFamily),
    labelMedium = labelMedium.with(fontFamily),
    labelSmall = labelSmall.with(fontFamily),
    displayLargeEmphasized = displayLargeEmphasized.with(fontFamily),
    displayMediumEmphasized = displayMediumEmphasized.with(fontFamily),
    displaySmallEmphasized = displaySmallEmphasized.with(fontFamily),
    headlineLargeEmphasized = headlineLargeEmphasized.with(fontFamily),
    headlineMediumEmphasized = headlineMediumEmphasized.with(fontFamily),
    headlineSmallEmphasized = headlineSmallEmphasized.with(fontFamily),
    titleLargeEmphasized = titleLargeEmphasized.with(fontFamily),
    titleMediumEmphasized = titleMediumEmphasized.with(fontFamily),
    titleSmallEmphasized = titleSmallEmphasized.with(fontFamily),
    bodyLargeEmphasized = bodyLargeEmphasized.with(fontFamily),
    bodyMediumEmphasized = bodyMediumEmphasized.with(fontFamily),
    bodySmallEmphasized = bodySmallEmphasized.with(fontFamily),
    labelLargeEmphasized = labelLargeEmphasized.with(fontFamily),
    labelMediumEmphasized = labelMediumEmphasized.with(fontFamily),
    labelSmallEmphasized = labelSmallEmphasized.with(fontFamily),
)
