package me.rerere.rikkahub.data.ai.tools

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Process-scoped per-conversation queue of system notifications that must reach the model
 * WITHOUT becoming a visible chat message.
 *
 * Why this exists: a background sub-agent used to report completion by posting a real user
 * message into the parent conversation ([ChatService.sendMessage]). That had three costs the
 * feature was built to avoid: the notice rendered as a chat bubble ("[Sub-agent …]"), it
 * waited up to a minute for the parent to go idle (or cancelled the in-flight turn when the
 * wait expired), and it spawned a whole new LLM turn just to say "done". An injection
 * instead rides the NEXT model request the conversation was going to make anyway — the very
 * next loop step when a generation is in flight, the user's next message otherwise — as a
 * request-only synthetic message that is never persisted, never rendered, and never embedded
 * into a vector index.
 *
 * Lifecycle: writers `push` at any time; GenerationLoop `drain`s immediately before every
 * model request, so an in-flight turn sees the notice on its next step instead of after the
 * turn ends. Entries are per-conversation and process-only: if the process dies the notice
 * is lost, which matches the pre-existing persistence contract of the sub-agent registry
 * (the durable `agent_runs` ledger still records the outcome). Undelivered entries are
 * capped at [MAX_PENDING] per conversation (oldest dropped) so a conversation the user
 * abandons cannot accumulate notices without bound.
 */
object ConversationInjections {

    /** Max undelivered notices kept per conversation; oldest are dropped past the cap. */
    const val MAX_PENDING = 20

    private val byConv = ConcurrentHashMap<Uuid, ArrayDeque<String>>()

    /** Queue [text] for [conversationId]'s next model request. Blank texts are ignored. */
    fun push(conversationId: Uuid, text: String) {
        if (text.isBlank()) return
        val deque = byConv.getOrPut(conversationId) { ArrayDeque() }
        synchronized(deque) {
            deque.addLast(text)
            while (deque.size > MAX_PENDING) deque.removeFirst()
        }
    }

    /**
     * Atomically take every pending notice for [conversationId] (oldest first), leaving the
     * queue empty. Returns an empty list when nothing is pending.
     */
    fun drain(conversationId: Uuid): List<String> {
        val deque = byConv[conversationId] ?: return emptyList()
        synchronized(deque) {
            if (deque.isEmpty()) return emptyList()
            val out = deque.toList()
            deque.clear()
            byConv.remove(conversationId, deque)
            return out
        }
    }

    /** How many notices are waiting for [conversationId] (0 when none). Observability only. */
    fun pendingCount(conversationId: Uuid): Int {
        val deque = byConv[conversationId] ?: return 0
        return synchronized(deque) { deque.size }
    }

    /** Drop every pending notice for [conversationId]. */
    fun clear(conversationId: Uuid) {
        byConv.remove(conversationId)
    }
}
