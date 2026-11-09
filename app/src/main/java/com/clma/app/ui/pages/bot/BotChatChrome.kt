package com.clma.app.ui.pages.bot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import kotlin.uuid.Uuid

/**
 * What Bot mode swaps in the shared chat surface: its own top bar (with the task chip under it)
 * and where an opened conversation (a fork) goes. Classic mode passes null and is unchanged.
 */
@Immutable
class BotChatChrome(
    val topBar: @Composable () -> Unit,
    val onOpenConversation: (Uuid) -> Unit,
)
