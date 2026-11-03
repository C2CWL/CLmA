package com.clma.app.service

import android.content.Context
import kotlin.uuid.Uuid

/** Tracks live turns for the edge bar. Never creates a separate overlay window. */
object AgentOverlay {
    private val workingConversations = HashSet<String>()
    private val waitingConversations = LinkedHashMap<Uuid, Boolean>()

    val waitingConversation: Uuid?
        get() = synchronized(workingConversations) { waitingConversations.keys.firstOrNull() }

    val waitingForInput: Boolean
        get() = synchronized(workingConversations) { waitingConversations.values.firstOrNull() == true }

    fun updatePending(context: Context, conversationId: Uuid, messages: List<com.clma.ai.ui.UIMessage>) {
        val pending = PendingToolInteractions.pendingTools(messages)
        synchronized(workingConversations) {
            val before = waitingConversations[conversationId]
            val next = pending.takeIf { it.isNotEmpty() }?.any(PendingToolInteractions::needsInput)
            if (before == next) return
            if (next == null) waitingConversations.remove(conversationId)
            else waitingConversations[conversationId] = next
            FloatingBallService.refreshInteraction(context.applicationContext)
        }
    }

    fun clearPending(context: Context, conversationId: Uuid) {
        synchronized(workingConversations) {
            if (waitingConversations.remove(conversationId) != null) {
                FloatingBallService.refreshInteraction(context.applicationContext)
            }
        }
    }

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
