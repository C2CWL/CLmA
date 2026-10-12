package me.rerere.rikkahub.data.ai.tools

/**
 * Static knowledge the runtime approval decision is built on.
 *
 * **Blacklist era.** Approval is no longer a fixed "these tools always ask" white-list baked
 * into the build. Instead every tool is routed through the runtime decision
 * ([ToolApprovalPolicy.shouldAutoApprove] via `ChatService.isToolAutoApproved`) and the user
 * says which tools must ask by putting them on a **blacklist** (Settings → Tool approvals).
 * By default that blacklist is empty: nothing asks. Two things cannot be waived by leaving
 * them off the blacklist, because they are not the user's to waive at the call site:
 *
 *  - [NO_ALWAYS_ALLOW] — a small, hard floor of privilege-escalation surfaces. Never
 *    auto-approved, cannot be removed from the asking set, no "always" shortcut.
 *  - Every `mcp__*` relayed tool — an MCP server's surface is opaque to us, so a call to one
 *    always asks (again, no blanket grant).
 *
 * [DANGEROUS_TOOLS] is the curated list of side-effecting / privacy-reading tools. It no
 * longer gates anything; it is what the Settings screen offers as a one-tap "add the usual
 * suspects to the blacklist" starting point, so the safe behaviour is one tap away without
 * being forced on anyone.
 *
 * The command-level [HardlineCommandGuard] floor is separate and still unconditional: even a
 * blacklist miss and YOLO cannot push `rm -rf /` through.
 */
object ToolApprovalDefaults {

    /**
     * The `mcp__` namespace prefix `buildMcpToolName` puts on every relayed MCP tool. Kept
     * here as well as at the call sites so the approval floor and the hardline floor agree on
     * what "this is an opaque MCP call" means.
     */
    const val MCP_TOOL_PREFIX: String = "mcp__"

