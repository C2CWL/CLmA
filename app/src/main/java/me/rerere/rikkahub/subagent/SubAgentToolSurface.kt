package me.rerere.rikkahub.subagent

import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults

/**
 * T-09 / (8) + P2-04 — the headless **safety floor**: the per-tool predicate that decides
 * whether ANY sub-agent may be handed a tool.
 *
 * ## Why this exists
 *
 * A sub-agent is executed as a headless
 * [me.rerere.rikkahub.data.ai.tools.HeadlessConversations] run. Two things follow from that:
 *
 *  1. **Headless conversations auto-approve every tool.** `ChatService`'s `isToolAutoApproved`
 *     returns true for any conversation registered with `HeadlessConversations.mark()`, and that
 *     check does NOT consult [ToolApprovalDefaults.NO_ALWAYS_ALLOW]. So a sub-agent can call
 *     `eval_javascript`, `mcp_add`, `skill_install_from_url`, `keystore_generate_key`,
 *     `keystore_decrypt`, `grant_directory_access` or `nfc_write_tag` **unattended** — exactly the
 *     "confirm every single call, never blanket-allow" guarantee those tools are documented to
 *     have. That is a privilege-escalation hole, not a token-count nit: the parent may be
 *     prompt-injected, and the injected instruction now runs in a context that cannot ask the user
 *     anything.
 *  2. **A child can end up with a surface nobody chose for it.** The parent's `subagent_dispatch`
 *     `tools` allow-list used to be a dead parameter, and a sub-agent used to inherit its parent's
 *     COMPLETE surface (browser, NFC, external storage, workspace, skills, MCP, and the `subagent_*`
 *     handles themselves). The `subagent_*` tools are harmless only because three `isHeadless`
 *     checks refuse to act (the engine's recursion guard); they still cost tokens on every single
 *     request and invite the model to waste a whole tool trip discovering that dispatch is refused.
 *
 * ## What it does
 *
 * [denialReason] is the floor: the one rule that is applied to every tool name that could reach a
 * sub-agent, wherever that name came from (the parent's surface, a dispatcher-supplied allow-list,
 * or — since P2-04 — a profile's OWN tool surface). It removes the tools a headless run must never
 * receive — see [UI_BOUND_TOOL_NAMES], [PRIVACY_SENSITIVE_TOOL_NAMES],
 * [ToolApprovalDefaults.NO_ALWAYS_ALLOW] and the `subagent_` prefix.
 *
 * This object is deliberately **pure policy, no state**. The per-conversation record of *which*
 * surface a run got (and the identity filter that applies this floor to the finished tool list each
 * time `ChatService` assembles one) lives in [SubAgentSurface].
 *
 * ## Grounded in code, not in guesswork
 *
 * Every set below is derived from something the app actually does (a `ToolHostActivity` host, a
 * `NO_ALWAYS_ALLOW` registration, a prefix rule), so it cannot drift out of sync with the tool
 * implementations the way a hand-maintained "risky tools" list would.
 */
object SubAgentToolSurface {

    /**
     * Tools that need a human in front of the device and therefore can never complete inside a
     * headless run. Grounded in code, not in guesswork — each one is a `ToolHostActivity` host:
     *
     *  - `take_photo` → `MODE_CAMERA`
     *  - `verify_fingerprint` → `MODE_BIOMETRIC`
     *  - `nfc_read_tag` / `nfc_write_tag` → `MODE_NFC_READ` / `MODE_NFC_WRITE`
     *  - `grant_directory_access` → `MODE_SAF_PICKER`
     *
     * `nfc_write_tag` and `grant_directory_access` are already in
     * [ToolApprovalDefaults.NO_ALWAYS_ALLOW]; they are listed again so the reason a caller reads
     * ("needs a device UI") stays correct even if that set is ever reshuffled.
     *
     * Kept short on purpose. Tools that merely *put something on screen* without blocking
     * (`share`, `open_file`, `launch_app`, `show_toast`, `post_notification`) still work headless,
     * so v1 leaves them alone — a wrong entry here silently breaks a working sub-agent, which is
     * worse than the annoyance it would avoid.
     */
    val UI_BOUND_TOOL_NAMES: Set<String> = setOf(
        "take_photo",
        "verify_fingerprint",
        "nfc_read_tag",
        "nfc_write_tag",
        "grant_directory_access",
    )

