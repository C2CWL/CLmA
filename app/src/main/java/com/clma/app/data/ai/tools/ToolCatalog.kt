package com.clma.app.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart

// internal (not private) because ChatService imports TOOL_CATALOG_MAX_SUMMARY_CHARS to build
// catalog summaries of the same length this file enforces when it serialises search hits.
internal const val TOOL_CATALOG_MAX_SEARCH_RESULTS = 8
internal const val TOOL_CATALOG_MAX_OPEN_PER_CALL = 4
internal const val TOOL_CATALOG_MAX_ACTIVE_SCHEMAS = 16
internal const val TOOL_CATALOG_MAX_SUMMARY_CHARS = 180

/**
 * Page size for `tool_browse`.
 *
 * Sized so a catalogue of a few dozen tools fits in one answer while a 200-tool MCP surface
 * stays readable: 40 short name+summary lines is roughly the point where a model still reads
 * every line instead of skimming, and the reply always says how many entries remain so a second
 * page is an obvious next step rather than a guess.
 */
internal const val TOOL_BROWSE_DEFAULT_PAGE = 40
internal const val TOOL_BROWSE_MAX_PAGE = 60

/**
 * How tools are surfaced to the model.
 *
 * [DIRECT] injects every tool's schema up front; [PROGRESSIVE_CATALOG] injects only
 * the search/open pair, so the request stays small until the model explicitly opts in.
 */
enum class ToolSurfaceMode { DIRECT, PROGRESSIVE_CATALOG }

/** Where a catalogued tool comes from; used for scoring and serialized in search hits. */
enum class ToolCatalogSource { LOCAL, MCP }

/**
 * One catalog entry.
 *
 * [summary] is authored by the caller. This file never truncates the stored value;
 * it only enforces the 180-char cap at serialization time. [tool] is carried so the
 * caller can materialize a real schema once the name is activated — it is never
 * serialized here (it contains lambdas). [group] is the LOCAL tool group the entry came
 * from (a `LocalToolOption` serial name) and is null for MCP entries; it exists so the
 * model can be told which capabilities the catalog covers without listing every name.
 */
data class ToolCatalogEntry(
    val name: String,
    val summary: String,
    val source: ToolCatalogSource,
    val tool: Tool,
    val group: String? = null,
)

/**
 * Immutable, order-preserving catalog.
 *
 * Why a class rather than a bare list: O(1) name lookup plus the scoring logic lives
 * next to the data it ranks. Fully immutable, so no locking is needed.
 */
class ToolCatalog(entries: List<ToolCatalogEntry>) {
    val entries: List<ToolCatalogEntry> = entries.toList()
    private val byName: Map<String, ToolCatalogEntry> = entries.associateBy { it.name }
    val size: Int get() = entries.size

    /**
     * Distinct local group names in inventory order. The model-facing `tool_search`
     * description prints these so it knows which capability areas exist at all; the tool
     * names themselves stay out of the request until the model searches for them.
     */
    val categories: List<String> = this.entries.mapNotNull { it.group }.distinct()

    fun entry(name: String): ToolCatalogEntry? = byName[name]

    /**
     * One catalogued tool rendered as JSON (name, description, schema) — the payload
     * returned when the model calls a tool it never opened. Handing back the real schema
     * instead of a bare "tool_not_found" lets the model retry correctly in the same turn.
     */
    fun schemaJsonFor(name: String): String? = entry(name)?.let { entry ->
        val parameters = entry.tool.parameters()
        buildJsonObject {
            put("name", JsonPrimitive(entry.name))
            put("description", JsonPrimitive(entry.tool.description.take(2000)))
            if (parameters != null) {
                put("parameters", Json.encodeToJsonElement(InputSchema.serializer(), parameters))
            }
        }.toString()
    }

    fun search(query: String, limit: Int = TOOL_CATALOG_MAX_SEARCH_RESULTS): List<ToolCatalogEntry> =
        matchAll(query).take(limit.coerceIn(1, TOOL_CATALOG_MAX_SEARCH_RESULTS))
}

