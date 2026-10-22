package me.rerere.rikkahub.data.ai.limits

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import me.rerere.ai.core.ReasoningLevel

/**
 * Process-scoped per-conversation override for the assistant's [ReasoningLevel].
 *
 * Why this exists: `subagent_dispatch` accepts a `reasoning_level` so the dispatching
 * parent can size a sub-agent's thinking depth per run (a lookup needs no deep reasoning,
 * a research task may want it). The engine owns the run's lifecycle but not the
 * generation call site, so the override travels through this holder — the same way
 * [ConversationToolStepLimits] carries the run's `max_trips` cap and
 * [me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum] carries per-conversation
 * runtime context without plumbing a parameter through `sendMessage`.
 *
 * Lifecycle: the writer (SubAgentEngine) `set`s after creating the run's conversation and
 * `clear`s in the same `finally` that releases the run's other per-conversation state, so
 * a cancelled / failed / timed-out run cannot leave a stale level behind. Read once per
 * model request inside GenerationLoop. Process-only, no persistence. Providers whose
 * model does not advertise the reasoning ability ignore the level downstream, exactly as
 * they already do for the assistant-level setting.
 */
object ConversationReasoningOverrides {

    private val byConv = ConcurrentHashMap<Uuid, ReasoningLevel>()

    /** Pin [conversationId]'s reasoning level to [level] until [clear]. */
    fun set(conversationId: Uuid, level: ReasoningLevel) {
        byConv[conversationId] = level
    }

    /** The override for [conversationId], or null when the assistant's own level applies. */
    fun get(conversationId: Uuid): ReasoningLevel? = byConv[conversationId]

    /** Drop the override for [conversationId]. */
    fun clear(conversationId: Uuid) {
        byConv.remove(conversationId)
    }
}
