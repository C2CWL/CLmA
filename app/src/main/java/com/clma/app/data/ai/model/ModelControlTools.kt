package me.rerere.rikkahub.data.ai.model

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.agentrun.AgentRunStatus
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.Uuid

/**
 * Model-roster management for the assistant.
 *
 * The app already hands the model the expert library (`subagent_*`), MCP servers (`mcp_*`),
 * SSH hosts, workflows and the price table. Model providers and their models were the one
 * roster the assistant could *read* (indirectly, through `usage_get_prices`) but never edit:
 * adding, renaming or removing a model meant opening Settings by hand.
 *
 * This file closes that gap with four tools — `model_list`, `model_add`, `model_update` and
 * `model_delete` — over the same `Settings.providers` the Settings screen writes, so a change
 * made by the assistant shows up in Settings immediately and vice versa.
 *
 * Scope is deliberately the **model** entry, not the provider. Provider rows carry the apiKey
 * and baseUrl (secrets); this family never touches them, and an unknown or built-in synthetic
 * provider (AICore) is refused rather than half-edited.
 *
 * The write half requires approval ([me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults])
 * and is refused outright in a headless run
 * ([me.rerere.rikkahub.data.ai.tools.HeadlessToolApprovalPolicy]); each attempt is recorded in
 * the `agent_runs` ledger under [AgentRunKind.ModelWrite].
 */

private fun textPart(payload: JsonObject): List<UIMessagePart> =
    listOf(UIMessagePart.Text(payload.toString()))

private fun errorPart(code: String, detail: String): List<UIMessagePart> = textPart(
    buildJsonObject {
        put("ok", false)
        put("error", code)
        put("detail", detail)
    }
)

/** Wire name of a provider kind, so the model can round-trip a selector it was shown. */
internal fun providerKind(provider: ProviderSetting): String = when (provider) {
    is ProviderSetting.OpenAI -> "openai"
    is ProviderSetting.Google -> "google"
    is ProviderSetting.Claude -> "claude"
    is ProviderSetting.AICore -> "aicore"
    is ProviderSetting.LiteRtLocal -> "local_litert"
    is ProviderSetting.LlamaCppLocal -> "local_llamacpp"
    is ProviderSetting.Codex -> "codex"
    is ProviderSetting.Grok -> "grok"
    is ProviderSetting.GeminiOAuth -> "gemini_oauth"
}

internal fun modelTypeWire(type: ModelType): String = when (type) {
    ModelType.CHAT -> "chat"
    ModelType.IMAGE -> "image"
    ModelType.VIDEO -> "video"
    ModelType.EMBEDDING -> "embedding"
}

/** Lenient parse of a model-type argument; null when the token is not one we know. */
internal fun parseModelType(raw: String): ModelType? = when (raw.trim().lowercase()) {
    "chat" -> ModelType.CHAT
    "image" -> ModelType.IMAGE
    "video" -> ModelType.VIDEO
    "embedding", "embed" -> ModelType.EMBEDDING
    else -> null
}

private fun abilityWire(ability: ModelAbility): String = when (ability) {
    ModelAbility.TOOL -> "tool"
    ModelAbility.REASONING -> "reasoning"
}

