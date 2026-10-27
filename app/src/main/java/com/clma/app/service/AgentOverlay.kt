package com.clma.app.service

import android.content.Context
import kotlin.uuid.Uuid

/** Tracks live turns for the edge bar. Never creates a separate overlay window. */
object AgentOverlay {
    private val workingConversations = HashSet<String>()

    @Volatile
    var isWorking: Boolean = false
        private set

    fun show(context: Context, conversationId: Uuid? = null) {
        synchronized(workingConversations) {
            workingConversations.add(conversationId?.toString() ?: "<unattributed>")
            isWorking = true
            FloatingBallService.setWorking(context.applicationContext, true)
        }
    }

    fun hide(context: Context, conversationId: Uuid? = null) {
        synchronized(workingConversations) {
            workingConversations.remove(conversationId?.toString() ?: "<unattributed>")
            isWorking = workingConversations.isNotEmpty()
            FloatingBallService.setWorking(context.applicationContext, isWorking)
        }
    }
}
