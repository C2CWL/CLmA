package com.clma.app.service.island

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.uuid.Uuid

/**
 * Tells the top island that the chat page for [conversationId] is on screen, so the island does
 * not cover the very conversation the user is already looking at.
 */
@Composable
fun TopIslandChatVisibilityEffect(conversationId: Uuid) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, conversationId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> TopIslandController.setVisibleChat(conversationId)
                Lifecycle.Event.ON_PAUSE -> TopIslandController.clearVisibleChat(conversationId)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            TopIslandController.clearVisibleChat(conversationId)
        }
    }
}
