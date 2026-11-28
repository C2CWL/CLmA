package com.clma.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.data.model.isBot
import com.clma.app.data.botmode.settings.BotModeSettings
import com.clma.app.data.botmode.settings.BotModeSettingsStore
import org.koin.compose.koinInject
import kotlinx.serialization.Serializable
import com.clma.app.ui.hooks.rememberAmoledDarkMode
import com.clma.app.ui.hooks.rememberCurrentColorMode
import com.clma.app.ui.hooks.rememberUserSettingsState

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun RikkahubTheme(
    colorMode: ColorMode = rememberCurrentColorMode(),
    useBotColors: Boolean = false,
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()

    val botTheme = if (
        useBotColors && settings.displaySetting.botModeEnabled &&
        settings.assistants.any { it.id == settings.assistantId && it.isBot }
    ) {
        val store = koinInject<BotModeSettingsStore>()
        val botSettings by store.settings.collectAsStateWithLifecycle(initialValue = BotModeSettings())
        botSettings.customThemes.firstOrNull { it.id == botSettings.activeThemeId }
    } else null

    val colorScheme = when {
        botTheme != null -> remember(botTheme, darkTheme) { botTheme.generateColorScheme(darkTheme) }
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            val theme = findThemeById(settings.themeId, settings.customThemes)
                ?: findPresetTheme(settings.themeId)
            theme.getColorScheme(dark = darkTheme)
        }
    }
    val colorSchemeConverted = remember(darkTheme, amoledDarkMode, colorScheme) {
        colorScheme.withAmoledDarkMode(enabled = darkTheme && amoledDarkMode)
    }

    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors

    // 更新状态栏图标颜色
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(view, darkTheme) {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            val previousLightStatusBars = controller.isAppearanceLightStatusBars
            val previousLightNavigationBars = controller.isAppearanceLightNavigationBars
            controller.apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            onDispose {
                // 嵌套主题（如终端的深色主题）退出时恢复原有系统栏图标颜色。
                controller.isAppearanceLightStatusBars = previousLightStatusBars
                controller.isAppearanceLightNavigationBars = previousLightNavigationBars
            }
        }
    }

    // Both off by default: the typography is unchanged and the font scale multiplier is 1.0.
    val typography = rememberAppTypography(settings.displaySetting)
    val (appDensity, baseFontScale) = rememberAppDensity(settings.displaySetting)

    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalExtendColors provides extendColors,
        LocalOverscrollFactory provides null,
        LocalDensity provides appDensity,
        LocalBaseFontScale provides baseFontScale,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorSchemeConverted,
            typography = typography,
            content = content,
            motionScheme = MotionScheme.expressive()
        )
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current
