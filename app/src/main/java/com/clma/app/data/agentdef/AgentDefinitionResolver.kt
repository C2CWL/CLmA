package me.rerere.rikkahub.data.agentdef

/**
 * P2-06 — resolves `subagent_dispatch`'s `agent` argument (an [AgentDefinition] name) against
 * the stored expert library.
 *
 * This is a direct port of the `SubAgentProfileResolver` it replaces, with the same contract
 * and the same failure messages, because the behaviour it encodes was the subject of #36 and
 * #28: no input is not an error (nothing was requested), while an unknown or disabled name
 * **fails loudly** listing the valid names rather than silently falling back to the parent's
 * model — the silent-inheritance bug `model_id` had before #28.
 *
 * Pure on purpose (no DAO, no coroutine): the precedence rule is the part worth testing, and
 * the repository is what supplies the list.
 */
object AgentDefinitionResolver {

    sealed class Result {
        data object NotRequested : Result()
        data class Resolved(val definition: AgentDefinition) : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * Definitions eligible for dispatch or for listing in `subagent_dispatch`'s description —
     * a disabled definition is neither resolvable nor discoverable, so it behaves exactly as
     * if it did not exist. Shared by [resolve] and the tool description so there is a single
     * definition of "eligible" to test.
     */
    fun enabledDefinitions(definitions: List<AgentDefinition>): List<AgentDefinition> =
        definitions.filter { it.enabled }

    /**
     * [agentName] null/blank -> [Result.NotRequested]. Otherwise matched case-insensitively by
     * [AgentDefinition.name] against only the ENABLED definitions. More than one enabled
     * definition sharing a name (case-insensitively) is ambiguous and fails loudly naming the
     * duplicate: a sub-agent must never silently run on whichever of two same-named experts
     * happened to come first.
     */
    fun resolve(agentName: String?, definitions: List<AgentDefinition>): Result {
        if (agentName.isNullOrBlank()) return Result.NotRequested

        val enabled = enabledDefinitions(definitions)
        val matches = enabled.filter { it.name.equals(agentName, ignoreCase = true) }
        if (matches.size > 1) {
            return Result.Failed(
                "agent \"$agentName\" matches multiple experts - rename one of these " +
                    "duplicates: " + matches.joinToString(", ") { it.name }
            )
        }
        val match = matches.firstOrNull()
        if (match != null) return Result.Resolved(match)

        return Result.Failed(
            if (enabled.isEmpty()) {
                "agent \"$agentName\" did not match any expert, and no experts are configured"
            } else {
                "agent \"$agentName\" did not match any enabled expert. Available: " +
                    enabled.joinToString(", ") { it.name }
            }
        )
    }
}
