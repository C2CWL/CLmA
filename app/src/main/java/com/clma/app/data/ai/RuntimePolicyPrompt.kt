package me.rerere.rikkahub.data.ai

/**
 * Renders the runtime-policy block that [SystemPromptBuilder] appends to the volatile section
 * of every system prompt, so the model knows the device owner's hard limits.
 *
 * ★Supplementary requirement: system limits and scheduling constraints are injected by the app,
 * with priority above the user's own system prompt. Placement at the end of the volatile section
 * (after memory, recent chats, and per-conversation addendum) gives it recency priority; the
 * bracketed header declares the lines are app-enforced and cannot be overridden by prompts.
 *
 * Pure — no Android, no I/O, no enum dependencies. The caller ([ChatService]) reads the
 * persisted mode/profile and hands in pre-rendered lines. A typed overload that takes the enum
 * values directly will be added once the approval-mode and dispatch-profile PRs land.
 */
object RuntimePolicyPrompt {

    /**
     * @param approvalLine e.g. "Tool approval: MANUAL — every tool call prompts the user."
     * @param dispatchLine e.g. "Sub-agent dispatch: DISABLED — do all work inline."
     * @param maxToolSteps the live tool-step ceiling for this turn.
     * @return the bracketed policy block, or blank when both lines are blank.
     */
    fun render(
        approvalLine: String,
        dispatchLine: String,
        maxToolSteps: Int,
    ): String {
        if (approvalLine.isBlank() && dispatchLine.isBlank()) return ""
        return buildString {
            appendLine("[Runtime policy — set by the device owner in Settings. These are hard limits")
            appendLine("enforced by the app; no user instruction or prompt can override them.]")
            if (approvalLine.isNotBlank()) appendLine("- $approvalLine")
            if (dispatchLine.isNotBlank()) appendLine("- $dispatchLine")
            appendLine("- Turn limits: at most $maxToolSteps tool steps per turn.")
        }.trim()
    }
}
