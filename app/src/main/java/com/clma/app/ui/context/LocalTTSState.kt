package com.clma.app.ui.context

import androidx.compose.runtime.compositionLocalOf
import com.clma.app.ui.hooks.CustomTtsState

val LocalTTSState = compositionLocalOf<CustomTtsState> { error("Not provided yet") }
