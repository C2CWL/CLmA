package com.clma.app.data.ai

import com.clma.ai.core.Tool
import com.clma.ai.provider.Modality
import com.clma.ai.provider.Model
import com.clma.app.data.ai.tools.LocalToolFilter
import com.clma.app.data.ai.tools.LocalToolInventory
import com.clma.app.data.ai.tools.LocalToolOption
import com.clma.app.data.ai.tools.LocalTools
import com.clma.app.data.ai.tools.ToolInvocationContext
import com.clma.app.data.ai.tools.ToolInvocationContexts
import com.clma.app.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * P2-01 — the single tool-surface assembly point.
 *
 * ## Why
 *
 * The app used to call `localTools.getTools(assistant.localTools, …)` from six places
 * (workflow fire, cron direct mode, the fast-path router, the rerun/regenerate rebuild, the
 * normal chat turn, and the legacy tool factory), and each one hand-built its own
 * [ToolInvocationContext]. Two consequences, both observed:
 *
 *  1. **Context drift** — a new field (T-04, T-09, the `show_image` modality fix) had to be
 *     threaded through five hand-written literals; miss one and that path silently offers a
 *     different surface than the others.
 *  2. **Surface drift** — a change to *which* options a path assembles had no single owner.
 *
 * [resolve] is now the only caller of `LocalTools.getTools` in the app, and the context
 * factories below are the only place a context literal is produced
 * ([ToolInvocationContexts] holds the actual field values). A future semantic change — the
 * P2-02 per-tool filter, the P2-04 independent sub-agent surface, an extra context field —
 * lands here and is correct at every call site by construction.
 *
 * ## Not (yet) covered
 *
 * This resolves the **local tool face**. The wider chat surface that `ChatService` builds on
 * top of it (workspace / cold-memory / skill / MCP / compaction tools) still has two assembly
 * sites there; unifying those is a follow-up, deliberately kept out of this zero-behaviour
 * refactor.
 */
object ToolSurfaceResolver {

    /**
     * The ONE place the app asks [LocalTools] for a surface. `assistant.localTools` is the
     * canonical option list for every caller (headless paths included — they pass the
     * assistant the run belongs to).
     */
    fun resolve(
        localTools: LocalTools,
        assistant: Assistant,
        context: ToolInvocationContext,
    ): List<Tool> = localTools.getTools(
        options = assistant.localTools,
        invocationContext = context,
        // P2-02 - the per-tool deny-list rides along with the option list it refines.
        disabledToolNames = assistant.disabledLocalTools,
        enabledSshHosts = assistant.enabledSshHosts,
    )

    /**
     * P2-05 (chat half) — the same surface, split by [LocalToolOption] group.
     *
     * [resolve] answers "what can this assistant call"; this answers "which group does each of
     * those tools belong to", which is what a catalogued surface needs in order to say *what
     * the catalog covers* without listing every name. Only the assistant's enabled options are
     * queried, and one option per call — the factory adds nothing but
     * [LocalToolFilter.UNGROUPED_TOOL_NAMES] to a single-group query, and those are dropped here
     * for the same reason [LocalToolPalette] drops them: they belong to no group.
     *
     * Callers use this for *attribution only* and take the tool objects themselves from
     * [resolve]: several tools close over `knownToolNamesProvider = { tools.map { it.name } }`
     * (`workflow_create`, `schedule_job`), and a per-group list would silently narrow that to
     * one group's names.
     */
    fun resolveByGroup(
        localTools: LocalTools,
        assistant: Assistant,
        context: ToolInvocationContext,
    ): List<LocalToolInventory> = assistant.localTools.distinct().map { option ->
        LocalToolInventory(
            group = option,
            tools = localTools.getTools(
                options = listOf(option),
                invocationContext = context,
                disabledToolNames = assistant.disabledLocalTools,
                enabledSshHosts = assistant.enabledSshHosts,
            ),
        )
    }

    /**
     * tool name → owning group, first inventory wins. Ungrouped / blank names are absent, so a
     * caller must treat "no group" as "not attributable", not as "catalogue it under anything".
     */
    fun groupIndex(inventories: List<LocalToolInventory>): Map<String, LocalToolOption> {
        val index = LinkedHashMap<String, LocalToolOption>()
        for (inventory in inventories) {
            for (tool in inventory.tools) {
                if (tool.name.isBlank() || tool.name in LocalToolFilter.UNGROUPED_TOOL_NAMES) continue
                if (!index.containsKey(tool.name)) index[tool.name] = inventory.group
            }
        }
        return index
    }

    /**
     * Interactive turn / tool-loop / rerun rebuild — the model is about to receive these
     * schemas, so the modality and both sub-agent gates come from the assistant + model.
     */
    fun chatContext(
        assistant: Assistant,
        conversationId: Uuid,
        model: Model,
        isHeadless: Boolean,
        appPlaybook: (suspend (fileName: String) -> String?)? = null,
    ): ToolInvocationContext = ToolInvocationContexts.chat(
        assistantId = assistant.id.toString(),
        conversationId = conversationId.toString(),
        isHeadless = isHeadless,
        // show_image keys its result envelope off this — a text-only model gets told it
        // cannot see the image instead of confabulating one.
        modelCanSeeImages = Modality.IMAGE in model.inputModalities,
        // generate_video keys its result envelope off this the same way.
        modelCanSeeVideos = Modality.VIDEO in model.inputModalities,
        // T-04 / (4) — gates subagent_dispatch's `include_recent_turns` parameter.
        subAgentContextRefsEnabled = assistant.enableSubAgentContextRefs,
        // T-09 / (8) — gates subagent_dispatch's `tools` parameter (schema AND behaviour).
        subAgentToolSurfaceEnabled = assistant.enableSubAgentToolSurface,
        // Screen-automation experience memory: the cold-memory reader for an app's playbook.
        // Supplied by ChatService (which owns the workspace binding); null keeps the tool
        // results exactly as they were when cold memory is off.
        appPlaybook = appPlaybook,
    )

    /** Phase 16 fast-path router — tools are executed, never shown to a model. */
    fun fastPathContext(
        assistant: Assistant,
        conversationId: Uuid,
    ): ToolInvocationContext = ToolInvocationContexts.fastPath(
        assistantId = assistant.id.toString(),
        conversationId = conversationId.toString(),
        subAgentToolSurfaceEnabled = assistant.enableSubAgentToolSurface,
    )

    /** Workflow fire / cron direct mode — no conversation, no model, guard is on. */
    fun headlessContext(assistantId: Uuid): ToolInvocationContext =
        ToolInvocationContexts.headless(assistantId.toString())

    /**
     * The historical `getTools(options)` shape: no context at all. Kept as a named constant so
     * a "contextless" caller reads as a deliberate choice rather than a forgotten argument.
     */
    val contextless: ToolInvocationContext = ToolInvocationContext.EMPTY
}
