package me.rerere.rikkahub.data.ai.limits

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Process-scoped per-conversation override for [ToolRuntimeLimits.maxToolSteps].
 *
 * Why this exists: `subagent_dispatch` has always accepted `max_trips` (default
 * [me.rerere.rikkahub.subagent.SubAgentDefaults.DEFAULT_MAX_TRIPS]) and echoed it back in
 * the run envelope, but nothing ever enforced it — a sub-agent actually ran up to the
 * global tool-step budget (default far higher), so a dispatch the parent sized for a
 * dozen steps could burn several times that. The engine owns the run's lifecycle but not
 * the generation call site, so the cap travels through this holder, the same way
 * [me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum] carries per-conversation
 * runtime context without plumbing a parameter through `sendMessage`.
 *
 * Lifecycle: the writer (SubAgentEngine) `set`s after creating the run's conversation and
 * `clear`s in the same `finally` that releases the run's other per-conversation state, so
 * a cancelled / failed / timed-out run cannot leave a stale cap behind. Read once per
 * generation at the `GenerationLoop.generateText` call site. Process-only, no persistence.
 */
object ConversationToolStepLimits {

    private val byConv = ConcurrentHashMap<Uuid, Int>()

    /** Cap [conversationId]'s tool-loop steps at [maxSteps]. Values < 1 are treated as a clear. */
    fun set(conversationId: Uuid, maxSteps: Int) {
        if (maxSteps < 1) byConv.remove(conversationId) else byConv[conversationId] = maxSteps
    }

    /** The override for [conversationId], or null when the global default applies. */
    fun get(conversationId: Uuid): Int? = byConv[conversationId]

    /** Drop the override for [conversationId]. */
    fun clear(conversationId: Uuid) {
        byConv.remove(conversationId)
    }
}
