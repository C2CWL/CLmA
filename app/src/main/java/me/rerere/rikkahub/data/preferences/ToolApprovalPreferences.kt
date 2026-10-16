package me.rerere.rikkahub.data.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.ai.tools.ApprovalMode
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults

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
)

/**
 * Persistent tool-approval prefs, mode era. Two pieces:
 *
 * 1. **Blacklist** — the user's list of tools that must ask before running. Only consulted in
 *    Semi mode; in Auto it is ignored, in Manual every tool asks anyway. Configured from
 *    Settings → Tool approvals. The non-waivable floor
 *    ([me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.isHardFloor]) and every opaque
 *    `mcp__*` tool ask regardless of this set and regardless of the mode.
 *
 * 2. **Approval mode** — the three-level posture selector ([ApprovalMode]). Replaces the old
 *    single-boolean YOLO toggle. The retired `global_auto_approve_yolo` key is no longer read;
 *    a pre-existing value is simply ignored (the mode defaults to MANUAL), so upgrading from
 *    the YOLO scheme resets every user to the safest posture.
 *
 * The companion in-memory "Allow for this chat" allow-list lives in
 * [me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList] and resets on /new or app
 * process restart.
 */
class ToolApprovalPreferences(private val context: Context) {
    private val store = context.toolApprovalDataStore
    private val K_BLACKLIST = stringSetPreferencesKey("tool_blacklist")
    @Suppress("unused")
    private val K_GLOBAL_YOLO = booleanPreferencesKey("global_auto_approve_yolo") // retired
    private val K_MODE = stringPreferencesKey("approval_mode")

    // Filtered on every read: a stale workspace_-prefixed entry must never surface in the
    // settings list or an approval check, even before any cleanup write happens.
    val blacklistFlow: Flow<Set<String>> = store.data.map {
        migrateWorkspaceToolsFrom(it[K_BLACKLIST].orEmpty()).first
    }

    /** Live flow of the current approval mode. Default [ApprovalMode.MANUAL]. */
    val modeFlow: Flow<ApprovalMode> = store.data.map { snapshotMode(it[K_MODE]) }

    suspend fun currentBlacklist(): Set<String> =
        migrateWorkspaceToolsFrom(store.data.first()[K_BLACKLIST].orEmpty()).first

    /** Blacklist + mode in one read, for the per-call auto-approval check. */
    suspend fun snapshot(): ToolApprovalSnapshot {
        val prefs = store.data.first()
        return ToolApprovalSnapshot(
            blacklist = migrateWorkspaceToolsFrom(prefs[K_BLACKLIST].orEmpty()).first,
            mode = snapshotMode(prefs[K_MODE]),
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

    /** Drop every blacklist entry. The floor and `mcp__*` tools still ask. */
    suspend fun clearBlacklist() {
        store.edit { it.remove(K_BLACKLIST) }
    }

    /** Add every name in [toolNames] (workspace tools filtered out) to the blacklist in one write. */
    suspend fun addAllToBlacklist(toolNames: Collection<String>) {
        val clean = toolNames.filterNot(::isWorkspaceToolName).toSet()
        if (clean.isEmpty()) return
        store.edit { prefs -> prefs[K_BLACKLIST] = prefs[K_BLACKLIST].orEmpty() + clean }
    }

    /** Resolve a stored mode string to [ApprovalMode], defaulting to [ApprovalMode.MANUAL]. */
    private fun snapshotMode(stored: String?): ApprovalMode =
        stored?.let { runCatching { ApprovalMode.valueOf(it) }.getOrNull() } ?: ApprovalMode.MANUAL
}