    /**
     * Curated set of tools worth blacklisting: everything side-effecting (writes to disk,
     * runs shell, controls hardware, posts to a remote service, manipulates UI) plus the
     * privacy-sensitive READS (contacts, sms, call log) — reading PII off the device into an
     * LLM context deserves the same friction as a write. NOT a gate any more; the Settings
     * screen uses it as its "recommended" one-tap selection.
     *
     * If you add a new LLM-callable tool, decide: side-effecting or PII-reading → add it here
     * so users can find it in the picker. A pure read (battery, brightness) does not need an
     * entry — nothing has to be configured for it to run.
     */
    val DANGEROUS_TOOLS: Set<String> = setOf(
        // Shell / arbitrary code execution
        "termux_run_command",
        "termux_session_start",  // opens a persistent interactive shell; the meaningful consent moment
        "transcribe_audio_file",  // shells out to whisper-cli via Termux; reads arbitrary audio files
        "eval_javascript",

        // Shell via Shizuku (shell UID, no root)
        "shizuku_exec",

        // Remote shell (SSH)
        "ssh_exec",
        "ssh_exec_saved",
        "ssh_upload",
        "ssh_download",
        "ssh_forget_host_key",
        "save_ssh_host",
        "delete_ssh_host",

        // Accounting writes
        "usage_set_prices",  // replaces the whole model price table; every later cost depends on it

        // Filesystem / network writes
        "write_text_file",
        "download_file",
        "scan_media",

        // Cron mutations (read is free; mutate asks)
        "schedule_job",
        "delete_job",
        "pause_job",
        "resume_job",
        "trigger_job_now",
        "get_job_history",

        // UI manipulation through the AccessibilityService
        "tap",
        "long_press",
        "swipe",
        "scroll",
        "set_text",
        "click_node",
        "global_action",
        "launch_app",
        "launch_activity",   // launches a specific activity, same trust footprint as launch_app
        "open_url",          // can dial tel:, draft mailto:, hand a URI to any app
        "open_file",         // ACTION_VIEW on a user-supplied path — same surface as launch_app
        "wake_screen",       // acquires wake lock, turns screen on at night

        // Privacy / hardware actuation
        "take_photo",
        "record_audio",
        "speech_to_text",    // activates the microphone + uploads audio to recognizer
        "verify_fingerprint",
        "share",
        "set_torch",
        "vibrate",
        "set_brightness",
        "set_volume",
        "play_media",
        "stop_media",
        "pause_media",
        "resume_media",
        "seek_media",
        // get_media_status is read-only — no approval needed
        "post_notification",

        // Privacy reads — PII leaves the device into the model's context
        "list_call_log",
        "list_contacts",
        "search_contacts",
        "list_sms_inbox",
        "search_sms",

        // Notification listener side effects (read-only listing is free, mutating is not)
        "dismiss_notification",
        "notification_action_click",
        "notification_reply",       // fires a notification's direct-reply RemoteInput action

        // File manager — all ops are side-effecting or read PII from arbitrary paths
        "list_files",
        "read_file",
        "write_binary_file",
        "delete_file",
        "move_file",
        "copy_file",
        "create_directory",
        "file_info",
        "find_files",
        // Batch file ops (item 5.5) — list-or-glob copy / move / delete. Side-effecting
        // over potentially many paths in one call, so each gets the same approval gate as
        // the single-path file tools.
        "batch_copy",
        "batch_move",
        "batch_delete",

        // Telegram outbound — the bot can DM other chats / change its own config
        "telegram_send_message",
        "telegram_send_photo",
        "telegram_send_document",
        "telegram_set_token",
        "telegram_enable",
        "telegram_disable",
        "telegram_add_whitelist",
        "telegram_remove_whitelist",
        "telegram_set_default_chat",
        "telegram_set_assistant",
        "telegram_set_commands",
        "telegram_delete_commands",

        // MCP control — all side-effecting MCP tools require approval.
        // mcp_add and mcp_update are also flagged with "no always-allow" below because a
        // hostile MCP server can exfiltrate everything the LLM has access to and we want
        // a per-call confirmation each time, not a one-shot blanket grant.
        "mcp_add",
        "mcp_update",
        "mcp_delete",
        "mcp_set_enabled",
        "mcp_set_tool_approval",

        // External Automation Intent API — flipping any of these changes who can fire
        // the assistant from outside the app. Privilege-escalation surface, requires
        // approval. Reads (`external_automation_status`) are free.
        "external_automation_set_enabled",
        "external_automation_add_trusted_package",
        "external_automation_remove_trusted_package",

        // Reliability bundle — generate_bug_report writes a file to disk that includes
        // a redacted logcat dump. The redactor catches the common token shapes; we still
        // gate so the user can review what's being created and decide whether to share.
        // check_app_updates is read-only and has no entry here.
        "generate_bug_report",

        // Sub-agents — subagent_dispatch spawns an autonomous LLM run with the parent's
        // tool surface. Approval-required so the user sees the task + tools before
        // delegation happens. list / get / cancel are read-only or user-controlling and
        // have no entry here.
        "subagent_dispatch",

        // P2-06b — the roster WRITE tools. Approval-required for the same reason as
        // subagent_dispatch above: a stored expert becomes dispatchable immediately, and
        // deleting one changes what every later `agent` name resolves to, so the user reviews
        // the change before it lands. subagent_list stays read-only and has no entry here.
        "subagent_create",
        "subagent_update",
        "subagent_delete",

        // P2-37 — the model-roster WRITE tools (`model_*`). Approval-required for the same
        // reason as the expert writes above: the roster is what every assistant's model
        // picker and every later generation resolves against, so the user reviews an add,
        // rename or delete before it lands. model_list stays read-only and has no entry here.
        "model_add",
        "model_update",
        "model_delete",

        // Workflows (Phase 12) — every mutator goes through the existing approval flow
        // with a human-readable summary rendered by WorkflowApprovalRenderer. workflow_run
        // fires immediately on approve, with HARDLINE still applied to every action.
        // workflow_list / workflow_get are read-only and have no entry here.
        "workflow_create",
        "workflow_update",
        "workflow_delete",
        "workflow_set_enabled",
        "workflow_run",

        // Skill import (Phase 16) — pulls a markdown / JSON skill from an arbitrary URL
        // (or from raw text the LLM already fetched via ssh / curl / MCP) and installs
        // it. Whatever is in the skill rides along with the assistant's tool surface,
        // so this is privilege-escalation-adjacent. NO_ALWAYS_ALLOW below.
        "skill_install_from_url",
        "skill_install_from_text",

        // JS skills (Phase 18) — run a skill's JavaScript inside a hidden WebView.
        // The script can issue arbitrary network requests on behalf of the user, so
        // every invocation gets per-call approval. Eligible for "Always allow" once a
        // particular skill is trusted (NOT in NO_ALWAYS_ALLOW — the skill's body has
        // already been reviewed at install time).
        "run_js",

        // Native intent tools (Phase 18) — open the system Calendar / Contacts / SMS /
        // Email composer / WiFi settings / Maps app. Each fires a system intent the user
        // finalises in the destination app. Approval gate ensures the user reviews the
        // pre-filled fields before the LLM hands the action off.
        "create_calendar_event",
        "create_contact",
        "send_email_intent",
        "send_sms_intent",
        "open_wifi_settings",
        "show_location_on_map",

        // In-app browser write tools (Phase 21 / Pass 2). The browser can carry auth tokens
        // in cookies, so anything that mutates page state OR runs JS is approval-gated. Read
        // tools (open, current_url, screenshot, get_text, get_dom, get_links, back, forward,
        // wait_for) are NOT in this set — reading text out of a page is the same trust level
        // as taking a screenshot or reading any other LLM context. browser_done is the
        // loop-control sentinel and never side-effects.
        "browser_click",
        "browser_type",
        "browser_scroll",
        "browser_submit",
        "browser_select",
        "browser_press_key",
        "browser_eval_js",
        // Composite click+read added in the token-cost optimisation pass — its click
        // side carries the same trust footprint as plain browser_click, so it
        // inherits the same approval gate.
        "browser_click_and_read",

        // web_fetch (item 1.2) — network egress. A bare HTTP GET/POST can exfiltrate
        // anything the LLM puts in the URL / body / headers, so it gets the same
        // approval gate as every other outbound call.
        "web_fetch",
        // web_extract performs the same outbound GET as web_fetch, so it carries the same
        // trust footprint and inherits the same approval gate.
        "web_extract",

        // Phase 25 — Phase 3 second cut. Every mutating tool is approval-gated; the
        // read-only tools (keystore_verify, keystore_list_keys, list_storage_volumes,
        // list_granted_directories, list_zip_contents) are deliberately NOT in this set.
        "send_sms",                 // sends a real SMS — costs money / leaves the device
        "set_wallpaper",            // changes a visible device setting
        "keystore_generate_key",    // creates a hardware key (NO_ALWAYS_ALLOW below)
        "keystore_sign",            // signs arbitrary data with the user's key
        "keystore_encrypt",         // encrypts with the user's key
        "keystore_decrypt",         // decrypts ciphertext (NO_ALWAYS_ALLOW below)
        "keystore_delete_key",      // destroys a key
        "nfc_read_tag",             // opens a foreground reader session
        "nfc_write_tag",            // writes NDEF to a physical tag (NO_ALWAYS_ALLOW below)
        "grant_directory_access",   // persistent read+write to a whole tree (NO_ALWAYS_ALLOW below)
        "zip_files",                // writes an archive to disk / a granted tree
        "unzip_file",               // writes extracted files to disk / a granted tree

        // agent-keyboard control — every tool that mutates the focused text field or cursor
        // is side-effecting and approval-gated. The two read tools (keyboard_read_field,
        // keyboard_editor_info) are deliberately NOT in this set. No HARDLINE arm is needed:
        // typing a string into a focused field is not shell execution, and agent-keyboard
        // itself refuses password / sensitive fields at the IME boundary.
        "keyboard_type",
        "keyboard_press_key",
        "keyboard_delete",
        "keyboard_clear",
        "keyboard_set_cursor",
        "keyboard_select_range",
    )