    /**
     * Tools that can technically finish inside a headless run — they touch no device UI and never
     * block — but must not, because finishing means **capturing the user's surroundings or speech
     * with nobody present to consent**.
     *
     *  - `record_audio` → opens the microphone and writes what it hears to a file
     *  - `speech_to_text` → opens the microphone and ships the audio to a recognizer
     *
     * Both are in [ToolApprovalDefaults.DANGEROUS_TOOLS] (the privacy / hardware-actuation group),
     * so a user can make an ordinary conversation prompt on every call. A headless one does not:
     * `ChatService
     * .isToolAutoApproved` blanket-approves every tool in a `HeadlessConversations` run without
     * consulting `NO_ALWAYS_ALLOW`, which is the hole the floor closes at the surface level.
     * Excluding them here means a prompt-injected parent cannot turn its sub-agent into a silent
     * recorder.
     *
     * Not listed: `transcribe_audio_file` (reads existing files, records nothing) and the
     * merely-screen-visible `share` / `open_file` / `launch_app` / `show_toast` /
     * `post_notification` (they register on screen but still complete headless, so v1 leaves them).
     */
    val PRIVACY_SENSITIVE_TOOL_NAMES: Set<String> = setOf(
        "record_audio",
        "speech_to_text",
    )

    /**
     * Tools whose whole definition is "the parent agent controls itself": dispatch, list, get,
     * cancel. Inside a sub-agent they only ever answer "no_recursion" (three `isHeadless` guards
     * refuse them), so they are pure token cost plus a wasted tool trip. Excluded by PREFIX, so a
     * future `subagent_*` handle is covered without touching this file.
     */
    const val INTERNAL_TOOL_PREFIX: String = "subagent_"

    /**
     * `ask_user` is not a permission gate — it is a request for human input, and a headless run
     * has nobody to answer, so it degrades to an `ask_user_unavailable` envelope. It is excluded
     * to save the trip, not to fix a hang. (Verified 2026-10-01: `LocalTools` returns the
     * envelope rather than blocking; the older "it blocks in headless" theory was wrong.)
     */
    const val ASK_USER_TOOL_NAME: String = "ask_user"

    /**
     * Why a tool may not be handed to a sub-agent, or null when it is fine. The strings match the
     * vocabulary AAAelina's `SubAgentRun` uses for the same rejections, so an error surfaced to a
     * model looks the same in both codebases.
     */
    fun denialReason(toolName: String): String? = when {
        toolName.startsWith(INTERNAL_TOOL_PREFIX) -> "tool_unavailable_headless"
        toolName == ASK_USER_TOOL_NAME -> "tool_unavailable_headless"
        toolName in UI_BOUND_TOOL_NAMES -> "tool_unavailable_headless"
        toolName in ToolApprovalDefaults.NO_ALWAYS_ALLOW -> "tool_not_authorized"
        toolName in PRIVACY_SENSITIVE_TOOL_NAMES -> "tool_not_authorized"
        else -> null
    }

    /** True when [toolName] must never reach a sub-agent's surface. */
    fun isDenied(toolName: String): Boolean = denialReason(toolName) != null

    /**
     * The headless-safe subset of [callerToolNames] — the pure policy function, used to validate a
     * dispatcher-supplied `tools` allow-list and to keep the filter testable without a `Tool`.
     */
    fun safeNames(callerToolNames: Collection<String>): Set<String> =
        callerToolNames.filterNot { isDenied(it) }.toSet()
}