/**
 * Result of one `tool_open` call, bucketed so the model can tell exactly why each
 * requested name did or did not become active.
 */
data class ToolOpenOutcome(
    val activated: List<String>,
    val alreadyActive: List<String>,
    val unknown: List<String>,
    val rejectedOverCap: List<String> = emptyList(),
    val activeCount: Int,
    val evicted: List<String> = emptyList(),
    val rejectedOverCallLimit: List<String> = emptyList(),
) {
    fun toJson(): String {
        val note = buildString {
            append("Activated schemas are callable from your NEXT request onwards — including")
            append(" later steps of this same turn, so open a tool and then call it.")
            if (activated.isEmpty()) {
                if (unknown.isEmpty() && alreadyActive.isEmpty() &&
                    rejectedOverCap.isEmpty() && rejectedOverCallLimit.isEmpty()
                ) {
                    append(" No tool names were provided, so nothing was activated. Call tool_search")
                    append(" first to get exact names, then pass them in the names array.")
                } else {
                    append(" Nothing was activated. Retry: run tool_search first to get exact names,")
                    append(" then call tool_open with those names.")
                }
            }
            if (evicted.isNotEmpty()) {
                append(" Active capacity ($TOOL_CATALOG_MAX_ACTIVE_SCHEMAS) reached; auto-evicted least recently used: ${evicted.joinToString(", ")}.")
            }
            if (rejectedOverCallLimit.isNotEmpty()) {
                append(" One call opens at most $TOOL_CATALOG_MAX_OPEN_PER_CALL new schemas, so")
                append(" ${rejectedOverCallLimit.joinToString(", ")} were left out: call tool_open")
                append(" again with those names.")
            }
            if (rejectedOverCap.isNotEmpty()) {
                append(" This chat already holds the maximum of $TOOL_CATALOG_MAX_ACTIVE_SCHEMAS")
                append(" opened schemas, so ${rejectedOverCap.joinToString(", ")} could not be")
                append(" opened. Release one you are done with using tool_close, then call tool_open")
                append(" again.")
            }
        }
        return Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("activated", JsonArray(activated.map { JsonPrimitive(it) }))
                put("alreadyActive", JsonArray(alreadyActive.map { JsonPrimitive(it) }))
                put("unknown", JsonArray(unknown.map { JsonPrimitive(it) }))
                put("rejectedOverCap", JsonArray(rejectedOverCap.map { JsonPrimitive(it) }))
                put("rejectedOverCallLimit", JsonArray(rejectedOverCallLimit.map { JsonPrimitive(it) }))
                put("evicted", JsonArray(evicted.map { JsonPrimitive(it) }))
                put("activeCount", activeCount)
                put("note", note)
            },
        )
    }
}

/**
 * Result of one `tool_close` call — the inverse bookkeeping of [ToolOpenOutcome], so the model
 * can tell "released", "you never opened that" and "no such tool" apart.
 */
data class ToolCloseOutcome(
    val closed: List<String>,
    val notActive: List<String>,
    val unknown: List<String>,
    val activeCount: Int,
) {
    fun toJson(): String {
        val note = buildString {
            append("Released schemas stop being sent from your next turn; the slot is free now.")
            if (closed.isNotEmpty()) {
                append(" Closed ${closed.joinToString(", ")}.")
            }
            if (notActive.isNotEmpty()) {
                append(" ${notActive.joinToString(", ")} were not open — nothing to close.")
                append(" A tool the user pinned for this assistant is sent with every request")
                append(" regardless, so tool_close cannot remove it.")
            }
            if (unknown.isNotEmpty()) {
                append(" ${unknown.joinToString(", ")} are not in the catalogue, so nothing was")
                append(" closed for them. Use tool_search for exact names.")
            }
        }
        return Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("closed", JsonArray(closed.map { JsonPrimitive(it) }))
                put("notActive", JsonArray(notActive.map { JsonPrimitive(it) }))
                put("unknown", JsonArray(unknown.map { JsonPrimitive(it) }))
                put("activeCount", activeCount)
                put("note", note)
            },
        )
    }
}

