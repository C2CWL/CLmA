package me.rerere.rikkahub.data.ai.tools

/**
 * Phase 17 stability — context every tool factory in [LocalTools.getTools] sees about WHO
 * is invoking it. Until this layer existed, tools that needed to know the calling
 * conversation / assistant id (sub-agent recursion guard, workflow_create authoring-id
 * persistence) had no way to read it — both shipped with placeholder defaults and silent
 * gaps the audit caught.
 *
 * Convention: every getTools() caller MUST construct a ToolInvocationContext with the most
 * specific data it has. The default ([EMPTY]) is a no-op safe fallback used when the
 * caller doesn't track the data (legacy / one-off paths) — but factories should treat the
 * empty context as "I don't know" not "no constraints", and apply conservative defaults.
 *
 * Fields:
 *  - [callerAssistantId]: the assistant whose toggles are being dispatched. ChatService
 *    knows this from `settings.getCurrentAssistant().id`. Cron / workflow / sub-agent
 *    paths know it from their respective entity's assistant id.
 *  - [callerConversationId]: the conversation-uuid of the user-facing chat (interactive)
 *    or the headless conversation (cron / workflow / sub-agent / external-automation).
 *  - [isHeadless]: true when the dispatch is happening from a system flow rather than the
 *    user typing in a chat. Sub-agents, cron jobs, workflows, and external-automation
 *    runs all set this to true so the recursion guard fires.
 *  - [modelCanSeeImages]: true iff the model handling this turn has image input in its
 *    modalities. `show_image` reads this so a text-only model is told plainly it cannot
 *    see the picture (and must OCR / file-process it) instead of being handed dimensions
 *    that read like "I looked at it" — the root cause of confabulated image descriptions.
 *    Defaults to `true`: the no-knowledge fallback preserves the pre-fix behaviour, and
 *    ChatService (the only LLM-driven dispatch path) always sets it explicitly.
 */
data class ToolInvocationContext(
    val callerAssistantId: String? = null,
    val callerConversationId: String? = null,
    val isHeadless: Boolean = false,
    val modelCanSeeImages: Boolean = true,
    /**
     * P2-33b — true iff the model handling this turn has video input in its modalities.
     * `generate_video` reads this so a model that cannot watch the clip is told plainly that the
     * result is displayed to the user but invisible to it, rather than being handed byte counts
     * that read like \"I watched it\" (the same anti-confabulation guard [modelCanSeeImages] gives
     * `show_image`). Defaults to `true` to mirror [modelCanSeeImages]; the only model-derived
     * dispatch path (ChatService -> ToolSurfaceResolver.chatContext) always sets it explicitly.
     */
    val modelCanSeeVideos: Boolean = true,
    /**
     * T-04 / (4): true when the calling assistant has `enableSubAgentContextRefs` on.
     * `subagent_dispatch` reads this to decide whether to OFFER its `include_recent_turns`
     * parameter at all - the flag gates the tool SCHEMA, not just the behaviour, so an
     * assistant with the feature off sends a byte-identical `subagent_dispatch` definition
     * to the one it sent before T-04 (prompt-cache safe).
     *
     * Defaults to false: the empty / legacy context keeps the pre-T-04 surface, and
     * ChatService is the only caller that can turn it on.
     */
    val subAgentContextRefsEnabled: Boolean = false,
    /**
     * T-09 / (8): true when the calling assistant has `enableSubAgentToolSurface` on.
     * `subagent_dispatch` reads this to decide whether to DESCRIBE its `tools` parameter at all
     * and whether to honour it - the flag gates the tool SCHEMA, not just the behaviour, so an
     * assistant with the feature off sends a byte-identical `subagent_dispatch` definition to the
     * one it sent before T-09 (prompt-cache safe). The engine reads the same Assistant field for
     * the freeze itself, so the two can never disagree.
     *
     * Defaults to false: the empty / legacy context keeps the pre-T-09 surface, and ChatService is
     * the only caller that can turn it on.
     */
    val subAgentToolSurfaceEnabled: Boolean = false,
    /**
     * Screen-automation experience memory: reads the cold-memory playbook document for an
     * Android package (see `AppPlaybookRules.fileNameFor`), or `null` when there is none.
     *
     * `launch_app` / `read_window_tree` call it to surface the stored note for the app they just
     * brought up, so the agent reuses what it learned last time instead of rediscovering it. The
     * lambda is supplied by `ChatService`, which owns the cold-memory workspace binding; it is
     * `null` on every other path (and when cold memory is off), which makes the tool results
     * byte-identical to what they were before this field existed.
     */
    val appPlaybook: (suspend (fileName: String) -> String?)? = null,
) {
    companion object {
        /** No-knowledge fallback. Factories that depend on context MUST handle this. */
        val EMPTY = ToolInvocationContext()
    }
}
