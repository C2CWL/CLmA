package me.rerere.rikkahub.subagent

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agentdef.AgentDefinition
import me.rerere.rikkahub.data.agentdef.AgentDefinitionDefaults
import me.rerere.rikkahub.data.agentdef.AgentDefinitionRepository
import me.rerere.rikkahub.data.agentdef.AgentNamespace
import me.rerere.rikkahub.data.agentdef.LocalToolGroups
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.agentrun.AgentRunStatus
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.tools.HeadlessToolApprovalPolicy
import me.rerere.rikkahub.data.ai.tools.LenientLocalToolListSerializer
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

/**
 * P2-06b — the expert-library WRITE tools: `subagent_create` / `subagent_update` /
 * `subagent_delete`.
 *
 * Until now an expert could only be created on the settings screen. These three let the parent
 * assistant manage its own roster instead ("make a research expert that uses the cheap model,
 * then dispatch it"), which is what makes the mixed preset useful without the user
 * hand-configuring anything up front.
 *
 * Three rules shaped the implementation:
 *
 *  - **A write is never silent.** Every call opens an `agent_runs` row
 *    ([AgentRunKind.AgentDefWrite]) before touching the store and marks it terminal afterwards,
 *    success or failure, exactly like the price-table tool (P2-11d-3, D10). The row carries the
 *    operation, the expert's id/name and which fields changed — metadata only, never the
 *    definition's prompt text (the ledger stores no content).
 *  - **A write is never unattended.** All three tools are in `ToolApprovalDefaults.DANGEROUS_TOOLS`,
 *    so a human confirms each call in an ordinary conversation, and they are in
 *    [HeadlessToolApprovalPolicy]'s refused set, so a cron / workflow / sub-agent run cannot
 *    rewrite the roster behind the user's back. The refusal below is the second layer: even a
 *    direct in-process caller that forgot to consult the policy gets the same structured
 *    envelope the runtime would have produced.
 *  - **A name is an identity.** Dispatch matches experts case-insensitively, so two experts
 *    whose names differ only by case make every dispatch ambiguous. Create and update reject
 *    such a name outright rather than letting the ambiguity surface at dispatch time.
 */

internal const val EXPERT_CREATE_TOOL_NAME = "subagent_create"
internal const val EXPERT_UPDATE_TOOL_NAME = "subagent_update"
internal const val EXPERT_DELETE_TOOL_NAME = "subagent_delete"

/**
 * The model-visible shape of one stored expert, shared by `subagent_list kind=experts` and the
 * write tools' replies. Fields left at their "inherit the parent" default are omitted rather
 * than sent as null, so an expert that only picks a model reads exactly that small.
 *
 * The local-tool array is encoded with the SAME [LenientLocalToolListSerializer] the store and
 * `Assistant.localTools` use, i.e. `[{"type":"time_info"},…]` — one vocabulary, and one a model
 * can echo straight back on a later write.
 */
internal fun encodeDefinition(definition: AgentDefinition): JsonObject = buildJsonObject {
    put("id", definition.id)
    put("name", definition.name)
    put("description", definition.description)
    put("enabled", definition.enabled)
    if (definition.modelId != null) put("model_id", definition.modelId)
    if (definition.slug != null) put("slug", definition.slug)
    if (definition.tokenBudget != null) put("token_budget", definition.tokenBudget)
    if (definition.systemPrompt.isNotEmpty()) put("system_prompt", definition.systemPrompt)
    definition.localTools?.let { tools ->
        put("local_tools", JsonInstant.encodeToJsonElement(LenientLocalToolListSerializer, tools))
    }
    definition.disabledLocalTools?.let { disabled ->
        put("disabled_local_tools", buildJsonArray { disabled.sorted().forEach { add(it) } })
    }
    definition.mcpServers?.let { servers ->
        put("mcp_servers", buildJsonArray { servers.sorted().forEach { add(it) } })
    }
    definition.skills?.let { skills ->
        put("skills", buildJsonArray { skills.sorted().forEach { add(it) } })
    }
}

