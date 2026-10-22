package me.rerere.rikkahub.data.ai.tools

/**
 * P2-01 — the ONE place a [ToolInvocationContext] gets its field values.
 *
 * ## Why this file exists
 *
 * Before P2-01 the same context was hand-assembled at every tool-assembly site (fast-path
 * router, rerun/regenerate, the normal chat turn, workflow fire, cron direct mode). Each site
 * picked its own subset of the six fields, so a semantic change — T-04's
 * `subAgentContextRefsEnabled`, T-09's `subAgentToolSurfaceEnabled`, the `modelCanSeeImages`
 * fix for `show_image` — had to be applied in five places or the surfaces silently drifted
 * apart. That is exactly the "路径漂移" this refactor removes.
 *
 * The functions below take plain values (no `Assistant` / `Model` dependency) so the field
 * choices are unit-testable on a bare JVM; [me.rerere.rikkahub.data.ai.ToolSurfaceResolver]
 * holds the `Assistant`-flavoured wrappers that call sites actually use.
 *
 * ## The three shapes, and why they differ
 *
 *  - [chat] — an interactive turn, *including* its tool-loop continuation and its rerun
 *    ("regenerate") rebuild. The model is about to be handed these schemas, so every
 *    model-derived flag is set: `modelCanSeeImages` and both sub-agent schema gates.
 *  - [fastPath] — the Phase 16 fast-path router. Its tools are **executed, never shown to a
 *    model** (the router matches an intent and calls the tool directly), so there is no model
 *    to inform and no schema to gate; only the freeze flag is passed through, so that if the
 *    router ever runs against a conversation whose sub-agent surface is frozen it resolves
 *    the same tools the model was offered.
 *  - [headless] — workflow fire / cron direct mode. There is no conversation and no model:
 *    only the calling assistant id and the headless bit (which fires the sub-agent recursion
 *    guard) are known.
 *
 * Callers with no context at all (the legacy [me.rerere.rikkahub.data.ai.tools.LocalTools]
 * `getTools(options)` shape) must pass [ToolInvocationContext.EMPTY] explicitly rather than
 * inventing a new literal here.
 */
object ToolInvocationContexts {

    /**
     * Interactive turn: the model sees these schemas, so all model- and assistant-derived
     * gates are carried.
     */
    fun chat(
        assistantId: String,
        conversationId: String,
        isHeadless: Boolean,
        modelCanSeeImages: Boolean,
        modelCanSeeVideos: Boolean,
        subAgentContextRefsEnabled: Boolean,
        subAgentToolSurfaceEnabled: Boolean,
        appPlaybook: (suspend (fileName: String) -> String?)? = null,
    ): ToolInvocationContext = ToolInvocationContext(
        callerAssistantId = assistantId,
        callerConversationId = conversationId,
        isHeadless = isHeadless,
        modelCanSeeImages = modelCanSeeImages,
        modelCanSeeVideos = modelCanSeeVideos,
        subAgentContextRefsEnabled = subAgentContextRefsEnabled,
        subAgentToolSurfaceEnabled = subAgentToolSurfaceEnabled,
        appPlaybook = appPlaybook,
    )

    /**
     * Fast-path router. Tools are executed, not offered — `isHeadless` stays false (the caller
     * gates headless conversations out before it gets here) and the model-derived fields keep
     * their defaults. The freeze flag is passed so a frozen conversation resolves the frozen
     * surface.
     */
    fun fastPath(
        assistantId: String,
        conversationId: String,
        subAgentToolSurfaceEnabled: Boolean,
    ): ToolInvocationContext = ToolInvocationContext(
        callerAssistantId = assistantId,
        callerConversationId = conversationId,
        isHeadless = false,
        subAgentToolSurfaceEnabled = subAgentToolSurfaceEnabled,
    )

    /**
     * Headless action dispatch (workflow fire / cron direct mode). No conversation exists;
     * `isHeadless = true` is what makes the sub-agent recursion guard fire.
     */
    fun headless(
        assistantId: String,
        conversationId: String? = null,
    ): ToolInvocationContext = ToolInvocationContext(
        callerAssistantId = assistantId,
        callerConversationId = conversationId,
        isHeadless = true,
    )
}
