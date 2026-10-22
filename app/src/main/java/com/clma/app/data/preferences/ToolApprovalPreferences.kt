package com.clma.app.data.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.clma.app.data.ai.tools.ApprovalMode
import com.clma.app.data.ai.tools.ToolApprovalDefaults

private const val TAG = "ToolApprovalPreferences"

/** Workspace tools (created by createWorkspaceTools) are per-workspace, never
 *  per-app, so this prefix must never be written to or read from the global
 *  blacklist. */
private const val WORKSPACE_TOOL_PREFIX = "workspace_"

private val Context.toolApprovalDataStore by preferencesDataStore(name = "tool_approval")

/** True iff [name] is a workspace tool (reserved "workspace_" prefix, see
 *  createWorkspaceTools). Pure so the prefix rule can be unit-tested without a
 *  DataStore instance. */
internal fun isWorkspaceToolName(name: String): Boolean = name.startsWith(WORKSPACE_TOOL_PREFIX)

/** Splits [stored] into (kept, removed) where removed is every workspace_-prefixed
 *  entry. Pure - backs the read-time filter that keeps workspace tools out of the
 *  global blacklist. */
internal fun migrateWorkspaceToolsFrom(stored: Set<String>): Pair<Set<String>, Set<String>> {
    val removed = stored.filterTo(mutableSetOf(), ::isWorkspaceToolName)
    return (stored - removed) to removed
}

/** Keeps only the names that are actually on the default-ask floor
 *  ([ToolApprovalDefaults.NO_ALWAYS_ALLOW]). A waiver for anything else is meaningless (the
 *  blacklist already governs it) and a stale / hand-edited entry must never widen the waiver
 *  set. Pure so the rule can be unit-tested without a DataStore instance. */
internal fun sanitizeFloorWaivers(stored: Set<String>): Set<String> =
    stored.filterTo(mutableSetOf()) { it in ToolApprovalDefaults.NO_ALWAYS_ALLOW }

/**
 * The approval decision's persisted inputs, read in one go so a single tool call does not pay
 * two DataStore round-trips.
 *
 * @property blacklist tools that require a fresh confirmation before running. In Semi mode
 *   these ask; in Auto they are ignored; in Manual every tool asks regardless.
 * @property mode the posture level ([ApprovalMode]). Default [ApprovalMode.MANUAL] on a fresh
 *   install or after upgrade from the old YOLO scheme — the safest posture, so the user opts
 *   *out* of friction deliberately rather than discovering it was off.
 */
data class ToolApprovalSnapshot(
    val blacklist: Set<String> = emptySet(),
    val mode: ApprovalMode = ApprovalMode.MANUAL,
    /** Default-ask floor tools ([ToolApprovalDefaults.NO_ALWAYS_ALLOW]) the user explicitly
     *  turned approval off for, after accepting the risk dialog. Empty by default. */
    val floorWaived: Set<String> = emptySet(),
    /** Whether relayed `mcp__*` tools ask in Semi mode. Default true; only an explicit,
     *  risk-acknowledged switch in Settings turns it off. Auto and Manual ignore it. */
    val mcpRequireApproval: Boolean = true,
)

