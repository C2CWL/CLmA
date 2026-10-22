package com.clma.app.ui.context

import androidx.compose.runtime.compositionLocalOf
import com.clma.app.ui.hooks.CustomAsrState

val LocalASRState = compositionLocalOf<CustomAsrState> { error("Not provided yet") }