/** A ranked search hit — name + summary + source only, deliberately schema-free. */
data class ToolSearchHit(val name: String, val summary: String, val source: ToolCatalogSource)

data class ToolSearchOutcome(
    val hits: List<ToolSearchHit>,
    val total: Int,
    val truncated: Boolean,
) {
    fun toJson(): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("total", total)
            put("returned", hits.size)
            put("truncated", truncated)
            put("results", JsonArray(hits.map { hit ->
                buildJsonObject {
                    put("name", hit.name)
                    put("summary", hit.summary.take(TOOL_CATALOG_MAX_SUMMARY_CHARS))
                    put("source", hit.source.name)
                }
            }))
            if (hits.isEmpty()) {
                put("note", "No tools matched. Try different keywords, or use broader terms.")
            }
        },
    )

}

/**
 * Session-scoped, thread-safe set of activated tool names. Never persisted — it lives
 * only for the lifetime of the conversation, which is exactly the "progressive"
 * contract (activation is per-session state, not data).
 */
class ToolActivationState(
    initial: Set<String> = emptySet(),
) {
    private val lock = Any()
    private val activeNames = LinkedHashSet<String>(initial)

    /** Snapshot in insertion order (fresh copy, safe for the caller to mutate). */
    fun active(): Set<String> = synchronized(lock) { activeNames.toSet() }

    fun contains(name: String): Boolean = synchronized(lock) { activeNames.contains(name) }

    fun clear() = synchronized(lock) { activeNames.clear() }

    /** Drop active names that fell out of the catalog (e.g. an MCP server disconnected). */
    fun retain(knownNames: Set<String>) = synchronized(lock) { activeNames.retainAll(knownNames) }

    /**
     * Activate [names] in the order given.
     *
     * Duplicate handling: the first occurrence is the one that activates; any later
     * occurrence is reported in [ToolOpenOutcome.alreadyActive], and its recency is refreshed
     * in the LRU set. Unknown names never consume the per-call budget, and the per-call
     * budget is applied first-come-first-served.
     *
     * LRU eviction: when active schemas reach [TOOL_CATALOG_MAX_ACTIVE_SCHEMAS], the least
     * recently used active schemas are evicted to make room, rather than rejecting the activation
     * with an error. Evicted names are reported in [ToolOpenOutcome.evicted].
     */
    fun activate(catalog: ToolCatalog, names: List<String>): ToolOpenOutcome = synchronized(lock) {
        val activated = mutableListOf<String>()
        val alreadyActive = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        val rejectedOverCap = mutableListOf<String>()
        val rejectedOverCallLimit = mutableListOf<String>()
        val evicted = mutableListOf<String>()
        var openedThisCall = 0
        for (name in names) {
            if (catalog.entry(name) == null) {
                unknown.add(name)
                continue
            }
            if (name in activeNames) {
                // Refresh LRU recency
                activeNames.remove(name)
                activeNames.add(name)
                alreadyActive.add(name)
                continue
            }
            if (openedThisCall >= TOOL_CATALOG_MAX_OPEN_PER_CALL) {
                rejectedOverCallLimit.add(name)
                continue
            }
            if (activeNames.size >= TOOL_CATALOG_MAX_ACTIVE_SCHEMAS) {
                val oldest = activeNames.iterator().next()
                activeNames.remove(oldest)
                evicted.add(oldest)
            }
            activeNames.add(name)
            activated.add(name)
            openedThisCall++
        }
        ToolOpenOutcome(
            activated = activated,
            alreadyActive = alreadyActive,
            unknown = unknown,
            rejectedOverCap = rejectedOverCap,
            activeCount = activeNames.size,
            evicted = evicted,
            rejectedOverCallLimit = rejectedOverCallLimit,
        )
    }

    /**
     * Release [names] — the inverse of [activate]. The slot frees up *now*, so a `tool_open` in
     * the same turn can use it; the schema itself stops being attached at the next turn, when the
     * surface is rebuilt from the activation set. Names that were never opened, and names that are
     * not in the catalogue at all, are reported apart rather than silently succeeding.
     */
    fun deactivate(catalog: ToolCatalog, names: List<String>): ToolCloseOutcome = synchronized(lock) {
        val closed = mutableListOf<String>()
        val notActive = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        for (name in names) {
            if (catalog.entry(name) == null) {
                unknown.add(name)
                continue
            }
            if (activeNames.remove(name)) {
                closed.add(name)
            } else {
                notActive.add(name)
            }
        }
        ToolCloseOutcome(
            closed = closed,
            notActive = notActive,
            unknown = unknown,
            activeCount = activeNames.size,
        )
    }
}