fun subagentCreateTool(
    repository: AgentDefinitionRepository,
    agentRunRepository: AgentRunRepository,
    settingsStore: SettingsStore,
    callerContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = EXPERT_CREATE_TOOL_NAME,
    description = """
        Create a reusable expert — a named specialist (own system prompt, model, and optionally
        its own tool surface / namespace) that subagent_dispatch can then run by `agent` name.
        Prefer this over retyping the same instructions on every dispatch. Fails if another
        expert already uses the name (case-insensitive), because dispatch resolves by name.
        Requires user approval; recorded in the run ledger.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Short unique name, e.g. \"Researcher\". Matched case-insensitively by subagent_dispatch.")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "One line shown next to the name in subagent_dispatch's expert list.")
                })
                put("system_prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "Instructions prepended to every task this expert runs. Omit for none.")
                })
                put("model_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Model for this expert: a model uuid, a provider model id, or a display name (case-insensitive). Omit to inherit the parent assistant's model.")
                })
                put("enabled", buildJsonObject { put("type", "boolean") })
                put("slug", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional namespace slug (lowercase letters, digits, single dashes) giving this expert a private agents/<slug>/ directory in the workspace. Omit for none.")
                })
                put("token_budget", buildJsonObject { put("type", "integer") })
                put("local_tools", surfaceArraySchema(LOCAL_TOOLS_DESCRIPTION))
                put("disabled_local_tools", surfaceArraySchema(DISABLED_LOCAL_TOOLS_DESCRIPTION))
                put("mcp_servers", surfaceArraySchema(MCP_SERVERS_DESCRIPTION))
                put("skills", surfaceArraySchema(SKILLS_DESCRIPTION))
            },
            required = listOf("name"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        headlessRefusal(callerContext, EXPERT_CREATE_TOOL_NAME)?.let { return@Tool it }
        val params = args.jsonObject

        val name = params.str("name")?.trim().orEmpty()
        if (name.isEmpty()) return@Tool errEnv("invalid_name", "name is required and may not be blank")
        if (name.length > AgentDefinitionDefaults.MAX_NAME_LENGTH) {
            return@Tool errEnv(
                "invalid_name",
                "name exceeds ${AgentDefinitionDefaults.MAX_NAME_LENGTH} chars; got ${name.length}",
            )
        }
        val description = params.str("description")?.trim().orEmpty()
        if (description.length > AgentDefinitionDefaults.MAX_DESCRIPTION_LENGTH) {
            return@Tool errEnv(
                "invalid_description",
                "description exceeds ${AgentDefinitionDefaults.MAX_DESCRIPTION_LENGTH} chars; got ${description.length}",
            )
        }
        val systemPrompt = params.str("system_prompt").orEmpty()
        if (systemPrompt.length > AgentDefinitionDefaults.MAX_SYSTEM_PROMPT_LENGTH) {
            return@Tool errEnv(
                "invalid_system_prompt",
                "system_prompt exceeds ${AgentDefinitionDefaults.MAX_SYSTEM_PROMPT_LENGTH} chars; got ${systemPrompt.length}",
            )
        }
        val slugRaw = params.str("slug")
        val slug = AgentNamespace.normalizeSlug(slugRaw)
        if (!slugRaw.isNullOrBlank() && slug == null) {
            return@Tool errEnv(
                "invalid_slug",
                "slug must be lowercase ASCII letters, digits and single dashes (max " +
                    "${AgentNamespace.MAX_SLUG_LENGTH}); got \"$slugRaw\"",
            )
        }
        val tokenBudget = params.long("token_budget")
        if (tokenBudget != null && tokenBudget <= 0) {
            return@Tool errEnv("invalid_token_budget", "token_budget must be positive; got $tokenBudget")
        }
        val modelId = when (
            val resolved = resolveModelField(params, settingsStore)
        ) {
            is ModelField.Resolved -> resolved.modelId
            is ModelField.Invalid -> return@Tool errEnv("unknown_model", resolved.message)
        }

        val localTools = when (val update = params.fieldUpdate("local_tools")) {
            is FieldUpdate.Unchanged, is FieldUpdate.Inherit -> null
            is FieldUpdate.Own -> parseLocalTools(update.value).getOrElse {
                return@Tool errEnv("invalid_local_tools", it.message ?: "invalid local_tools")
            }
        }
        val disabledLocalTools = when (val update = params.fieldUpdate("disabled_local_tools")) {
            is FieldUpdate.Unchanged, is FieldUpdate.Inherit -> null
            is FieldUpdate.Own -> parseStringSet(update.value, "disabled_local_tools").getOrElse {
                return@Tool errEnv("invalid_disabled_local_tools", it.message ?: "invalid disabled_local_tools")
            }
        }
        val mcpServers = when (val update = params.fieldUpdate("mcp_servers")) {
            is FieldUpdate.Unchanged, is FieldUpdate.Inherit -> null
            is FieldUpdate.Own -> parseMcpServers(update.value, settingsStore.settingsFlow.value.mcpServers).getOrElse {
                return@Tool errEnv("invalid_mcp_servers", it.message ?: "invalid mcp_servers")
            }
        }
        val skills = when (val update = params.fieldUpdate("skills")) {
            is FieldUpdate.Unchanged, is FieldUpdate.Inherit -> null
            is FieldUpdate.Own -> parseStringSet(update.value, "skills").getOrElse {
                return@Tool errEnv("invalid_skills", it.message ?: "invalid skills")
            }
        }

        val existing = repository.refresh()
        findNameClash(existing, name, exceptId = null)?.let { clash ->
            return@Tool errEnv(
                "duplicate_name",
                "an expert named \"${clash.name}\" already exists (id ${clash.id}); pick another " +
                    "name or update that one instead",
            )
        }

        val id = UUID.randomUUID().toString()
        val auditId = openWriteAudit(
            agentRunRepository = agentRunRepository,
            op = "create",
            domainId = id,
            parentRunId = callerContext.callerConversationId,
            extra = buildJsonObject {
                put("name", name)
                put("enabled", params.bool("enabled") ?: true)
            },
        )
        val stored = runCatching {
            repository.create(
                name = name,
                description = description,
                systemPrompt = systemPrompt,
                modelId = modelId,
                enabled = params.bool("enabled") ?: true,
                slug = slug,
                tokenBudget = tokenBudget,
                localTools = localTools,
                disabledLocalTools = disabledLocalTools,
                mcpServers = mcpServers,
                skills = skills,
                id = id,
            )
        }.getOrElse { error ->
            agentRunRepository.markTerminal(auditId, AgentRunStatus.failed, error.message)
            return@Tool errEnv("write_failed", error.message ?: "unknown failure while writing the expert")
        }
        agentRunRepository.markTerminal(auditId, AgentRunStatus.succeeded)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", true)
            put("audit_run_id", auditId)
            put("expert", encodeDefinition(stored))
        }.toString()))
    },
)

fun subagentUpdateTool(
    repository: AgentDefinitionRepository,
    agentRunRepository: AgentRunRepository,
    settingsStore: SettingsStore,
    callerContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = EXPERT_UPDATE_TOOL_NAME,
    description = """
        Update a stored expert. Identify it by id or by name (read subagent_list
        kind="experts" first). Only the fields you pass are changed; everything else is left
        as it was. Fails if the new name is already taken by another expert. Requires user
        approval; recorded in the run ledger.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "The expert's id, or its current name (case-insensitive).")
                })
                put("name", buildJsonObject { put("type", "string") })
                put("description", buildJsonObject { put("type", "string") })
                put("system_prompt", buildJsonObject { put("type", "string") })
                put("model_id", buildJsonObject {
                    put("type", "string")
                    put("description", "New model; pass an empty string to go back to inheriting the parent's model.")
                })
                put("enabled", buildJsonObject { put("type", "boolean") })
                put("slug", buildJsonObject {
                    put("type", "string")
                    put("description", "New namespace slug; pass an empty string to remove the private namespace.")
                })
                put("token_budget", buildJsonObject {
                    put("type", "integer")
                    put("description", "New token budget; pass 0 to clear it (unlimited).")
                })
                put("local_tools", surfaceArraySchema(LOCAL_TOOLS_UPDATE_DESCRIPTION))
                put("disabled_local_tools", surfaceArraySchema(DISABLED_LOCAL_TOOLS_UPDATE_DESCRIPTION))
                put("mcp_servers", surfaceArraySchema(MCP_SERVERS_UPDATE_DESCRIPTION))
                put("skills", surfaceArraySchema(SKILLS_UPDATE_DESCRIPTION))
            },
            required = listOf("id"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        headlessRefusal(callerContext, EXPERT_UPDATE_TOOL_NAME)?.let { return@Tool it }
        val params = args.jsonObject

        val target = params.str("id")?.trim().orEmpty()
        if (target.isEmpty()) return@Tool errEnv("invalid_id", "id is required (an expert id or name)")
        val existing = repository.byId(target) ?: repository.byName(target)
            ?: return@Tool errEnv("unknown_expert", "no expert with id or name \"$target\"")

        var row = existing
        val changed = mutableListOf<String>()

        if (params.has("name")) {
            val name = params.str("name")?.trim().orEmpty()
            if (name.isEmpty()) return@Tool errEnv("invalid_name", "name may not be blank")
            if (name.length > AgentDefinitionDefaults.MAX_NAME_LENGTH) {
                return@Tool errEnv(
                    "invalid_name",
                    "name exceeds ${AgentDefinitionDefaults.MAX_NAME_LENGTH} chars; got ${name.length}",
                )
            }
            findNameClash(repository.refresh(), name, exceptId = existing.id)?.let { clash ->
                return@Tool errEnv(
                    "duplicate_name",
                    "another expert is already named \"${clash.name}\" (id ${clash.id}); pick another name",
                )
            }
            if (name != existing.name) {
                row = row.copy(name = name)
                changed += "name"
            }
        }
        if (params.has("description")) {
            val description = params.str("description")?.trim().orEmpty()
            if (description.length > AgentDefinitionDefaults.MAX_DESCRIPTION_LENGTH) {
                return@Tool errEnv(
                    "invalid_description",
                    "description exceeds ${AgentDefinitionDefaults.MAX_DESCRIPTION_LENGTH} chars; got ${description.length}",
                )
            }
            if (description != existing.description) {
                row = row.copy(description = description)
                changed += "description"
            }
        }
        if (params.has("system_prompt")) {
            val systemPrompt = params.str("system_prompt").orEmpty()
            if (systemPrompt.length > AgentDefinitionDefaults.MAX_SYSTEM_PROMPT_LENGTH) {
                return@Tool errEnv(
                    "invalid_system_prompt",
                    "system_prompt exceeds ${AgentDefinitionDefaults.MAX_SYSTEM_PROMPT_LENGTH} chars; got ${systemPrompt.length}",
                )
            }
            if (systemPrompt != existing.systemPrompt) {
                row = row.copy(systemPrompt = systemPrompt)
                changed += "system_prompt"
            }
        }
        if (params.has("model_id")) {
            val modelId = when (val resolved = resolveModelField(params, settingsStore)) {
                is ModelField.Resolved -> resolved.modelId
                is ModelField.Invalid -> return@Tool errEnv("unknown_model", resolved.message)
            }
            if (modelId != existing.modelId) {
                row = row.copy(modelId = modelId)
                changed += "model_id"
            }
        }
        if (params.has("enabled")) {
            val enabled = params.bool("enabled")
                ?: return@Tool errEnv("invalid_enabled", "enabled must be true or false")
            if (enabled != existing.enabled) {
                row = row.copy(enabled = enabled)
                changed += "enabled"
            }
        }
        if (params.has("slug")) {
            val slugRaw = params.str("slug")
            val slug = AgentNamespace.normalizeSlug(slugRaw)
            if (!slugRaw.isNullOrBlank() && slug == null) {
                return@Tool errEnv(
                    "invalid_slug",
                    "slug must be lowercase ASCII letters, digits and single dashes (max " +
                        "${AgentNamespace.MAX_SLUG_LENGTH}); got \"$slugRaw\"",
                )
            }
            if (slug != existing.slug) {
                row = row.copy(slug = slug)
                changed += "slug"
            }
        }
        if (params.has("token_budget")) {
            val raw = params.long("token_budget")
                ?: return@Tool errEnv("invalid_token_budget", "token_budget must be an integer; pass 0 to clear it")
            val tokenBudget = raw.takeIf { it > 0 }
            if (tokenBudget != existing.tokenBudget) {
                row = row.copy(tokenBudget = tokenBudget)
                changed += "token_budget"
            }
        }

        when (val update = params.fieldUpdate("local_tools")) {
            is FieldUpdate.Unchanged -> {}
            is FieldUpdate.Inherit -> if (existing.localTools != null) {
                row = row.copy(localTools = null)
                changed += "local_tools"
            }
            is FieldUpdate.Own -> {
                val parsed = parseLocalTools(update.value).getOrElse {
                    return@Tool errEnv("invalid_local_tools", it.message ?: "invalid local_tools")
                }
                if (parsed != existing.localTools) {
                    row = row.copy(localTools = parsed)
                    changed += "local_tools"
                }
            }
        }
        when (val update = params.fieldUpdate("disabled_local_tools")) {
            is FieldUpdate.Unchanged -> {}
            is FieldUpdate.Inherit -> if (existing.disabledLocalTools != null) {
                row = row.copy(disabledLocalTools = null)
                changed += "disabled_local_tools"
            }
            is FieldUpdate.Own -> {
                val parsed = parseStringSet(update.value, "disabled_local_tools").getOrElse {
                    return@Tool errEnv("invalid_disabled_local_tools", it.message ?: "invalid disabled_local_tools")
                }
                if (parsed != existing.disabledLocalTools) {
                    row = row.copy(disabledLocalTools = parsed)
                    changed += "disabled_local_tools"
                }
            }
        }
        when (val update = params.fieldUpdate("mcp_servers")) {
            is FieldUpdate.Unchanged -> {}
            is FieldUpdate.Inherit -> if (existing.mcpServers != null) {
                row = row.copy(mcpServers = null)
                changed += "mcp_servers"
            }
            is FieldUpdate.Own -> {
                val parsed = parseMcpServers(update.value, settingsStore.settingsFlow.value.mcpServers).getOrElse {
                    return@Tool errEnv("invalid_mcp_servers", it.message ?: "invalid mcp_servers")
                }
                if (parsed != existing.mcpServers) {
                    row = row.copy(mcpServers = parsed)
                    changed += "mcp_servers"
                }
            }
        }
        when (val update = params.fieldUpdate("skills")) {
            is FieldUpdate.Unchanged -> {}
            is FieldUpdate.Inherit -> if (existing.skills != null) {
                row = row.copy(skills = null)
                changed += "skills"
            }
            is FieldUpdate.Own -> {
                val parsed = parseStringSet(update.value, "skills").getOrElse {
                    return@Tool errEnv("invalid_skills", it.message ?: "invalid skills")
                }
                if (parsed != existing.skills) {
                    row = row.copy(skills = parsed)
                    changed += "skills"
                }
            }
        }

        if (changed.isEmpty()) {
            return@Tool errEnv(
                "no_change",
                "no field would change; pass at least one of name / description / system_prompt / " +
                    "model_id / enabled / slug / token_budget / local_tools / disabled_local_tools / " +
                    "mcp_servers / skills",
            )
        }

        val auditId = openWriteAudit(
            agentRunRepository = agentRunRepository,
            op = "update",
            domainId = existing.id,
            parentRunId = callerContext.callerConversationId,
            extra = buildJsonObject {
                put("name", existing.name)
                put("changed", buildJsonArray { changed.forEach { add(it) } })
            },
        )
        val stored = runCatching {
            repository.update(row)
        }.getOrElse { error ->
            agentRunRepository.markTerminal(auditId, AgentRunStatus.failed, error.message)
            return@Tool errEnv("write_failed", error.message ?: "unknown failure while writing the expert")
        }
        if (stored == null) {
            agentRunRepository.markTerminal(auditId, AgentRunStatus.failed, "the expert was deleted meanwhile")
            return@Tool errEnv("unknown_expert", "the expert was deleted while this update was in flight")
        }
        agentRunRepository.markTerminal(auditId, AgentRunStatus.succeeded)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", true)
            put("audit_run_id", auditId)
            put("changed", buildJsonArray { changed.forEach { add(it) } })
            put("expert", encodeDefinition(stored))
        }.toString()))
    },
)

