package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool

/**
 * P2-02 — the per-tool opt-out.
 *
 * `LocalToolOption` is a coarse switch: it turns a whole group on or off (e.g. `sensors` is
 * `list_sensors` **and** `read_sensor`; `media_player` is six tools). This filter adds a
 * second, finer layer driven by
 * [me.rerere.rikkahub.data.model.Assistant.disabledLocalTools] — a set of exact tool names
 * that are removed from the assembled surface even while their group is still enabled.
 *
 * It is applied at the single assembly point (`LocalTools.getTools`), so every caller that
 * goes through `ToolSurfaceResolver` — chat, rerun, fast-path, cron, workflow, sub-agents —
 * sees the same filtered surface.
 *
 * ## Contract
 *
 *  - Empty set ⇒ **nothing happens at all** (no list mutation): an assistant that predates
 *    this card gets the exact same surface it always did.
 *  - The removal is **in place** on purpose: `workflow_create` is built earlier in the same
 *    method with a `knownToolNamesProvider = { tools.map { it.name } }` closure over this very
 *    list, and it must observe the filtered set too.
 *
 * Pure (no Android, no Context) so it is unit-testable on a bare JVM —
 * see `LocalToolsFilterTest`.
 */
object LocalToolFilter {

    /**
     * Names [LocalTools.getTools] appends no matter which group was requested.
     *
     * `check_app_updates` is the app's own update check. `web_fetch` and `web_extract` are
     * gated by Search settings (`enableWebFetchTools`), not by a [LocalToolOption] — the old
     * per-assistant entries were removed and are dropped on decode. A per-group inventory is
     * built with `getTools(listOf(oneGroup))`, so without this set every category lists the
     * two web tools.
     */
    val UNGROUPED_TOOL_NAMES: Set<String> = setOf(
        "check_app_updates",
        "web_fetch",
        "web_extract",
    )

    /**
     * Per-assistant opt-out does not apply to [UNGROUPED_TOOL_NAMES]. Those tools have their
     * own switch (or none); a name left in `disabledLocalTools` from the old per-group rows
     * must not keep them off. An empty input is returned as-is, so the empty-deny no-op below
     * still holds.
     */
    fun applicableDisabledNames(disabledToolNames: Set<String>): Set<String> =
        if (disabledToolNames.isEmpty()) disabledToolNames
        else disabledToolNames - UNGROUPED_TOOL_NAMES

    /**
     * Removes every tool whose [Tool.name] is in [disabledToolNames] from [tools], in place,
     * preserving the order of the survivors. Returns the same list instance for convenience.
     */
    fun removeDisabled(
        tools: MutableList<Tool>,
        disabledToolNames: Set<String>,
    ): MutableList<Tool> {
        if (disabledToolNames.isNotEmpty()) {
            tools.removeAll { it.name in disabledToolNames }
        }
        return tools
    }
}