    /**
     * The hard floor: tools that require a fresh confirmation on **every single call**, no
     * matter what. Reserved for tools that are inherently privilege-escalation surfaces —
     * adding an MCP server is exactly that, since a hostile server can exfiltrate anything
     * reachable through the assistant's tool set.
     *
     * These are neither removable from the asking set (leaving them off the blacklist does
     * not waive them) nor eligible for the per-call shortcut. Only YOLO — and the command
     * floor [HardlineCommandGuard] — sit above this set.
     */
    val NO_ALWAYS_ALLOW: Set<String> = setOf(
        "mcp_add",
        "mcp_update",
        // eval_javascript runs arbitrary code in the QuickJS engine. Even with a wall-clock
        // timeout + a bounded native heap/stack, the code itself is attacker-controllable, so
        // a blanket "Always Allow" would hand unattended cron / Telegram paths a standing
        // arbitrary-code primitive. Require a per-call confirmation every time.
        "eval_javascript",
        // Phase 16 — skill_install_from_url + skill_install_from_text. The skill body is
        // fetched from an arbitrary URL (or supplied as raw text via any other tool, e.g.
        // ssh_exec / termux_run_command for authenticated servers) and installed against
        // the assistant's tool surface. We require per-call approval every single time so
        // the user reviews the URL / source-label + skill name.
        "skill_install_from_url",
        "skill_install_from_text",
        // Phase 21 / Pass 2 — browser_eval_js runs arbitrary JavaScript in a real WebView
        // with the user's cookies, localStorage, and authenticated fetch surface. Even
        // after HARDLINE filters out shell-shaped strings + obvious dynamic-eval patterns,
        // the residual surface is too broad to ever blanket-allow. Every invocation gets
        // an explicit per-call approval card, no exceptions.
        "browser_eval_js",
        // Phase 25 — privilege-escalation surfaces that must confirm every single call:
        //  - keystore_generate_key: mints a hardware key that later sign/encrypt calls trust
        //  - keystore_decrypt: turns ciphertext back into plaintext the model then reads
        //  - nfc_write_tag: permanently rewrites a physical tag's contents
        //  - grant_directory_access: persistent read+write to an entire storage tree,
        //    possibly cloud / Downloads / Pictures
        "keystore_generate_key",
        "keystore_decrypt",
        "nfc_write_tag",
        "grant_directory_access",
    )

    /**
     * True when [toolName] is on the non-waivable floor: a member of [NO_ALWAYS_ALLOW] or an
     * opaque relayed MCP tool (`mcp__*`). Such a tool asks on every call regardless of the
     * user's blacklist; only YOLO (the explicit "stop asking me" switch) sits above it.
     */
    fun isHardFloor(toolName: String): Boolean =
        toolName in NO_ALWAYS_ALLOW || toolName.startsWith(MCP_TOOL_PREFIX)
}
