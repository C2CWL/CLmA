package me.rerere.rikkahub.data.ai.tools

/**
 * The runtime tool-approval decision, factored out of `ChatService.isToolAutoApproved` so the
 * whole policy is pure and unit-testable on a bare JVM (see `ToolApprovalPolicyTest`).
 *
 * Ordering, top to bottom — first match wins:
 *
 *  1. `ask_user` is a human-input request, not a permission gate: it must pause for the user
 *     wherever there is a surface to ask on, and auto-approve only in a headless run (nobody
 *     to answer, so it falls through to its own "unavailable" envelope).
 *  2. **YOLO** ("I AM STUPID") is the user's explicit blanket consent. It sits above the
 *     blacklist *and* the hard floor. The command floor ([HardlineCommandGuard]) still applies.
 *  3. A **headless** conversation (cron / sub-agent / workflow / external automation) has no
 *     approval channel: asking would hang the turn forever, so everything auto-approves here.
 *     The tools that must never run unattended are refused separately, as an error envelope,
 *     by [HeadlessToolApprovalPolicy].
 *  4. The **hard floor** ([ToolApprovalDefaults.isHardFloor]) — the privilege-escalation set
 *     and every opaque `mcp__*` tool — asks on every call; it is not waivable via the blacklist.
 *  5. An in-memory **"allow for this chat"** grant suppresses the prompt for this conversation.
 *  6. Otherwise the tool auto-approves **unless it is on the user's blacklist**.
 */
object ToolApprovalPolicy {

    /** The one local tool that is a request for input rather than a capability. */
    const val ASK_USER_TOOL_NAME: String = "ask_user"

    /**
     * True when [toolName] may run without prompting.
     *
     * Pure: every input is a parameter, so callers own the (async) lookups and the function
     * itself never touches Android, DataStore or globals beyond the constant floor.
     */
    fun shouldAutoApprove(
        toolName: String,
        blacklist: Set<String>,
        yolo: Boolean,
        headless: Boolean,
        allowedForChat: Boolean,
        hardFloor: Set<String> = ToolApprovalDefaults.NO_ALWAYS_ALLOW,
        isMcpRelayed: Boolean = toolName.startsWith(ToolApprovalDefaults.MCP_TOOL_PREFIX),
    ): Boolean {
        if (toolName == ASK_USER_TOOL_NAME) return headless
        if (yolo) return true
        if (headless) return true
        if (isMcpRelayed || toolName in hardFloor) return false
        if (allowedForChat) return true
        return toolName !in blacklist
    }
}