fun subagentDeleteTool(
    repository: AgentDefinitionRepository,
    agentRunRepository: AgentRunRepository,
    callerContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = EXPERT_DELETE_TOOL_NAME,
    description = """
        Delete a stored expert. Identify it by id or by name (read subagent_list
        kind="experts" first). Runs already dispatched keep going; only the reusable
        definition is removed. Requires user approval; recorded in the run ledger.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "The expert's id, or its name (case-insensitive).")
                })
            },
            required = listOf("id"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        headlessRefusal(callerContext, EXPERT_DELETE_TOOL_NAME)?.let { return@Tool it }
        val params = args.jsonObject

        val target = params.str("id")?.trim().orEmpty()
        if (target.isEmpty()) return@Tool errEnv("invalid_id", "id is required (an expert id or name)")
        val existing = repository.byId(target) ?: repository.byName(target)
            ?: return@Tool errEnv("unknown_expert", "no expert with id or name \"$target\"")

        val auditId = openWriteAudit(
            agentRunRepository = agentRunRepository,
            op = "delete",
            domainId = existing.id,
            parentRunId = callerContext.callerConversationId,
            extra = buildJsonObject { put("name", existing.name) },
        )
        val removed = runCatching {
            repository.delete(existing.id)
        }.getOrElse { error ->
            agentRunRepository.markTerminal(auditId, AgentRunStatus.failed, error.message)
            return@Tool errEnv("write_failed", error.message ?: "unknown failure while deleting the expert")
        }
        if (!removed) {
            agentRunRepository.markTerminal(auditId, AgentRunStatus.failed, "the expert was already gone")
            return@Tool errEnv("unknown_expert", "the expert was already deleted")
        }
        agentRunRepository.markTerminal(auditId, AgentRunStatus.succeeded)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", true)
            put("audit_run_id", auditId)
            put("deleted_id", existing.id)
            put("deleted_name", existing.name)
        }.toString()))
    },
)

/**
 * `model_id` as the store wants it (a canonical `Uuid` string) or nothing.
 *
 * A blank/absent value means "inherit the parent's model" — the same convention
 * [AgentDefinition.modelId] uses — and is deliberately *not* an error, so clearing the model on
 * update is spelled `model_id: ""`. A non-blank value that names no model of an enabled provider
 * stays a loud failure: silently storing an unresolvable id is exactly the #28 bug this codebase
 * already fixed once.
 */
private sealed class ModelField {
    data class Resolved(val modelId: String?) : ModelField()
    data class Invalid(val message: String) : ModelField()
}

private fun resolveModelField(params: JsonObject, settingsStore: SettingsStore): ModelField {
    val raw = params.str("model_id")
    if (raw.isNullOrBlank()) return ModelField.Resolved(null)
    return when (val result = SubAgentModelResolver.resolve(raw, settingsStore.settingsFlow.value.providers)) {
        is SubAgentModelResolver.Result.Resolved -> ModelField.Resolved(result.modelId.toString())
        is SubAgentModelResolver.Result.Failed -> ModelField.Invalid(result.message)
        // Unreachable for a non-blank input, but "inherit" is the only sensible reading if the
        // resolver ever grows a third outcome.
        is SubAgentModelResolver.Result.Inherit -> ModelField.Resolved(null)
    }
}

/**
 * A case-insensitive name collision, or null. [exceptId] excludes the row being edited so an
 * expert can keep its own name through an update. Compares against EVERY definition, enabled or
 * not: a disabled duplicate is still a trap for whoever re-enables it, and the settings screen
 * has always rejected it.
 */
internal fun findNameClash(
    definitions: List<AgentDefinition>,
    name: String,
    exceptId: String?,
): AgentDefinition? = definitions.firstOrNull { definition ->
    definition.id != exceptId &&
        definition.name.isNotBlank() &&
        definition.name.equals(name, ignoreCase = true)
}

/**
 * The refusal a write tool returns when it is reached from a conversation with no approval
 * channel — the same envelope the runtime's
 * [HeadlessToolApprovalPolicy] gate would have produced, so a direct caller that skipped the
 * gate still cannot rewrite the roster from a cron job. Null when the call may proceed.
 */
private fun headlessRefusal(
    callerContext: ToolInvocationContext,
    toolName: String,
): List<UIMessagePart>? {
    if (!callerContext.isHeadless) return null
    val envelope = HeadlessToolApprovalPolicy.refusalEnvelope(toolName)
    return if (envelope != null) {
        listOf(UIMessagePart.Text(envelope))
    } else {
        errEnv(
            HeadlessToolApprovalPolicy.ERROR_CODE,
            "$toolName cannot run in a headless conversation - there is no approval channel.",
        )
    }
}

/**
 * Opens the audit row for one roster write. Metadata only (operation, ids, changed field names) —
 * the ledger never stores the definition's prompt text. Best-effort on the repository's side: a
 * ledger hiccup returns a fallback id and never blocks the write itself.
 */
private suspend fun openWriteAudit(
    agentRunRepository: AgentRunRepository,
    op: String,
    domainId: String,
    parentRunId: String?,
    extra: JsonObject,
): String = agentRunRepository.open(
    kind = AgentRunKind.AgentDefWrite,
    domainId = domainId,
    parentRunId = parentRunId,
    metadata = buildJsonObject {
        put("op", op)
        put("source", "subagent_tool")
        extra.forEach { (key, value) -> put(key, value) }
    },
)

// ---- expert tool-surface fields (P2-36) ------------------------------------------------
//
// subagent_create / subagent_update now accept the four surface fields the store has always
// carried — AgentDefinition.localTools / disabledLocalTools / mcpServers / skills — which the
// parent-facing schema used to omit. Without them the parent could not configure a child's tool
// surface or MCP servers at all: every expert silently inherited the parent's, which is exactly
// the gap the expert library promised to close.
//
// All four share one tri-state convention, matching AgentDefinition's "null = inherit":
//   - key absent       -> leave the field alone (update) / inherit the parent (create),
//   - key is JSON null -> inherit the parent (the only way back from an owned-empty surface),
//   - key is an array  -> the expert's OWN collection ([] = own-empty, and null != []).

private const val LOCAL_TOOLS_DESCRIPTION =
    "The expert's OWN local-tool list, as option ids such as \"time_info\", \"screen_automation\" " +
        "or \"browser\" (the ids subagent_list kind=\"experts\" emits). Omit or pass null to " +
        "inherit the parent assistant's list; an empty array gives the expert no local tools. " +
        "The [{\"type\":\"...\"}] object form is also accepted."

private const val DISABLED_LOCAL_TOOLS_DESCRIPTION =
    "Tool names to disable for this expert (per-tool opt-out). Omit or pass null to inherit the " +
        "parent's; an empty array owns an empty set."

private const val MCP_SERVERS_DESCRIPTION =
    "MCP servers for this expert, each a server id (Uuid) or a server name. Omit or pass null to " +
        "inherit the parent's; an empty array owns an empty set."

private const val SKILLS_DESCRIPTION =
    "Enabled skill names for this expert. Omit or pass null to inherit the parent's; an empty " +
        "array owns an empty set."

private const val LOCAL_TOOLS_UPDATE_DESCRIPTION =
    LOCAL_TOOLS_DESCRIPTION + " Pass null to go back to inheriting the parent's list."

private const val DISABLED_LOCAL_TOOLS_UPDATE_DESCRIPTION =
    DISABLED_LOCAL_TOOLS_DESCRIPTION + " Pass null to inherit the parent's."

private const val MCP_SERVERS_UPDATE_DESCRIPTION =
    MCP_SERVERS_DESCRIPTION + " Pass null to inherit the parent's."

private const val SKILLS_UPDATE_DESCRIPTION =
    SKILLS_DESCRIPTION + " Pass null to inherit the parent's."

/** The schema every surface field shares: an array of strings, plus its description. */
private fun surfaceArraySchema(description: String): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject { put("type", "string") })
    put("description", description)
}

/** See the section comment: absent / null / array are three different intents. */
internal sealed interface FieldUpdate<out T> {
    data object Unchanged : FieldUpdate<Nothing>
    data object Inherit : FieldUpdate<Nothing>
    data class Own<T>(val value: T) : FieldUpdate<T>
}

internal fun JsonObject.fieldUpdate(key: String): FieldUpdate<JsonElement> {
    if (!containsKey(key)) return FieldUpdate.Unchanged
    val element = this[key]
    return if (element == null || element is JsonNull) FieldUpdate.Inherit else FieldUpdate.Own(element)
}

/** Every local-tool id this build understands, sorted, for error messages. */
internal val VALID_LOCAL_TOOL_IDS: List<String> by lazy {
    LocalToolGroups.all.mapNotNull { option ->
        (JsonInstant.encodeToJsonElement(LocalToolOption.serializer(), option) as? JsonObject)
            ?.get("type")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
    }.distinct().sorted()
}

/** A string id ("time_info") or a {"type":"time_info"} object, canonicalised through LocalToolGroups.order. */
internal fun parseLocalTools(element: JsonElement): Result<List<LocalToolOption>> = runCatching {
    val array = element as? JsonArray
        ?: throw IllegalArgumentException("local_tools must be an array of tool ids")
    val resolved = array.map { item ->
        val obj = when (item) {
            is JsonPrimitive -> buildJsonObject { put("type", item.contentOrNull ?: "") }
            is JsonObject -> item
            else -> throw IllegalArgumentException(
                "each local_tools entry must be a string id (e.g. \"time_info\") or a {\"type\":...} object"
            )
        }
        runCatching { JsonInstant.decodeFromJsonElement(LocalToolOption.serializer(), obj) }
            .getOrElse {
                val id = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: item.toString()
                throw IllegalArgumentException(
                    "unknown local tool \"$id\"; valid ids: ${VALID_LOCAL_TOOL_IDS.joinToString(", ")}"
                )
            }
    }
    LocalToolGroups.order(resolved.distinct())
}

/** An array of strings, sorted; anything else fails. */
internal fun parseStringSet(element: JsonElement, field: String): Result<Set<String>> = runCatching {
    val array = element as? JsonArray ?: throw IllegalArgumentException("$field must be an array of strings")
    array.map { item ->
        val primitive = item as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw IllegalArgumentException("every $field entry must be a string")
        }
        primitive.content
    }.toSortedSet()
}

/** MCP servers named by Uuid or by (case-insensitive) server name; anything unresolved fails. */
internal fun parseMcpServers(
    element: JsonElement,
    servers: List<McpServerConfig>,
): Result<Set<String>> = runCatching {
    val array = element as? JsonArray ?: throw IllegalArgumentException("mcp_servers must be an array")
    array.map { item ->
        val primitive = item as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw IllegalArgumentException("every mcp_servers entry must be a string (a server id or name)")
        }
        val raw = primitive.content
        val match = servers.firstOrNull { it.id.toString() == raw }
            ?: servers.firstOrNull { it.commonOptions.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "no MCP server matches \"$raw\"; known servers: " +
                    servers.joinToString(", ") { "${it.commonOptions.name} (${it.id})" }
                        .ifEmpty { "(none configured)" }
            )
        match.id.toString()
    }.toSortedSet()
}

// ---- tiny JSON argument readers --------------------------------------------------------
//
// `JsonObject[key]` being absent and being an explicit `null` are different intents for the
// update tool ("leave it alone" vs "clear it"), so these keep the distinction: `has` answers
// presence, and the typed readers return null for both an absent key and a JSON null.

private fun JsonObject.has(key: String): Boolean = containsKey(key)

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

@Suppress("unused")
private val unusedJsonElementTypeAnchor: JsonElement? = null
