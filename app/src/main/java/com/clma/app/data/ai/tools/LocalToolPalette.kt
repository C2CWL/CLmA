package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool

/**
 * P2-05 — the LOCAL producer for [ToolCatalog].
 *
 * The catalogue was built with two sources and only ever produced one: `ChatService` assembles
 * the MCP half of its progressive surface, and [ToolCatalogSource.LOCAL] sat declared but
 * unused. This is the producer for the local half, and its consumer is the **expert editor**,
 * not a chat turn — a searchable, browsable view of the local tool directory
 * (`LocalTools.getTools`), so configuring an expert's surface becomes a lookup instead of a
 * scroll through ~180 tool names.
 *
 * The chat paths are deliberately untouched. Nothing here can put a tool in front of a model:
 * the directory is only ever *read*, and whatever `ToolSurfaceResolver` assembles today it
 * still assembles, byte for byte.
 *
 * ## Shape
 *
 * [LocalToolInventory] is what the caller supplies: one entry per [LocalToolOption] group,
 * carrying the tools the live factory returns for **that group alone**. Grouping is the
 * caller's job because only the caller can construct the factory; ranking is not, and is not
 * re-implemented here — [LocalToolPalette.search] delegates to the very same [ToolCatalog]
 * scorer `tool_search` uses (via [ToolCatalog.matchAll] / [ToolCatalog.search]), so a palette
 * hit and a model-facing hit can never disagree about what "most relevant" means.
 *
 * A name offered by two groups is attributed to the **first** one in inventory order: the row
 * has to point somewhere, and inventory order is the reading order of the group list the user
 * already knows. Names in [LocalToolFilter.UNGROUPED_TOOL_NAMES] are dropped instead: the
 * factory appends them to every group query, and attributing them to the first group would
 * still list `web_fetch` / `web_extract` under a category they do not belong to.
 *
 * Pure: no Android, no coroutines, no `R`, no `Context`. `LocalToolPaletteTest` runs it on a
 * bare JVM.
 */

/** One local-tool group exactly as the live factory produced it. */
data class LocalToolInventory(
    val group: LocalToolOption,
    val tools: List<Tool>,
)

/**
 * One palette row.
 *
 * [source] is carried rather than assumed, so the row renders the provenance it actually came
 * from — the sheet prints [ToolCatalogSource.name] (`LOCAL`), which is what makes "fed by the
 * LOCAL source" visible on screen instead of merely true in the source code.
 */
data class LocalToolPaletteHit(
    val name: String,
    val summary: String,
    val group: LocalToolOption,
    val source: ToolCatalogSource,
)

/** Summary shown for a local tool whose description is blank. */
internal const val LOCAL_TOOL_NO_DESCRIPTION = "No description."

class LocalToolPalette private constructor(
    /** The groups that actually produced a tool, in inventory order (deduplicated). */
    val groups: List<LocalToolOption>,
    private val catalog: ToolCatalog,
    private val groupOf: Map<String, LocalToolOption>,
) {

    /** Every tool in the directory, in inventory order. */
    val hits: List<LocalToolPaletteHit> = catalog.entries.mapNotNull { toHit(it) }

    val size: Int get() = hits.size

    /** The group that owns [name], or null when the directory has no such tool. */
    fun group(name: String): LocalToolOption? = groupOf[name]

    fun hit(name: String): LocalToolPaletteHit? = hits.firstOrNull { it.name == name }

    /**
     * The live catalogue entry — the only way out of this file to the real [Tool] the factory
     * built. Kept so a future caller (e.g. a preview of the tool's JSON schema) can materialise
     * exactly the tool the chat path would have received.
     */
    fun entry(name: String): ToolCatalogEntry? = catalog.entry(name)

    /**
     * Ranked, capped at [TOOL_CATALOG_MAX_SEARCH_RESULTS] — the model-facing semantics, reused
     * verbatim so both sides agree on ordering, ties and the cap.
     */
    fun search(query: String, limit: Int = TOOL_CATALOG_MAX_SEARCH_RESULTS): List<LocalToolPaletteHit> =
        catalog.search(query, limit).mapNotNull { toHit(it) }

    /**
     * The same ranking with no cap: a human scrolling a list is not a response budget. The
     * palette sheet applies its own, larger bound.
     */
    fun matchAll(query: String): List<LocalToolPaletteHit> = catalog.matchAll(query).mapNotNull { toHit(it) }

    private fun toHit(entry: ToolCatalogEntry): LocalToolPaletteHit? =
        groupOf[entry.name]?.let { group ->
            LocalToolPaletteHit(
                name = entry.name,
                summary = entry.summary,
                group = group,
                source = entry.source,
            )
        }

    companion object {

        /**
         * Builds the palette from per-group inventories.
         *
         * Blank tool names are skipped (a nameless tool cannot be addressed by the model either),
         * [LocalToolFilter.UNGROUPED_TOOL_NAMES] are skipped (they belong to no group), and a
         * name already claimed by an earlier group is left with that group.
         */
        fun build(inventory: List<LocalToolInventory>): LocalToolPalette {
            val groups = mutableListOf<LocalToolOption>()
            val groupOf = LinkedHashMap<String, LocalToolOption>()
            val entries = mutableListOf<ToolCatalogEntry>()
            for (section in inventory) {
                for (tool in section.tools) {
                    if (tool.name.isBlank() ||
                        tool.name in LocalToolFilter.UNGROUPED_TOOL_NAMES ||
                        groupOf.containsKey(tool.name)
                    ) continue
                    groupOf[tool.name] = section.group
                    if (section.group !in groups) groups += section.group
                    entries += ToolCatalogEntry(
                        name = tool.name,
                        summary = summarize(tool),
                        // P2-05 — the only producer of this source in the app.
                        source = ToolCatalogSource.LOCAL,
                        tool = tool,
                    )
                }
            }
            return LocalToolPalette(
                groups = groups,
                catalog = ToolCatalog(entries),
                groupOf = groupOf,
            )
        }

        /**
         * The first non-blank line of [Tool.description], whitespace collapsed: the sheet renders
         * a row, and tool descriptions are `trimIndent()`-ed blocks meant for a model, not a list.
         * Not truncated — the row ellipsizes, and [ToolSearchOutcome] (the model-facing path) is
         * where the 180-char cap belongs.
         */
        fun summarize(tool: Tool): String {
            val line = tool.description.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            return line.trim().replace(WHITESPACE, " ").ifEmpty { LOCAL_TOOL_NO_DESCRIPTION }
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
