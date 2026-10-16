package me.rerere.rikkahub.data.ai.tools

/**
 * The three posture levels the device owner picks in Settings → Tool approvals.
 *
 * Replaces the old two-knob (blacklist + YOLO) scheme with a single semantic selector so
 * the user says "how much friction do I want" instead of maintaining a per-tool list and a
 * blanket override separately.
 *
 * - [AUTO]: no friction. Side-effecting tools run without prompting — except the
 *   non-waivable floor ([ToolApprovalDefaults.NO_ALWAYS_ALLOW] + `mcp__*`), which still
 *   asks on every call. ★This differs from the retired YOLO toggle, which sat ABOVE the
 *   floor and suppressed it too. Auto is "stop asking me about ordinary tools", not
 *   "stop asking me about privilege-escalation surfaces".
 * - [SEMI]: the blacklist decides. A tool on the blacklist asks; everything else
 *   auto-approves. This is the fine-grained mode — the user curates which specific tools
 *   need a confirmation.
 * - [MANUAL]: every tool call asks. The only exceptions are the in-memory "allow for this
 *   chat" grant (so a long agent session isn't drowned in prompts after the first OK) and
 *   `ask_user` in a headless run (nobody to answer). The floor obviously still asks too.
 *
 * Ordering, top to bottom — first match wins (★拍板①: the floor is checked BEFORE the
 * mode, so Auto cannot waive it):
 *
 *  1. `ask_user` is a human-input request, not a permission gate: it must pause for the user
 *     wherever there is a surface to ask on, and auto-approve only in a headless run.
 *  2. A **headless** conversation (cron / sub-agent / workflow / external automation) has no
 *     approval channel: asking would hang the turn forever, so everything auto-approves.
 *     The tools that must never run unattended are refused separately as an error envelope
 *     by [HeadlessToolApprovalPolicy].
 *  3. The **hard floor** ([ToolApprovalDefaults.isHardFloor]) — the privilege-escalation set
 *     and every opaque `mcp__*` tool — asks on every call; it is not waivable by any mode.
 *  4. An in-memory **"allow for this chat"** grant suppresses the prompt for this conversation.
 *  5. The **mode** then decides: Auto → approve; Semi → blacklist; Manual → ask.
 */
enum class ApprovalMode {
    AUTO,
    SEMI,
    MANUAL,
}

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
        mode: ApprovalMode,
        headless: Boolean,
        allowedForChat: Boolean,
        hardFloor: Set<String> = ToolApprovalDefaults.NO_ALWAYS_ALLOW,
        isMcpRelayed: Boolean = toolName.startsWith(ToolApprovalDefaults.MCP_TOOL_PREFIX),
    ): Boolean {
        if (toolName == ASK_USER_TOOL_NAME) return headless
        if (headless) return true
        if (isMcpRelayed || toolName in hardFloor) return false
        if (allowedForChat) return true
        return when (mode) {
            ApprovalMode.AUTO -> true
            ApprovalMode.SEMI -> toolName !in blacklist
            ApprovalMode.MANUAL -> false
        }
    }
}
