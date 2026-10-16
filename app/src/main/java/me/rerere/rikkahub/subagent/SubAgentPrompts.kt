package me.rerere.rikkahub.subagent

/**
 * Assemble the system-prompt addendum for one sub-agent run, from the three layers that
 * may define one (lowest precedence first):
 *
 *   1. the parent assistant's `subAgentSystemPrompt` — the user's standing instruction for
 *      every sub-agent this assistant dispatches; falls back to
 *      [SubAgentDefaults.DEFAULT_SYSTEM_PROMPT] when blank, so a run always carries the
 *      scoping discipline the constant was written for (it was dead code until now);
 *   2. the dispatched expert's `systemPrompt` (when `agent` names one);
 *   3. the dispatch call's own `system_prompt` (per-run, highest precedence — read here for
 *      the first time; the parameter used to be parsed into [SubAgentRequest] and dropped).
 *
 * The layers are joined, not replaced: a later layer refines the earlier ones rather than
 * switching them off, so an expert prompt never silently discards the assistant's standing
 * rules. The result goes into the run's system prompt via
 * [me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum] instead of being prepended
 * to the task text — the archived sub-agent conversation keeps a clean first user message,
 * and the vector index never embeds the scaffolding.
 *
 * Pure: no Android, no I/O — unit-tested on the JVM.
 */
internal fun composeSubAgentSystemPrompt(
    assistantPrompt: String,
    expertPrompt: String?,
    requestPrompt: String?,
): String = listOfNotNull(
    assistantPrompt.trim().ifBlank { SubAgentDefaults.DEFAULT_SYSTEM_PROMPT.trim() },
    expertPrompt?.trim()?.takeIf { it.isNotEmpty() },
    requestPrompt?.trim()?.takeIf { it.isNotEmpty() },
).joinToString("\n\n")
