package me.rerere.rikkahub.data.ai.tools

/**
 * The three posture levels the device owner picks in Settings → Tool approvals.
 *
 * Replaces the old two-knob (blacklist + YOLO) scheme with a single semantic selector so
 * the user says "how much friction do I want" instead of maintaining a per-tool list and a
 * blanket override separately.
 *
 * - [AUTO]: no friction. Side-effecting tools run without prompting — including opaque
 *   relayed `mcp__*` tools (拍板 2026-10-16: Auto must not nag about MCP, an agent build
 *   whose MCP tools all ask is not autonomous). The privilege-escalation floor
 *   ([ToolApprovalDefaults.NO_ALWAYS_ALLOW]) still asks on every call — Auto is "stop asking
 *   me about ordinary work", not "hand over the keystore".
 * - [SEMI]: the blacklist decides. A tool on the blacklist asks; everything else
 *   auto-approves. This is the fine-grained mode — the user curates which specific tools
 *   need a confirmation.
 * - [MANUAL]: every tool call asks. The only exceptions are the in-memory "allow for this
 *   chat" grant (so a long agent session isn't drowned in prompts after the first OK) and
 *   `ask_user` in a headless run (nobody to answer). The floor obviously still asks too.
 *
 * Ordering, top to bottom — first match wins:
 *
 *  1. `ask_user` is a human-input request, not a permission gate: it must pause for the user
 *     wherever there is a surface to ask on, and auto-approve only in a headless run.
 *  2. A **headless** conversation (cron / sub-agent / workflow / external automation) has no
 *     approval channel: asking would hang the turn forever, so everything auto-approves.
 *     The tools that must never run unattended are refused separately as an error envelope
 *     by [HeadlessToolApprovalPolicy].
 *  3. **Auto** waives opaque relayed `mcp__*` tools. The named privilege-escalation floor
 *     below is NOT waived: adding MCP servers / installing skills / code eval / keystore
 *     still confirm every time.
 *  4. The **hard floor** ([ToolApprovalDefaults.isHardFloor]) asks on every call; no mode
 *     waives it.
 *  5. An in-memory **"allow for this chat"** grant suppresses the prompt for this conversation.
 *  6. The **mode** then decides: Auto → approve; Semi → blacklist; Manual → ask.
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
        // Auto waives opaque relayed MCP tools (拍板 2026-10-16) but NOT the named floor below.
        if (mode == ApprovalMode.AUTO && isMcpRelayed) return true
        if (isMcpRelayed || toolName in hardFloor) return false
        if (allowedForChat) return true
        return when (mode) {
            ApprovalMode.AUTO -> true
            ApprovalMode.SEMI -> toolName !in blacklist
            ApprovalMode.MANUAL -> false
        }
    }
}
