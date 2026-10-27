package com.clma.app.data.ai.tools

import com.clma.ai.core.Tool

/**
 * P2-05 (chat half) — the LOCAL groups that stay directly attached even when the assistant
 * runs in [ToolSurfaceMode.PROGRESSIVE_CATALOG].
 *
 * The rule: a tool the model needs *before* it can plan anything is never something it should
 * have to look up first. `ask_user` is the only local group in that position — it is how the
 * model asks which of the other ~180 tools the user actually wants. Everything else (device
 * sensors, screen automation, files, ssh, workflow authoring, …) is a capability the model can
 * discover from a description, so it goes into the catalog.
 *
 * The always-on tools that belong to no group at all (`check_app_updates`, `web_fetch`,
 * `web_extract` — see [LocalToolFilter.UNGROUPED_TOOL_NAMES]) stay direct for a different
 * reason: they are gated by a global setting rather than a per-assistant toggle, so cataloguing
 * them would make a Search setting silently unreachable from the model.
 */
internal val CORE_LOCAL_TOOL_GROUPS: Set<LocalToolOption> = setOf(LocalToolOption.AskUser)

/**
 * The model-facing label for a local tool group.
 *
 * `LocalToolOption` members are `data object`s, so `toString()` is the class simple name
 * (`MediaPlayer`, `WifiInfo`); snake-casing it yields the same identifier the serialized,
 * user-facing group list already uses (`media_player`, `wifi_info`). This is a label, never a
 * key: nothing round-trips it through a decoder, so it cannot drift into a compatibility issue.
 */
internal fun catalogCategoryKey(group: LocalToolOption): String = snakeCase(group.toString())

internal fun snakeCase(raw: String): String {
    val out = StringBuilder(raw.length + 4)
    raw.forEachIndexed { index, char ->
        if (char.isUpperCase()) {
            val previous = raw.getOrNull(index - 1)
            if (index > 0 && previous != null && !previous.isUpperCase() && previous != '_') {
                out.append('_')
            }
            out.append(char.lowercaseChar())
        } else {
            out.append(char)
        }
    }
    return out.toString()
}

/**
 * One turn's model-facing tool surface, assembled once and then re-snapshotted per step.
 *
 * ## Why a holder
 *
 * In [ToolSurfaceMode.DIRECT] the surface is a fixed list and [tools] hands it back untouched.
 * In [ToolSurfaceMode.PROGRESSIVE_CATALOG] the list has three parts: the always-attached tools
 * ([fixed]), the two discovery tools, and whatever `tool_open` has activated so far.
 *
 * The last part is the reason this is a class and not a `List<Tool>`: activation is mutable
 * session state that `tool_open` changes *while* the generation loop is running, so the loop
 * must be able to ask for the list again between steps. [tools] is cheap and side-effect free —
 * it re-reads [ToolActivationState] and re-materialises the activated entries — so a
 * `tool_open` result lands in the very next request of the same turn instead of costing the
 * model another user round-trip.
 *
 * Assembling the underlying [ToolCatalog] is *not* cheap (it walks the local tool factory and
 * the MCP registry), which is exactly why it happens once per turn here rather than per step.
 */
class ChatToolSurface internal constructor(
    /** Always-attached tools, in assembly order (search, core local, workspace, cold memory, …). */
    private val fixed: List<Tool>,
    private val catalog: ToolCatalog? = null,
    private val activation: ToolActivationState? = null,
    /**
     * P3-03 — the names this turn's own request retrieved, best first, computed once when the
     * surface is assembled.
     *
     * Fixed for the whole turn on purpose: the guess must not drift halfway through a turn, and
     * re-embedding the user on every step would be work for nothing. Explicit activation is the
     * dynamic half (see [tools]), because a tool the model opened has to be callable right away.
     */
    private val rankedNames: List<String> = emptyList(),
) {

    /** True when this surface is catalogued (i.e. the assistant opted into the mode). */
    val isProgressive: Boolean get() = catalog != null && activation != null

    /**
     * The tool list to send with the next request: the fixed prefix, then `tool_search` /
     * `tool_browse` / `tool_open` / `tool_close`, then every currently activated schema in
     * activation order, then up to [ToolRankFusion.DEFAULT_TURN_TOOL_BUDGET] tools the user's own
     * request retrieved.
     *
     * The retrieved tail is what makes progressive mode usable rather than one round trip away
     * from every tool: screen automation should not have to discover `take_screenshot` before it
     * can see the screen. It costs a bounded number of schemas — the ones already attached (fixed
     * or activated) do not consume the budget, because they were going to be there anyway.
     *
     * A direct surface is returned by reference, so the DIRECT path allocates nothing new.
     */
    fun tools(): List<Tool> {
        val catalog = this.catalog ?: return fixed
        val activation = this.activation ?: return fixed
        val attached = LinkedHashSet<String>(fixed.size + 16)
        fixed.forEach { attached += it.name }
        activation.active().forEach { attached += it }
        val retrieved = if (rankedNames.isEmpty()) {
            emptyList()
        } else {
            ToolRankFusion
                .attachedForTurn(
                    ranked = rankedNames,
                    pinned = emptyList(),
                    activated = attached.toList(),
                )
                .filterNot { it in attached }
        }
        return buildList {
            addAll(fixed)
            addAll(buildToolCatalogTools(catalog, activation))
            activation.active().forEach { name -> catalog.entry(name)?.let { add(it.tool) } }
            retrieved.forEach { name -> catalog.entry(name)?.let { add(it.tool) } }
        }
    }


    /**
     * The schema of a catalogued-but-not-activated tool, or null when [name] is not catalogued
     * at all. Used by the generation loop to answer a premature call with the real parameters
     * instead of a bare `tool_not_found`.
     */
    fun schemaForUnloadedTool(name: String): String? = catalog?.schemaJsonFor(name)
}