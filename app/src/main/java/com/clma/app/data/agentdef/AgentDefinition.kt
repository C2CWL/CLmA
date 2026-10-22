package me.rerere.rikkahub.data.agentdef

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.data.ai.tools.LocalToolOption

/**
 * P2-06 — a stored **expert definition**.
 *
 * A definition is the durable, shareable half of what used to live in two places: the
 * DataStore-backed `SubAgentProfile` (name / description / system prompt / model) and the
 * per-assistant tool surface P2-04 taught `SubAgentSurface` to synthesise. From P2-06b on this
 * table is the single source of truth for named experts; the old `subAgents` key is retired.
 *
 * The row is deliberately a *description of an expert*, not an execution record:
 *  - it carries no conversation, no message content and no token counts (that is
 *    `usage_records` / `agent_runs`), and
 *  - it stores **Uuid values as their canonical strings** ([modelId], [mcpServers]). The
 *    synthesis layer parses them; keeping the columns as plain TEXT keeps this file free of
 *    `kotlin.uuid` experimental types and of any serializer that would have to be pinned.
 *
 * The three tool-surface fields keep P2-04's "null = inherit the parent" convention verbatim,
 * so a definition that only picks a model and a prompt produces exactly the assistant the run
 * would have used before P2-04.
 *
 * This table lives in its **own database file** (`agent_definitions.db`, see
 * [AgentDefinitionDatabase]): adding it to `AppDatabase` would move Room's identity hash, and
 * that hash is pinned by `ImportedDatabaseReconciler` — a path already burned once by issue
 * #105, and one whose constants can only be produced by the Room compiler (i.e. by burning CI
 * rounds). The trade-off, recorded here so it is not rediscovered later: definitions are not
 * part of the chat-backup import/restore flow.
 */
@Entity(
    tableName = "agent_definitions",
    indices = [
        Index(name = "idx_agentdef_name", value = ["name"]),
        Index(name = "idx_agentdef_slug", value = ["slug"]),
    ],
)
data class AgentDefinition(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String = "",

    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String = "",

    /** `Uuid` string of the chat model, or null to inherit the parent's. */
    @ColumnInfo(name = "model_id")
    val modelId: String? = null,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    /**
     * The expert's OWN local-tool list. `null` = inherit the parent assistant's; a non-null
     * (possibly empty) list replaces it. See [me.rerere.rikkahub.subagent.SubAgentSurface].
     */
    @ColumnInfo(name = "local_tools")
    val localTools: List<LocalToolOption>? = null,

    /** Per-tool opt-out (P2-02 semantics). `null` = inherit the parent's. */
    @ColumnInfo(name = "disabled_local_tools")
    val disabledLocalTools: Set<String>? = null,

    /** `Uuid` strings of the MCP servers. `null` = inherit the parent's. */
    @ColumnInfo(name = "mcp_servers")
    val mcpServers: Set<String>? = null,

    /** Enabled skill names (same vocabulary as `Assistant.enabledSkills`). `null` = inherit. */
    @ColumnInfo(name = "skills")
    val skills: Set<String>? = null,

    /**
     * D9 — the expert's private namespace, a single path segment under
     * `agents/` inside the parent's workspace. Null = no private namespace (the run keeps
     * whatever the parent had).
     */
    @ColumnInfo(name = "slug")
    val slug: String? = null,

    /**
     * P2-07 reservation: per-orchestration token budget. Null = unlimited (D8). Stored now so
     * the schema does not have to move again when the budget card lands.
     */
    @ColumnInfo(name = "token_budget")
    val tokenBudget: Long? = null,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long = 0L,

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = 0L,
)

object AgentDefinitionDefaults {
    const val MAX_NAME_LENGTH = 60
    const val MAX_DESCRIPTION_LENGTH = 200
    const val MAX_SYSTEM_PROMPT_LENGTH = 16_000

    /** D9 — expert workspaces live under this directory of the parent workspace. */
    const val NAMESPACE_ROOT = "agents"

    /** D9 — the expert's cold-memory folder inside its own namespace. */
    const val COLD_MEMORY_FOLDER = "memory"

    /** Upper bound on how many definitions the store will list in one read. */
    const val QUERY_LIMIT = 500
}
