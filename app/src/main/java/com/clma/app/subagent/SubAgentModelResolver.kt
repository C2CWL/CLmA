package me.rerere.rikkahub.subagent

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.agentdef.AgentDefinition
import kotlin.uuid.Uuid

/**
 * Resolves subagent_dispatch's `model_id` (uuid, provider model id, or display name) against
 * the CHAT-type models of ENABLED providers. #28: `model_id` was parsed, stored and
 * echoed back but never used to pick a model - the sub-agent silently inherited the parent's.
 * That silent fallback is the bug; this resolver fails loudly instead.
 *
 * P2-06b moved this out of `SubAgentEngine.kt` unchanged, together with [resolveSubAgentModel],
 * which is the part whose precedence rule the tests actually pin. Nothing about the resolution
 * changed: the file move is what lets a plain-JVM harness compile the resolver and the
 * precedence function without dragging in `ChatService`, Android `Log`, the Koin graph and the
 * whole engine class around them.
 */
internal object SubAgentModelResolver {
    sealed class Result {
        data object Inherit : Result()
        data class Resolved(val modelId: Uuid) : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * [modelIdInput] null/blank -> [Result.Inherit] (today's behavior unchanged). Otherwise
     * tried in order - uuid exact match, then case-insensitive exact match on [Model.modelId],
     * then case-insensitive exact match on [Model.displayName] - stopping at the first step
     * with any match. Exactly one match at a step resolves; more than one is ambiguous;
     * falling through all three with nothing is unknown. Both failure cases list the
     * candidates as "displayName (providerName) -> uuid" so the caller can retry unambiguously.
     */
    fun resolve(modelIdInput: String?, providers: List<ProviderSetting>): Result {
        if (modelIdInput.isNullOrBlank()) return Result.Inherit

        val chatModels: List<Pair<ProviderSetting, Model>> = providers
            .filter { it.enabled }
            .flatMap { provider -> provider.models.filter { it.type == ModelType.CHAT }.map { provider to it } }

        val asUuid = runCatching { Uuid.parse(modelIdInput) }.getOrNull()
        if (asUuid != null) {
            chatModels.firstOrNull { (_, model) -> model.id == asUuid }
                ?.let { (_, model) -> return Result.Resolved(model.id) }
        }

        val byModelId = chatModels.filter { (_, model) -> model.modelId.equals(modelIdInput, ignoreCase = true) }
        if (byModelId.size == 1) return Result.Resolved(byModelId[0].second.id)
        if (byModelId.size > 1) return Result.Failed(ambiguousMessage(modelIdInput, byModelId))

        val byDisplayName = chatModels.filter { (_, model) -> model.displayName.equals(modelIdInput, ignoreCase = true) }
        if (byDisplayName.size == 1) return Result.Resolved(byDisplayName[0].second.id)
        if (byDisplayName.size > 1) return Result.Failed(ambiguousMessage(modelIdInput, byDisplayName))

        return Result.Failed(unknownMessage(modelIdInput, chatModels))
    }

    private fun candidateLine(candidate: Pair<ProviderSetting, Model>): String {
        val (provider, model) = candidate
        return "${model.displayName} (${provider.name}) -> ${model.id}"
    }

    private fun ambiguousMessage(input: String, matches: List<Pair<ProviderSetting, Model>>): String =
        "model_id \"$input\" matches multiple models - retry with one of these uuids:\n" +
            matches.joinToString("\n") { candidateLine(it) }

    private fun unknownMessage(input: String, available: List<Pair<ProviderSetting, Model>>): String =
        if (available.isEmpty()) {
            "model_id \"$input\" did not match any model, and no chat models are available from enabled providers"
        } else {
            "model_id \"$input\" did not match any model. Available models:\n" +
                available.joinToString("\n") { candidateLine(it) }
        }
}

/**
 * #36, re-homed onto the expert library by P2-06b — combines `model_id`'s resolution with an
 * expert's model. `model_id` always wins when it resolved to something (or failed - a bad
 * explicit model_id must surface, not be papered over by falling back to the expert's model).
 * Only when `model_id` was never given ([SubAgentModelResolver.Result.Inherit]) does the
 * expert's model get a chance, and only if [definition] is non-null and its `modelId` is set -
 * otherwise this is a no-op, exactly preserving the behavior before named experts when no agent
 * was requested.
 *
 * [AgentDefinition.modelId] is the canonical `Uuid` **string** (P2-06a stores ids as TEXT to
 * keep the entity free of experimental uuid types), so it is parsed here. A stored value that no
 * longer parses can only come from hand-edited data; it degrades to inheriting — the same answer
 * an expert with no model at all gives — rather than failing the dispatch, because the
 * alternative is an expert that can never be used over a column the user cannot see.
 *
 * Split out as a pure function (same rationale as [finishSubAgentWait]) so the precedence rule is
 * unit-testable without a live [SubAgentEngine].
 *
 * Later addition — the assistant-level default: when neither `model_id` nor the expert pins a
 * model, the parent assistant's own `subAgentModelId` (the user's standing "run my sub-agents
 * on this cheaper model" setting) gets its turn before the run falls back to inheriting the
 * conversation's model. The field existed in the Assistant model — and was even counted as a
 * reference when deleting a model — but nothing ever read it; wiring it here is what makes the
 * setting real. Null (or an unset field) preserves the pre-existing behavior byte-for-byte.
 */
internal fun resolveSubAgentModel(
    modelResolution: SubAgentModelResolver.Result,
    definition: AgentDefinition?,
    assistantSubAgentModelId: Uuid? = null,
): SubAgentModelResolver.Result = when (modelResolution) {
    is SubAgentModelResolver.Result.Inherit -> {
        val parsed = definition?.modelId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        when {
            parsed != null -> SubAgentModelResolver.Result.Resolved(parsed)
            assistantSubAgentModelId != null ->
                SubAgentModelResolver.Result.Resolved(assistantSubAgentModelId)
            else -> modelResolution
        }
    }
    else -> modelResolution
}
