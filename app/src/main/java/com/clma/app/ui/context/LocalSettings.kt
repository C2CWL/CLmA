package com.clma.app.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import com.clma.app.data.datastore.Settings

val LocalSettings = staticCompositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
