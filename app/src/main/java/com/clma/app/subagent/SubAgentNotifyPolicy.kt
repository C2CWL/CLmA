package me.rerere.rikkahub.subagent

/**
 * Whether a finished BACKGROUND run should deliver its outcome to the parent conversation
 * (via [ConversationInjections] — a request-only system notification, never a visible chat
 * message). Two terminal states are deliberately silent:
 *
 *  - CANCELLED: the usual cause is the user pressing stop on the parent (the cancel
 *    cascade) or an explicit `subagent_cancel` — telling the parent right after a stop makes
 *    the app start talking again on its own, which reads as a bug;
 *  - SUCCEEDED with `noResult`: the dispatcher explicitly asked for fire-and-forget to keep
 *    context small; a completion notice carrying "it finished" is the opposite of that. The
 *    run stays visible in the registry / settings runs list for anyone who wants it.
 *
 * Failures and timeouts always notify: background work the parent is waiting on must never
 * fail in silence. Foreground runs never notify (the dispatch tool result already carried
 * the outcome) — that rule lives in the engine and is not duplicated here.
 *
 * Pure: operates only on [SubAgentRun] — unit-tested on the JVM.
 */
internal fun shouldNotifyParent(run: SubAgentRun): Boolean = when (run.status) {
    SubAgentStatus.CANCELLED -> false
    SubAgentStatus.SUCCEEDED -> !run.noResult
    SubAgentStatus.FAILED,
    SubAgentStatus.TIMED_OUT,
    -> true
    // Non-terminal runs have nothing to notify about; the engine only calls this at the end.
    SubAgentStatus.PENDING,
    SubAgentStatus.RUNNING,
    -> false
}

/**
 * The text of the system notification a finished background run pushes into the parent's
 * [me.rerere.rikkahub.data.ai.tools.ConversationInjections] queue. GenerationLoop drains the
 * queue immediately before the parent's next model request and appends the notice as a
 * request-only synthetic message — so the model learns the outcome on its very next step
 * (mid-turn when a generation is in flight) without anything ever rendering in the chat
 * page or being persisted into the conversation / vector index.
 *
 * [usageLine] is pre-rendered by the caller (the engine, which owns the usage types) so this
 * function stays free of the data.usage module; pass null when the run billed nothing.
 *
 * The bracketed framing matters: the notice arrives in the USER slot, so it must say loudly
 * that it is not the user talking — otherwise the model answers it like a message and the
 * "system told me" illusion breaks.
 *
 * Pure: string assembly over [SubAgentRun] only — unit-tested on the JVM.
 */
internal object SubAgentCompletionNotice {

    fun format(run: SubAgentRun, usageLine: String?): String = buildString {
        append("[System notification — a background sub-agent you dispatched has finished. ")
        append("This is not a user message; do not answer it directly. ")
        append("Weave the outcome into your work, and tell the user only if it matters to them.]")
        append('\n')
        append("Sub-agent: ").append(run.label)
        append('\n')
        append("Status: ").append(run.status.name)
        if (!usageLine.isNullOrBlank()) {
            append('\n')
            append(usageLine)
        }
        run.error?.takeIf { it.isNotBlank() }?.let {
            append('\n')
            append("Error: ").append(it)
        }
        // noResult runs never reach this formatter (shouldNotifyParent gates them out), but
        // keep the guard so a future caller cannot leak a suppressed result by accident.
        if (!run.noResult) {
            run.result?.takeIf { it.isNotBlank() }?.let {
                append('\n')
                append("Result:")
                append('\n')
                append(it)
            }
        }
    }
}