/**
 * Persistent tool-approval prefs, mode era. Two pieces:
 *
 * 1. **Blacklist** — the user's list of tools that must ask before running. Only consulted in
 *    Semi mode; in Auto it is ignored, in Manual every tool asks anyway. Configured from
 *    Settings → Tool approvals. The default-ask floor
 *    ([com.clma.app.data.ai.tools.ToolApprovalDefaults.NO_ALWAYS_ALLOW]) asks regardless
 *    of this set unless the user waived it (see 3.); opaque relayed `mcp__*` tools ask too,
 *    except in Auto mode which waives them (拍板 2026-10-16).
 *
 * 2. **Approval mode** — the three-level posture selector ([ApprovalMode]). Replaces the old
 *    single-boolean YOLO toggle. The retired `global_auto_approve_yolo` key is no longer read;
 *    a pre-existing value is simply ignored (the mode defaults to MANUAL), so upgrading from
 *    the YOLO scheme resets every user to the safest posture.
 *
 * 3. **Floor waivers** — the default-ask floor
 *    ([com.clma.app.data.ai.tools.ToolApprovalDefaults.NO_ALWAYS_ALLOW]) and relayed `mcp__*`
 *    tools ask by default, but the user may turn that off per floor tool (`floor_waived`) or for
 *    all MCP tools (`mcp_relayed_require_approval`) after accepting a risk + liability dialog.
 *    `floor_waiver_ack_at` records when that dialog was last accepted. Waivers only matter for
 *    conversations with an approval channel; headless runs keep refusing these tools via
 *    [com.clma.app.data.ai.tools.HeadlessToolApprovalPolicy].
 *
 * The companion in-memory "Allow for this chat" allow-list lives in
 * [com.clma.app.data.ai.tools.ToolApprovalAllowList] and resets on /new or app
 * process restart.
 */
class ToolApprovalPreferences(private val context: Context) {
    private val store = context.toolApprovalDataStore
    private val K_BLACKLIST = stringSetPreferencesKey("tool_blacklist")
    @Suppress("unused")
    private val K_GLOBAL_YOLO = booleanPreferencesKey("global_auto_approve_yolo") // retired
    private val K_MODE = stringPreferencesKey("approval_mode")
    private val K_FLOOR_WAIVED = stringSetPreferencesKey("floor_waived")
    private val K_MCP_REQUIRE_APPROVAL = booleanPreferencesKey("mcp_relayed_require_approval")
    private val K_FLOOR_WAIVER_ACK_AT = longPreferencesKey("floor_waiver_ack_at")

    // Filtered on every read: a stale workspace_-prefixed entry must never surface in the
    // settings list or an approval check, even before any cleanup write happens.
    val blacklistFlow: Flow<Set<String>> = store.data.map {
        migrateWorkspaceToolsFrom(it[K_BLACKLIST].orEmpty()).first
    }

    /** Live flow of the current approval mode. Default [ApprovalMode.MANUAL]. */
    val modeFlow: Flow<ApprovalMode> = store.data.map { snapshotMode(it[K_MODE]) }

    /** Live flow of the floor tools whose approval the user waived. Default empty. */
    val floorWaivedFlow: Flow<Set<String>> = store.data.map {
        sanitizeFloorWaivers(it[K_FLOOR_WAIVED].orEmpty())
    }

    /** Live flow of the "MCP tools ask before running" switch. Default true. */
    val mcpRequireApprovalFlow: Flow<Boolean> = store.data.map {
        it[K_MCP_REQUIRE_APPROVAL] ?: true
    }

    suspend fun currentBlacklist(): Set<String> =
        migrateWorkspaceToolsFrom(store.data.first()[K_BLACKLIST].orEmpty()).first

    /** Blacklist + mode in one read, for the per-call auto-approval check. */
    suspend fun snapshot(): ToolApprovalSnapshot {
        val prefs = store.data.first()
        return ToolApprovalSnapshot(
            blacklist = migrateWorkspaceToolsFrom(prefs[K_BLACKLIST].orEmpty()).first,
            mode = snapshotMode(prefs[K_MODE]),
            floorWaived = sanitizeFloorWaivers(prefs[K_FLOOR_WAIVED].orEmpty()),
            mcpRequireApproval = prefs[K_MCP_REQUIRE_APPROVAL] ?: true,
        )
    }

    /** Snapshot read of the mode alone. */
    suspend fun currentMode(): ApprovalMode = modeFlow.first()

    suspend fun setMode(mode: ApprovalMode) {
        store.edit { it[K_MODE] = mode.name }
    }