/** null = the element is not a valid abilities array (the caller decides how to report it). */
private fun abilitiesFrom(element: JsonElement): List<ModelAbility>? {
    val array = element as? JsonArray ?: return null
    val out = mutableListOf<ModelAbility>()
    array.forEach { item ->
        val raw = (item as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: return null
        val ability = when (raw) {
            "tool" -> ModelAbility.TOOL
            "reasoning" -> ModelAbility.REASONING
            else -> return null
        }
        if (ability !in out) out += ability
    }
    return out
}

/**
 * Resolve a provider selector: an exact UUID wins, otherwise a case-insensitive name match.
 * Returns null when nothing matches, so callers emit a structured `unknown_provider`.
 */
internal fun findProviderForControl(
    providers: List<ProviderSetting>,
    selector: String,
): ProviderSetting? {
    val needle = selector.trim()
    if (needle.isEmpty()) return null
    runCatching { Uuid.parse(needle) }.getOrNull()?.let { id ->
        providers.firstOrNull { it.id == id }?.let { return it }
    }
    return providers.firstOrNull { it.name.equals(needle, ignoreCase = true) }
}

/**
 * Resolve a model selector inside [provider]: an exact model UUID wins, otherwise a
 * case-insensitive `model_id` match. Null when nothing matches.
 */
internal fun findModelForControl(provider: ProviderSetting, selector: String): Model? {
    val needle = selector.trim()
    if (needle.isEmpty()) return null
    runCatching { Uuid.parse(needle) }.getOrNull()?.let { id ->
        provider.models.firstOrNull { it.id == id }?.let { return it }
    }
    return provider.models.firstOrNull { it.modelId.equals(needle, ignoreCase = true) }
}

private fun modelToJson(model: Model): JsonObject = buildJsonObject {
    put("id", model.id.toString())
    put("model_id", model.modelId)
    put("display_name", model.displayName)
    put("type", modelTypeWire(model.type))
    putJsonArray("abilities") { model.abilities.forEach { add(abilityWire(it)) } }
}

private fun providerToJson(provider: ProviderSetting, models: List<Model>): JsonObject = buildJsonObject {
    put("id", provider.id.toString())
    put("name", provider.name)
    put("kind", providerKind(provider))
    put("enabled", provider.enabled)
    put("built_in", provider.builtIn)
    put("models", buildJsonArray { models.forEach { add(modelToJson(it)) } })
}

/**
 * `model_list` — the read half. No approval: it reads settings and returns text, and knowing
 * which models exist is a precondition for every write below.
 */
fun modelListTool(settingsStore: SettingsStore): Tool = Tool(
    name = "model_list",
    description = (
        "List the model providers and the models configured in the app: each provider's id, name, " +
            "kind, enabled flag and, per model, its id, model_id, display_name, type and abilities. " +
            "Optionally narrow to one provider (by name or id) or one model type. Read-only, so it " +
            "needs no approval."
        ),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("provider") {
                    put("type", "string")
                    put("description", "Optional: provider name or UUID. Omit to list every provider.")
                }
                putJsonObject("type") {
                    put("type", "string")
                    put("description", "Optional model-type filter: chat, image, video or embedding.")
                }
            }
        )
    },
    execute = { arguments ->
        val args = arguments as? JsonObject ?: JsonObject(emptyMap())
        val providerSelector = args["provider"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val typeRaw = args["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val typeFilter = if (typeRaw.isEmpty()) null else parseModelType(typeRaw)
        if (typeRaw.isNotEmpty() && typeFilter == null) {
            return@Tool errorPart("invalid_type", "type must be one of: chat, image, video, embedding")
        }

        val settings = settingsStore.settingsFlow.first()
        val providers = if (providerSelector.isEmpty()) {
            settings.providers
        } else {
            val match = findProviderForControl(settings.providers, providerSelector)
                ?: return@Tool errorPart(
                    "unknown_provider",
                    "no provider matches '$providerSelector'. Call model_list with no arguments to see every provider.",
                )
            listOf(match)
        }

        var modelCount = 0
        val providerArray = buildJsonArray {
            providers.forEach { provider ->
                val models = provider.models.filter { typeFilter == null || it.type == typeFilter }
                modelCount += models.size
                add(providerToJson(provider, models))
            }
        }
        textPart(
            buildJsonObject {
                put("ok", true)
                put("providers", providerArray)
                put("provider_count", providers.size)
                put("model_count", modelCount)
            }
        )
    },
)

/**
 * `model_add` — add one model under an existing provider. Written through `SettingsStore` under
 * a fresh audit row, so a rejected write leaves the roster exactly as it was.
 */
fun modelAddTool(
    settingsStore: SettingsStore,
    agentRunRepository: AgentRunRepository,
): Tool = Tool(
    name = "model_add",
    description = (
        "Add a model to an existing provider. Choose the provider (by name or id, see model_list) and " +
            "the API model_id the provider expects; the display name defaults to the model id. Writes " +
            "the app settings, so the new model becomes selectable immediately. Requires user approval " +
            "and is refused in a headless run. A model_id the provider already has is rejected as a " +
            "duplicate — use model_update for that one."
        ),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("provider") {
                    put("type", "string")
                    put("description", "Provider name or UUID to add the model to (see model_list).")
                }
                putJsonObject("model_id") {
                    put("type", "string")
                    put("description", "The API model id the provider expects, for example deepseek-chat or gpt-4o.")
                }
                putJsonObject("display_name") {
                    put("type", "string")
                    put("description", "Label shown in the app. Defaults to model_id.")
                }
                putJsonObject("type") {
                    put("type", "string")
                    put("description", "chat (the default), image, video or embedding.")
                }
                putJsonObject("abilities") {
                    put("type", "array")
                    put("description", "Capabilities this model has: the strings tool and/or reasoning. Omit for none.")
                    putJsonObject("items") { put("type", "string") }
                }
            },
            required = listOf("provider", "model_id"),
        )
    },
    execute = { arguments ->
        val args = arguments as? JsonObject ?: JsonObject(emptyMap())
        val providerSelector = args["provider"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (providerSelector.isEmpty()) return@Tool errorPart("missing_provider", "provider is required")
        val modelId = args["model_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (modelId.isEmpty()) return@Tool errorPart("missing_model_id", "model_id is required")

        val typeRaw = args["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val type = if (typeRaw.isEmpty()) ModelType.CHAT else parseModelType(typeRaw)
        if (type == null) return@Tool errorPart("invalid_type", "type must be one of: chat, image, video, embedding")

        val abilitiesElement = args["abilities"]
        val abilities = if (abilitiesElement == null) {
            emptyList()
        } else {
            abilitiesFrom(abilitiesElement)
                ?: return@Tool errorPart("invalid_abilities", "abilities must be an array of the strings: tool, reasoning")
        }
        val displayName = args["display_name"]?.jsonPrimitive?.contentOrNull?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: modelId

        val settings = settingsStore.settingsFlow.first()
        val provider = findProviderForControl(settings.providers, providerSelector)
            ?: return@Tool errorPart("unknown_provider", "no provider matches '$providerSelector'; call model_list first")
        if (provider is ProviderSetting.AICore) {
            return@Tool errorPart("provider_read_only", "the AICore provider synthesizes its models on the device and cannot be edited")
        }
        if (provider.models.any { it.modelId.equals(modelId, ignoreCase = true) }) {
            return@Tool errorPart(
                "duplicate_model",
                "provider '${provider.name}' already has a model with model_id '$modelId'; use model_update to change it",
            )
        }

        val created = Model(modelId = modelId, displayName = displayName, type = type, abilities = abilities)
        val auditId = agentRunRepository.open(
            kind = AgentRunKind.ModelWrite,
            domainId = "${provider.id}:${created.id}",
            metadata = buildJsonObject {
                put("op", "add")
                put("source", "model_tool")
                put("provider_id", provider.id.toString())
                put("provider_name", provider.name)
                put("model_id", modelId)
                put("model_uuid", created.id.toString())
            },
        )
        try {
            settingsStore.update { current ->
                current.copy(
                    providers = current.providers.map { existing ->
                        if (existing.id == provider.id) existing.addModel(created) else existing
                    }
                )
            }
        } catch (error: Throwable) {
            agentRunRepository.markTerminal(auditId, AgentRunStatus.failed, error.message)
            return@Tool errorPart("write_failed", error.message ?: "unknown failure while writing settings")
        }
        agentRunRepository.markTerminal(auditId, AgentRunStatus.succeeded)
        textPart(
            buildJsonObject {
                put("ok", true)
                put("provider_id", provider.id.toString())
                put("provider_name", provider.name)
                put("model", modelToJson(created))
                put("audit_run_id", auditId)
            }
        )
    },
)

/** null for absent or blank; used to read the optional update fields. */
private fun trimmedOrNull(element: JsonElement?): String? =
    element?.jsonPrimitive?.contentOrNull?.trim()?.takeUnless { it.isEmpty() }

/**
 * `model_update` — change the fields of one model. Partial: only the keys you pass are touched.
 * A no-op (nothing actually changed) is reported without an audit row.
 */
fun modelUpdateTool(
    settingsStore: SettingsStore,
    agentRunRepository: AgentRunRepository,
): Tool = Tool(
    name = "model_update",
    description = (
        "Change one model inside a provider. Identify it by provider (name or id) and by the model's " +
            "UUID or model_id, then pass only the fields to change: model_id, display_name, type " +
            "(chat / image / video / embedding) or abilities (the strings tool and/or reasoning). Requires user " +
            "approval and is refused in a headless run."
        ),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("provider") {
                    put("type", "string")
                    put("description", "Provider name or UUID the model lives under (see model_list).")
                }
                putJsonObject("model") {
                    put("type", "string")
                    put("description", "The model to change: its UUID (see model_list) or its model_id.")
                }
                putJsonObject("model_id") {
                    put("type", "string")
                    put("description", "New API model id. Omit to keep the current one.")
                }
                putJsonObject("display_name") {
                    put("type", "string")
                    put("description", "New label shown in the app. Omit to keep the current one.")
                }
                putJsonObject("type") {
                    put("type", "string")
                    put("description", "chat, image, video or embedding. Omit to keep the current one.")
                }
                putJsonObject("abilities") {
                    put("type", "array")
                    put("description", "Replacement capabilities list: the strings tool and/or reasoning. Omit to keep the current one.")
                    putJsonObject("items") { put("type", "string") }
                }
            },
            required = listOf("provider", "model"),
        )
    },
    execute = { arguments ->
        val args = arguments as? JsonObject ?: JsonObject(emptyMap())
        val providerSelector = args["provider"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (providerSelector.isEmpty()) return@Tool errorPart("missing_provider", "provider is required")
        val modelSelector = args["model"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (modelSelector.isEmpty()) return@Tool errorPart("missing_model", "model is required")

        val hasModelId = args.containsKey("model_id")
        val hasDisplayName = args.containsKey("display_name")
        val hasType = args.containsKey("type")
        val hasAbilities = args.containsKey("abilities")
        if (!hasModelId && !hasDisplayName && !hasType && !hasAbilities) {
            return@Tool errorPart("nothing_to_update", "pass at least one of: model_id, display_name, type, abilities")
        }

        val newModelId = if (!hasModelId) null else (
            trimmedOrNull(args["model_id"])
                ?: return@Tool errorPart("invalid_model_id", "model_id must be a non-blank string")
            )
        val newDisplayName = if (!hasDisplayName) null else (
            trimmedOrNull(args["display_name"])
                ?: return@Tool errorPart("invalid_display_name", "display_name must be a non-blank string")
            )
        val newType = if (!hasType) null else (
            parseModelType(args["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty())
                ?: return@Tool errorPart("invalid_type", "type must be one of: chat, image, video, embedding")
            )
        val newAbilities = if (!hasAbilities) null else (
            abilitiesFrom(args["abilities"]!!)
                ?: return@Tool errorPart("invalid_abilities", "abilities must be an array of the strings: tool, reasoning")
            )

        val settings = settingsStore.settingsFlow.first()
        val provider = findProviderForControl(settings.providers, providerSelector)
            ?: return@Tool errorPart("unknown_provider", "no provider matches '$providerSelector'; call model_list first")
        if (provider is ProviderSetting.AICore) {
            return@Tool errorPart("provider_read_only", "the AICore provider synthesizes its models on the device and cannot be edited")
        }
        val model = findModelForControl(provider, modelSelector)
            ?: return@Tool errorPart("unknown_model", "provider '${provider.name}' has no model matching '$modelSelector'")
        if (newModelId != null && !newModelId.equals(model.modelId, ignoreCase = true) &&
            provider.models.any { it.id != model.id && it.modelId.equals(newModelId, ignoreCase = true) }
        ) {
            return@Tool errorPart("duplicate_model", "provider '${provider.name}' already has a model with model_id '$newModelId'")
        }

        val updated = model.copy(
            modelId = newModelId ?: model.modelId,
            displayName = newDisplayName ?: model.displayName,
            type = newType ?: model.type,
            abilities = newAbilities ?: model.abilities,
        )
        val changedFields = buildJsonArray {
            if (newModelId != null && newModelId != model.modelId) add("model_id")
            if (newDisplayName != null && newDisplayName != model.displayName) add("display_name")
            if (newType != null && newType != model.type) add("type")
            if (newAbilities != null && newAbilities != model.abilities) add("abilities")
        }
        if (updated == model) {
            return@Tool textPart(
                buildJsonObject {
                    put("ok", true)
                    put("changed", false)
                    put("provider_id", provider.id.toString())
                    put("provider_name", provider.name)
                    put("model", modelToJson(model))
                }
            )
        }

        val auditId = agentRunRepository.open(
            kind = AgentRunKind.ModelWrite,
            domainId = "${provider.id}:${model.id}",
            metadata = buildJsonObject {
                put("op", "update")
                put("source", "model_tool")
                put("provider_id", provider.id.toString())
                put("provider_name", provider.name)
                put("model_id", model.modelId)
                put("model_uuid", model.id.toString())
                put("changed_fields", changedFields)
            },
        )
        try {
            settingsStore.update { current ->
                current.copy(
                    providers = current.providers.map { existing ->
                        if (existing.id == provider.id) existing.editModel(updated) else existing
                    }
                )
            }
        } catch (error: Throwable) {
            agentRunRepository.markTerminal(auditId, AgentRunStatus.failed, error.message)
            return@Tool errorPart("write_failed", error.message ?: "unknown failure while writing settings")
        }
        agentRunRepository.markTerminal(auditId, AgentRunStatus.succeeded)
        textPart(
            buildJsonObject {
                put("ok", true)
                put("changed", true)
                put("provider_id", provider.id.toString())
                put("provider_name", provider.name)
                put("model", modelToJson(updated))
                put("changed_fields", changedFields)
                put("audit_run_id", auditId)
            }
        )
    },
)

/**
 * `model_delete` — remove one model from one provider. Reports how many assistants currently
 * point at it, so a delete that orphans a chat model is visible in the result rather than silent.
 */
fun modelDeleteTool(
    settingsStore: SettingsStore,
    agentRunRepository: AgentRunRepository,
): Tool = Tool(
    name = "model_delete",
    description = (
        "Remove one model from a provider. Identify it by provider (name or id) and by the model's " +
            "UUID or model_id. The result reports how many assistants reference the model so an " +
            "accidental delete is visible. Requires user approval and is refused in a headless run."
        ),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("provider") {
                    put("type", "string")
                    put("description", "Provider name or UUID the model lives under (see model_list).")
                }
                putJsonObject("model") {
                    put("type", "string")
                    put("description", "The model to delete: its UUID (see model_list) or its model_id.")
                }
            },
            required = listOf("provider", "model"),
        )
    },
    execute = { arguments ->
        val args = arguments as? JsonObject ?: JsonObject(emptyMap())
        val providerSelector = args["provider"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (providerSelector.isEmpty()) return@Tool errorPart("missing_provider", "provider is required")
        val modelSelector = args["model"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (modelSelector.isEmpty()) return@Tool errorPart("missing_model", "model is required")

        val settings = settingsStore.settingsFlow.first()
        val provider = findProviderForControl(settings.providers, providerSelector)
            ?: return@Tool errorPart("unknown_provider", "no provider matches '$providerSelector'; call model_list first")
        if (provider is ProviderSetting.AICore) {
            return@Tool errorPart("provider_read_only", "the AICore provider synthesizes its models on the device and cannot be edited")
        }
        val model = findModelForControl(provider, modelSelector)
            ?: return@Tool errorPart("unknown_model", "provider '${provider.name}' has no model matching '$modelSelector'")
        val referencing = settings.assistants.count { it.chatModelId == model.id || it.subAgentModelId == model.id }

        val auditId = agentRunRepository.open(
            kind = AgentRunKind.ModelWrite,
            domainId = "${provider.id}:${model.id}",
            metadata = buildJsonObject {
                put("op", "delete")
                put("source", "model_tool")
                put("provider_id", provider.id.toString())
                put("provider_name", provider.name)
                put("model_id", model.modelId)
                put("model_uuid", model.id.toString())
                put("assistants_referencing", referencing)
            },
        )
        try {
            settingsStore.update { current ->
                current.copy(
                    providers = current.providers.map { existing ->
                        if (existing.id == provider.id) existing.delModel(model) else existing
                    }
                )
            }
        } catch (error: Throwable) {
            agentRunRepository.markTerminal(auditId, AgentRunStatus.failed, error.message)
            return@Tool errorPart("write_failed", error.message ?: "unknown failure while writing settings")
        }
        agentRunRepository.markTerminal(auditId, AgentRunStatus.succeeded)
        textPart(
            buildJsonObject {
                put("ok", true)
                put("provider_id", provider.id.toString())
                put("provider_name", provider.name)
                put("removed", modelToJson(model))
                put("assistants_referencing", referencing)
                put("audit_run_id", auditId)
            }
        )
    },
)