/**
 * The catalogue control tools that replace a full schema dump.
 *
 * `tool_search` finds names and carries no schema; `tool_open` attaches schemas; `tool_close`
 * releases them again. Real schemas surface only on the model's next *request* —
 * which, since the generation loop re-reads the activation set between steps, is usually a
 * later step of the same turn rather than a new user message.
 */
fun buildToolCatalogTools(
    catalog: ToolCatalog,
    activation: ToolActivationState,
): List<Tool> = listOf(
    Tool(
        name = "tool_search",
        description = buildString {
            append(
                """
                Search the tool catalog and return matching tool names with short summaries.
                Use this whenever you are unsure which tools exist. It returns names and
                summaries ONLY — never schemas or parameters. To actually call a tool, first
                activate it with tool_open; activation applies to your next request, so a tool
                you open can be called in a later step of the same turn.

                Matching is lexical: keywords are scored against tool names, summaries and
                sources. Search with the words that would appear in a tool's name or summary;
                a paraphrase that shares no words will not match. Use tool_browse to list the
                catalogue by name when a search comes back empty.
                """.trimIndent(),
            )
            if (catalog.categories.isNotEmpty()) {
                append("\n\nCapability groups in this catalog: ")
                append(catalog.categories.joinToString(", "))
                append(". Search with one of those group names to see what is inside it.")
            }
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to match against tool names, summaries, and sources.")
                    })
                },
                required = listOf("query"),
            )
        },
        execute = {
            val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val entries = catalog.matchAll(query)
            val limited = entries.take(TOOL_CATALOG_MAX_SEARCH_RESULTS)
            val body = ToolSearchOutcome(
                hits = limited.map { e -> ToolSearchHit(e.name, e.summary, e.source) },
                total = entries.size,
                truncated = entries.size > limited.size,
            ).toJson()
            listOf(UIMessagePart.Text(body))
        },
    ),
    Tool(
        name = "tool_browse",
        description = """
            List the tool catalogue by name, in catalogue order, with each entry's source and a
            one-line summary. This is the index, not a search: it needs no query, only an offset
            and a page size. Use it when a search matched nothing — then the catalogue itself is
            the way to find a name — or to see what exists at all before deciding. Paged: the
            answer says how many entries remain, and `offset` continues from there. Any returned
            name can be passed straight to tool_open.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Index of the first entry to return. Default 0.")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Page size, 1..$TOOL_BROWSE_MAX_PAGE. Default $TOOL_BROWSE_DEFAULT_PAGE.",
                        )
                    })
                },
            )
        },
        execute = {
            val requestedOffset = it.jsonObject["offset"]?.jsonPrimitive?.intOrNull ?: 0
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: TOOL_BROWSE_DEFAULT_PAGE)
                .coerceIn(1, TOOL_BROWSE_MAX_PAGE)
            val all = catalog.entries
            val start = requestedOffset.coerceIn(0, all.size)
            val page = all.drop(start).take(limit)
            val body = buildJsonObject {
                put("total", all.size)
                put("offset", start)
                put("returned", page.size)
                put("remaining", (all.size - start - page.size).coerceAtLeast(0))
                put(
                    "entries",
                    JsonArray(
                        page.map { entry ->
                            buildJsonObject {
                                put("name", entry.name)
                                put("summary", entry.summary.take(TOOL_CATALOG_MAX_SUMMARY_CHARS))
                                put("source", entry.source.name)
                            }
                        },
                    ),
                )
                if (all.isEmpty()) {
                    put("note", "The catalogue is empty: no tools are registered for this turn.")
                }
            }
            listOf(UIMessagePart.Text(Json.encodeToString(JsonObject.serializer(), body)))
        },
    ),
    Tool(
        name = "tool_open",
        description = """
            Activate tools by name so their schemas become callable. Names must be exact
            matches returned by tool_search. Activation applies to your next request — the
            same turn continues, so open now and call on the following step. Do not re-open
            a tool that is already active. Use tool_close to release tools you no longer need.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("names", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Exact tool names to activate, as returned by tool_search.")
                    })
                },
                required = listOf("names"),
            )
        },
        execute = {
            val names = parseNames(it)
            listOf(UIMessagePart.Text(activation.activate(catalog, names).toJson()))
        },
    ),
    Tool(
        name = "tool_close",
        description = """
            Release entries you opened with tool_open and are finished with. The slot frees up
            right away, so you can open a different entry in this same turn; the released schema
            stops being sent from your next turn. Every schema you keep open is sent with every
            request, so closing what you are done with leaves more room for the conversation.
            Only closes what you opened: a tool the user pinned for this assistant is sent with
            every request regardless. Names must be exact matches (from tool_search or from the
            tools already in front of you).
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("names", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Exact names of entries to release.")
                    })
                },
                required = listOf("names"),
            )
        },
        execute = {
            val names = parseNames(it)
            listOf(UIMessagePart.Text(activation.deactivate(catalog, names).toJson()))
        },
    ),
)

/**
 * Full, untruncated, ranked match list — the single home of the scoring logic.
 *
 * [ToolCatalog.search] truncates it, and `tool_search` reuses it to compute the untruncated
 * `total` without duplicating the scoring. P2-05 widened it from `private` to `internal` for one
 * more caller: [LocalToolPalette], the expert editor's palette. The 8-hit cap is a *response*
 * budget for a model, not a bound a human scrolling a list should inherit, so the palette ranks
 * with this and applies its own (larger) bound instead. Nothing about the function changed — the
 * body, the ordering and the tie-break are exactly what they were.
 */
internal fun ToolCatalog.matchAll(query: String): List<ToolCatalogEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val tokens = q.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
    return entries
        .mapNotNull { entry ->
            val score = matchScore(q, tokens, entry)
            if (score > 0) entry to score else null
        }
        .sortedWith(compareByDescending<Pair<ToolCatalogEntry, Int>> { it.second }.thenBy { it.first.name })
        .map { it.first }
}

private fun matchScore(query: String, tokens: List<String>, entry: ToolCatalogEntry): Int {
    val name = entry.name.lowercase()
    val summary = entry.summary.lowercase()
    val sourceName = entry.source.name.lowercase()
    var score = 0
    if (query.isNotEmpty()) {
        if (name == query) {
            score += 100
        } else if (name.contains(query)) {
            score += 40
        }
    }
    for (token in tokens) {
        if (name.contains(token)) score += 10
        if (summary.contains(token)) score += 4
        if (sourceName.contains(token)) score += 2
    }
    return score
}

private fun parseNames(element: JsonElement): List<String> {
    val namesElement = element.jsonObject["names"] ?: return emptyList()
    if (namesElement !is JsonArray) return emptyList()
    return namesElement
        .mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull }
        .filter { it.isNotBlank() }
}
