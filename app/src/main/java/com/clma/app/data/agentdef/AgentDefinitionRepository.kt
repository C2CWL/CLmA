package me.rerere.rikkahub.data.agentdef

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.LocalToolOption

/**
 * P2-06 — the single reader/writer for the expert library.
 *
 * Two consumers with different needs had to be served by one type:
 *
 *  - the **UI** wants a reactive list, hence [definitions] as a [StateFlow];
 *  - the **tool factory** (`LocalTools.getTools`) is not `suspend` and cannot await a Room
 *    read while building the tool list for a turn, hence [snapshotBlocking] — the same
 *    escape hatch `BrowserPreferences.snapshotBlocking()` already uses elsewhere in this
 *    codebase.
 *
 * Both are served by one in-memory cache primed at construction and refreshed after every
 * write, so the blocking read never touches the disk and the reactive read never lags behind a
 * write made through this object. The cache is primed on [AppScope]; until that first load
 * lands the snapshot is empty, which callers must treat as "no experts configured" — never as
 * "this expert does not exist" (the distinction matters for the dispatch error message).
 *
 * Writes are upserts keyed by [AgentDefinition.id]; [update] deliberately does not insert, so a
 * stale id from a second device/UI instance cannot resurrect a deleted expert.
 */
class AgentDefinitionRepository(
    private val dao: AgentDefinitionDao,
    appScope: AppScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _definitions = MutableStateFlow<List<AgentDefinition>>(emptyList())

    /** The cached library, newest read first. See [snapshotBlocking] for the sync read. */
    val definitions: StateFlow<List<AgentDefinition>> = _definitions.asStateFlow()

    init {
        appScope.launch {
            // A boot-time hiccup must not crash the app: the cache simply stays empty and the
            // next explicit read repopulates it. Cancellation is rethrown rather than swallowed,
            // the same discipline the usage recorder follows — a scope being torn down is not a
            // database error and must not be silently absorbed.
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Deliberately silent and non-fatal; see above.
            }
        }
    }

    /** Re-reads every definition and replaces the cache. */
    suspend fun refresh(): List<AgentDefinition> {
        val rows = dao.listAll(AgentDefinitionDefaults.QUERY_LIMIT)
        _definitions.value = rows
        return rows
    }

    /** The cache, without touching the disk. Safe to call from a non-suspend tool factory. */
    fun snapshotBlocking(): List<AgentDefinition> = _definitions.value

    suspend fun byId(id: String): AgentDefinition? = dao.byId(id)

    /**
     * First case-insensitive name match, or null. Backs the roster-write tools, which accept
     * "an id or a name" for update / delete because "delete the Researcher" is the natural thing
     * for a model to say. Names cannot be duplicated through the tools — create and update both
     * reject a case-insensitive clash — so the first row the query returns is deterministic;
     * a collision introduced outside the tools resolves to `unknown_expert`-or-proceed, never to
     * a dispatch target, because dispatch goes through [resolveByName], which refuses ambiguity
     * loudly.
     */
    suspend fun byName(name: String): AgentDefinition? = dao.byName(name).firstOrNull()

    /** Fresh read + resolve in one step, so a dispatch never uses a stale name list. */
    suspend fun resolveByName(name: String?): AgentDefinitionResolver.Result =
        AgentDefinitionResolver.resolve(name, refresh())

    /** Creates a definition and returns the stored row (cache already refreshed). */
    suspend fun create(
        name: String,
        description: String = "",
        systemPrompt: String = "",
        modelId: String? = null,
        enabled: Boolean = true,
        localTools: List<LocalToolOption>? = null,
        disabledLocalTools: Set<String>? = null,
        mcpServers: Set<String>? = null,
        skills: Set<String>? = null,
        slug: String? = null,
        tokenBudget: Long? = null,
        id: String = newId(),
    ): AgentDefinition {
        val at = nowMs()
        val row = AgentDefinition(
            id = id,
            name = name.trim(),
            description = description.trim(),
            systemPrompt = systemPrompt,
            modelId = modelId,
            enabled = enabled,
            localTools = localTools,
            disabledLocalTools = disabledLocalTools,
            mcpServers = mcpServers,
            skills = skills,
            slug = AgentNamespace.normalizeSlug(slug),
            tokenBudget = tokenBudget,
            createdAtMs = at,
            updatedAtMs = at,
        )
        dao.upsert(row)
        refresh()
        return row
    }

    /**
     * Replaces an existing definition, preserving its `createdAtMs`. Returns null when no row
     * with that id exists, so the caller can tell "updated" from "the expert was deleted
     * meanwhile" instead of silently inserting it back.
     */
    suspend fun update(definition: AgentDefinition): AgentDefinition? {
        val existing = dao.byId(definition.id) ?: return null
        val row = definition.copy(
            name = definition.name.trim(),
            description = definition.description.trim(),
            slug = AgentNamespace.normalizeSlug(definition.slug),
            createdAtMs = existing.createdAtMs,
            updatedAtMs = nowMs(),
        )
        dao.upsert(row)
        refresh()
        return row
    }

    /** Deletes by id; true when a row was actually removed. */
    suspend fun delete(id: String): Boolean {
        // The id may be a name the model typed instead of a uuid — accept either, because the
        // tool layer's argument is a free string and "delete the researcher" is the natural
        // thing for a model to say.
        val byId = dao.deleteById(id)
        if (byId > 0) {
            refresh()
            return true
        }
        val byName = dao.byName(id).firstOrNull() ?: return false
        val removed = dao.deleteById(byName.id) > 0
        if (removed) refresh()
        return removed
    }

    suspend fun count(): Int = dao.count()
}
