package me.rerere.rikkahub.data.ai

/**
 * Host-side record of **what the agent actually did to which app** during a turn.
 *
 * Why the host records this instead of asking the model to: screen automation mostly runs on a
 * model with deep thinking off, and a model that never "remembers" to write a note still needs
 * the next run to be cheaper. So the facts that are cheap to observe — the way the app was
 * entered, which selector was used, whether it resolved — are captured here by the tools
 * themselves and flushed to the app's cold-memory playbook at the end of the turn
 * (`ChatService.flushAutomationPlaybook`). The model only ever has to *read* the result.
 *
 * Bounded and per-turn: [reset] at the start of a generation, [drain] at the end. When the model
 * *does* want to add prose to the playbook it still can, with `memory_write`; the two live in
 * separate parts of the file (see `AppPlaybookFile`).
 *
 * Privacy: only structural selectors (`view_id`), the short text of the control that was acted
 * on (≤ [MAX_VALUE_CHARS]), and the activity class of the screen are recorded — never typed
 * input, never whole-screen text, never message contents.
 */
object AutomationRecorder {

    /** Observation lines kept per package before the oldest are dropped. */
    const val MAX_LINES_PER_PACKAGE = 40

    /** Selector / label values are clipped to this many characters. */
    const val MAX_VALUE_CHARS = 40

    /** One app's observations, ready to merge into its playbook. */
    data class Snapshot(val packageName: String, val lines: List<String>)

    private class Log {
        val lines = LinkedHashSet<String>()
    }

    private val logs = java.util.concurrent.ConcurrentHashMap<String, Log>()

    /** Drops everything. Called at the start of a generation, alongside AgentTurnTracker.reset(). */
    fun reset() {
        logs.clear()
    }

    private fun add(packageName: String, line: String) {
        if (packageName.isBlank()) return
        val log = logs.computeIfAbsent(packageName) { Log() }
        synchronized(log) {
            log.lines.add(line)
            while (log.lines.size > MAX_LINES_PER_PACKAGE) {
                val oldest = log.lines.first()
                log.lines.remove(oldest)
            }
        }
    }

    /** Records how an app was entered: `launch_app`, `launch_activity`, `open_url`. */
    fun recordEntry(packageName: String, via: String) {
        add(packageName, "- 入口 $via")
    }

    /**
     * Records one atomic action against [screen] (the activity class it happened on).
     * [selector] is a label from [selectorLabel] / [nodeLabel]; null when the agent targeted a
     * node_id, in which case only the action and outcome carry information.
     */
    fun recordAction(
        packageName: String,
        screen: String?,
        action: String,
        selector: String?,
        ok: Boolean,
    ) {
        val where = screen?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: "?"
        val what = if (selector.isNullOrBlank()) action else "$action $selector"
        add(packageName, "- $where｜$what｜${if (ok) "ok" else "miss"}")
    }

    /** A compact label for a `by` + `value` selector, or null when there is nothing to record. */
    fun selectorLabel(by: String?, value: String?): String? = when (by) {
        "text" -> value?.takeIf { it.isNotBlank() }?.let { "text=\"${clip(it)}\"" }
        "content_description" -> value?.takeIf { it.isNotBlank() }?.let { "cd=\"${clip(it)}\"" }
        "view_id_resource_name" -> value?.takeIf { it.isNotBlank() }?.let { "vid=${clip(it)}" }
        else -> null
    }

    /**
     * A compact label for a node the agent reached by node_id: the structural `view_id` first,
     * then its short text, then its content description.
     */
    fun nodeLabel(viewId: String?, text: String?, contentDescription: String?): String? = when {
        !viewId.isNullOrBlank() -> "vid=${clip(viewId)}"
        !text.isNullOrBlank() -> "text=\"${clip(text)}\""
        !contentDescription.isNullOrBlank() -> "cd=\"${clip(contentDescription)}\""
        else -> null
    }

    private fun clip(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_VALUE_CHARS)

    /** Returns every app observed this turn and clears the recorder, so a flush cannot repeat. */
    fun drain(): List<Snapshot> {
        val snapshot = logs.entries.mapNotNull { (packageName, log) ->
            val lines = synchronized(log) { log.lines.toList() }
            if (lines.isEmpty()) null else Snapshot(packageName, lines)
        }
        logs.clear()
        return snapshot
    }
}