    /** Put [toolName] on the blacklist ([needsApproval] = true) or take it off. */
    suspend fun setBlacklisted(toolName: String, needsApproval: Boolean) {
        if (isWorkspaceToolName(toolName)) {
            Log.w(TAG, "refusing to put workspace tool '$toolName' on the global blacklist")
            return
        }
        store.edit { prefs ->
            val current = prefs[K_BLACKLIST].orEmpty()
            prefs[K_BLACKLIST] = if (needsApproval) current + toolName else current - toolName
        }
    }

    /** Drop every blacklist entry. Floor waivers and the MCP switch are left as they are. */
    suspend fun clearBlacklist() {
        store.edit { it.remove(K_BLACKLIST) }
    }

    /** Add every name in [toolNames] (workspace tools filtered out) to the blacklist in one write. */
    suspend fun addAllToBlacklist(toolNames: Collection<String>) {
        val clean = toolNames.filterNot(::isWorkspaceToolName).toSet()
        if (clean.isEmpty()) return
        store.edit { prefs -> prefs[K_BLACKLIST] = prefs[K_BLACKLIST].orEmpty() + clean }
    }

    /**
     * The "add common dangerous tools" one-tap: blacklist [toolNames] AND restore every safety
     * default the user may have waived (floor waivers cleared, MCP approval back on), in one
     * write. This is the safety-restoring direction, so it needs no confirmation.
     */
    suspend fun restoreRecommended(toolNames: Collection<String>) {
        val clean = toolNames.filterNot(::isWorkspaceToolName).toSet()
        store.edit { prefs ->
            if (clean.isNotEmpty()) prefs[K_BLACKLIST] = prefs[K_BLACKLIST].orEmpty() + clean
            prefs.remove(K_FLOOR_WAIVED)
            prefs[K_MCP_REQUIRE_APPROVAL] = true
        }
    }

    /**
     * Waive ([waived] = true) or restore the default per-call approval of a floor tool. Names
     * outside [ToolApprovalDefaults.NO_ALWAYS_ALLOW] are ignored. Waiving must only be called
     * after the user accepted the risk dialog; it stamps `floor_waiver_ack_at`.
     */
    suspend fun setFloorWaived(toolName: String, waived: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (toolName !in ToolApprovalDefaults.NO_ALWAYS_ALLOW) {
            Log.w(TAG, "refusing to waive '$toolName': not a default-ask floor tool")
            return
        }
        store.edit { prefs ->
            val current = sanitizeFloorWaivers(prefs[K_FLOOR_WAIVED].orEmpty())
            prefs[K_FLOOR_WAIVED] = if (waived) current + toolName else current - toolName
            if (waived) {
                prefs[K_FLOOR_WAIVER_ACK_AT] = nowMs
                // The row's switch now reads "off": drop a stale blacklist entry (e.g. from the
                // recommended one-tap) so Semi mode does not keep asking behind the user's back.
                prefs[K_BLACKLIST] = prefs[K_BLACKLIST].orEmpty() - toolName
            }
        }
        Log.i(TAG, "floor approval for '$toolName' ${if (waived) "waived by user (risk acknowledged)" else "restored"}")
    }

    /**
     * Turn the "MCP tools ask before running" switch on or off. Turning it off must only be
     * called after the user accepted the risk dialog; it stamps `floor_waiver_ack_at`.
     */
    suspend fun setMcpRequireApproval(required: Boolean, nowMs: Long = System.currentTimeMillis()) {
        store.edit { prefs ->
            prefs[K_MCP_REQUIRE_APPROVAL] = required
            if (!required) prefs[K_FLOOR_WAIVER_ACK_AT] = nowMs
        }
        Log.i(TAG, "MCP relayed tool approval ${if (required) "restored" else "waived by user (risk acknowledged)"}")
    }

    /** Resolve a stored mode string to [ApprovalMode], defaulting to [ApprovalMode.MANUAL]. */
    private fun snapshotMode(stored: String?): ApprovalMode =
        stored?.let { runCatching { ApprovalMode.valueOf(it) }.getOrNull() } ?: ApprovalMode.MANUAL
}
