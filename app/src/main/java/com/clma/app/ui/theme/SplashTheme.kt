package com.clma.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.StyleRes
import com.clma.app.R

/**
 * Launch-screen (splash) style per colour mode.
 *
 * The system draws the splash before any app code runs, so it can't read the user's settings at
 * that moment. On Android 12+ (API 31) the app can, however, tell the system which splash theme
 * to use for the *next* cold starts ([android.window.SplashScreen.setSplashScreenTheme]); the
 * system persists that choice by theme *name*. So:
 *  - first launch after install, or right after changing the in-app light/dark setting: the
 *    previous / manifest default (`Theme.CLmA.Splash.Claude`, light/dark following the system);
 *  - from the next cold start on: the splash matches the in-app light/dark setting.
 *
 * The style names in res/values/themes_splash.xml are persisted by the system — never rename them.
 */
enum class SplashStyle(val styleName: String) {
    /** Claude warm, light/dark follow the system night mode (values / values-night). Manifest default. */
    CLAUDE("Theme.CLmA.Splash.Claude"),
    CLAUDE_LIGHT("Theme.CLmA.Splash.ClaudeLight"),
    CLAUDE_DARK("Theme.CLmA.Splash.ClaudeDark"),
    GEMINI("Theme.CLmA.Splash.Gemini"),
    GEMINI_LIGHT("Theme.CLmA.Splash.GeminiLight"),
    GEMINI_DARK("Theme.CLmA.Splash.GeminiDark");

    @get:StyleRes
    val styleRes: Int
        get() = when (this) {
            CLAUDE -> R.style.Theme_CLmA_Splash_Claude
            CLAUDE_LIGHT -> R.style.Theme_CLmA_Splash_ClaudeLight
            CLAUDE_DARK -> R.style.Theme_CLmA_Splash_ClaudeDark
            GEMINI -> R.style.Theme_CLmA_Splash_Gemini
            GEMINI_LIGHT -> R.style.Theme_CLmA_Splash_GeminiLight
            GEMINI_DARK -> R.style.Theme_CLmA_Splash_GeminiDark
        }

    companion object {
        /**
         * Splash for the in-app colour mode. For now always Claude warm (the only app palette
         * with a splash so far); the Gemini styles already exist so their names never change.
         * "Follow system" uses the day/night style so the system picks light or dark at launch.
         */
        fun resolve(colorMode: ColorMode): SplashStyle = when (colorMode) {
            ColorMode.SYSTEM -> CLAUDE
            ColorMode.LIGHT -> CLAUDE_LIGHT
            ColorMode.DARK -> CLAUDE_DARK
        }
    }
}

private const val SPLASH_PREFS = "splash_theme"
private const val KEY_APPLIED = "applied_style"
private const val TAG = "SplashTheme"

/**
 * Tells the system which splash to show on the next cold start (API 31+, no-op below). Only
 * calls into the system when the wanted style differs from the last one applied, so it is cheap
 * to call on every start and on every theme change.
 */
fun Activity.syncSplashTheme(style: SplashStyle) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val prefs = getSharedPreferences(SPLASH_PREFS, Context.MODE_PRIVATE)
    if (prefs.getString(KEY_APPLIED, null) == style.styleName) return
    try {
        applySplashTheme(style)
        prefs.edit().putString(KEY_APPLIED, style.styleName).apply()
    } catch (e: Exception) {
        // OEM builds may reject the call; the manifest default still shows, nothing else breaks.
        Log.w(TAG, "setSplashScreenTheme failed", e)
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun Activity.applySplashTheme(style: SplashStyle) {
    // The manifest default needs no override; resetting keeps the system state minimal.
    val themeId = if (style == SplashStyle.CLAUDE) Resources.ID_NULL else style.styleRes
    splashScreen.setSplashScreenTheme(themeId)
}
