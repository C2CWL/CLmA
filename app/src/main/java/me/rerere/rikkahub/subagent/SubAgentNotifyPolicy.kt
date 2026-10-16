package me.rerere.rikkahub.subagent

/**
 * Whether a finished BACKGROUND run should wake the parent conversation with a synthetic
 * user message (which costs a full parent turn and may interrupt whatever the user is
 * doing). Two terminal states are deliberately silent:
 *
 *  - CANCELLED: the usual cause is the user pressing stop on the parent (the cancel
 *    cascade) or an explicit `subagent_cancel` — waking the parent right after a stop makes
 *    the app start talking again on its own, which reads as a bug;
 *  - SUCCEEDED with `noResult`: the dispatcher explicitly asked for fire-and-forget to keep
 *    context small; a wake-up turn carrying "it finished" is the opposite of that. The run
 *    stays visible in the registry / settings runs list for anyone who wants it.
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
