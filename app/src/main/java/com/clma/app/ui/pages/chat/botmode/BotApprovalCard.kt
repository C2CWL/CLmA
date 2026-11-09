package com.clma.app.ui.pages.chat.botmode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * When true, pending tool approvals render as inline chat cards instead of the classic
 * modal dialog. Classic mode leaves this false so ToolInteractionDialogHost behaviour
 * is unchanged.
 */
val LocalBotModeEnabled = staticCompositionLocalOf { false }

/** Optional unread / task hooks for Bot mode chrome; unused by classic paths. */
val LocalBotModeTaskOpen = compositionLocalOf<(() -> Unit)?> { null }
