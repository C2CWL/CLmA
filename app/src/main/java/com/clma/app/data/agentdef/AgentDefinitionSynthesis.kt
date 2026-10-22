package me.rerere.rikkahub.data.agentdef

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Assistant

/**
 * P2-06 — "definition → assistant" adapter (decision D6).
 *
 * A sub-agent run is still built from a real [Assistant] (that is what every assembly site in
 * `ChatService` / `LocalTools` / `McpManager` consumes), so an expert is applied by deriving a
 * *copy* of the parent assistant with the expert's fields overlaid. P2-04 introduced exactly
 * this idea for three fields; this is the same operation with the rest of the expert's
 * surface.
 *
 * Deliberately **not** overridden here, and why:
 *  - `id` — the derived assistant is never persisted or looked up by id. Keeping the parent's
 *    id means a stray `AssistantResolver.byId(...)` still lands on a real, stored assistant
 *    instead of missing and silently falling back to the global one.
 *  - `systemPrompt` — `SubAgentEngine` already prepends the expert's prompt to the task text
 *    (the behaviour since #36). Setting it here as well would deliver it twice.
 *  - `chatModelId` — model precedence is owned by `resolveSubAgentModel` (explicit `model_id`
 *    beats the expert's model, which beats the parent's). Duplicating the rule here would
 *    create a second place to get it wrong.
 *
 * Every `null` field means "inherit the parent", which is what makes a definition that sets
 * nothing at all a no-op — the red line that keeps the pre-P2-04 behaviour byte-for-byte.
 */
fun AgentDefinition.toAssistant(parent: Assistant): Assistant = parent.copy(
    name = name.ifBlank { parent.name },
    localTools = localTools ?: parent.localTools,
    disabledLocalTools = disabledLocalTools ?: parent.disabledLocalTools,
    mcpServers = mcpServers?.let(::parseUuidSet) ?: parent.mcpServers,
    enabledSkills = skills ?: parent.enabledSkills,
    // D9: the expert's private namespace *within the parent's workspace*. `workspaceId` is
    // inherited on purpose — the namespace is a subdirectory, not a separate workspace — and
    // this is inert unless the parent already enabled cold memory (T-06 gates the tools on
    // `coldMemoryEnabled` + a bound workspace + a usable dir).
    coldMemoryDir = AgentNamespace.coldMemoryDirFor(slug) ?: parent.coldMemoryDir,
)

/**
 * Parses stored id strings back into [Uuid]s, dropping any that no longer parse instead of
 * failing the whole surface. A malformed id can only come from hand-edited data, and losing
 * one MCP server is a smaller failure than an expert that cannot be dispatched at all.
 */
internal fun parseUuidSet(raw: Set<String>): Set<Uuid> {
    val out = LinkedHashSet<Uuid>(raw.size)
    for (value in raw) {
        val parsed = runCatching { Uuid.parse(value.trim()) }.getOrNull() ?: continue
        out.add(parsed)
    }
    return out
}
