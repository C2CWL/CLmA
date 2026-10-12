package me.rerere.rikkahub.data.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
 * @property blacklist tools that require a fresh confirmation before running. Empty by default:
 *   nothing asks until the user says so.
 * @property yolo the "I AM STUPID" blanket consent toggle.
 */
data class ToolApprovalSnapshot(
    val blacklist: Set<String> = emptySet(),
    val yolo: Boolean = false,
)

/**
 * Persistent tool-approval prefs, blacklist era. Two pieces:
 *
 * 1. **Blacklist** — the user's list of tools that must ask before they run. Default empty, so
 *    out of the box nothing prompts. Configured from Settings → Tool approvals; each entry can
 *    be removed there to stop it asking. The non-waivable floor
 *    ([me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.isHardFloor]) and every opaque
 *    `mcp__*` tool ask regardless of this set.
 *
 * 2. **"I AM STUPID" global auto-approve** — single boolean that, when true, treats every tool
 *    as pre-approved across every conversation and every surface (in-app, Telegram, cron),
 *    including the blacklist and the floor. HARDLINE STILL APPLIES — there is no override for
 *    that. Surfaced as a bright-red toggle behind a confirm dialog because it's a live foot-gun.
 *
 * The companion in-memory "Allow for this chat" allow-list lives in
 * [me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList] and resets on /new or app
 * process restart.
 */
class ToolApprovalPreferences(private val context: Context) {
    private val store = context.toolApprovalDataStore
    private val K_BLACKLIST = stringSetPreferencesKey("tool_blacklist")
    private val K_GLOBAL_YOLO = booleanPreferencesKey("global_auto_approve_yolo")

    // Filtered on every read: a stale workspace_-prefixed entry must never surface in the
    // settings list or an approval check, even before any cleanup write happens.
    val blacklistFlow: Flow<Set<String>> = store.data.map {
        migrateWorkspaceToolsFrom(it[K_BLACKLIST].orEmpty()).first
    }

    /** Live flow of the "I AM STUPID" global auto-approve flag. Default false. */
    val globalYoloFlow: Flow<Boolean> = store.data.map {
        it[K_GLOBAL_YOLO] ?: false
    }

    suspend fun currentBlacklist(): Set<String> =
        migrateWorkspaceToolsFrom(store.data.first()[K_BLACKLIST].orEmpty()).first

    /** Blacklist + YOLO in one read, for the per-call auto-approval check. */
    suspend fun snapshot(): ToolApprovalSnapshot {
        val prefs = store.data.first()
        return ToolApprovalSnapshot(
            blacklist = migrateWorkspaceToolsFrom(prefs[K_BLACKLIST].orEmpty()).first,
            yolo = prefs[K_GLOBAL_YOLO] ?: false,
        )
    }

    /** Snapshot read of the YOLO flag alone. */
    suspend fun currentYolo(): Boolean = globalYoloFlow.first()

    suspend fun setYolo(enabled: Boolean) {
        store.edit { it[K_GLOBAL_YOLO] = enabled }
    }

    /** Put [toolName] on the blacklist ([needsApproval] = true) or take it off. */
    suspend fun setBlacklisted(toolName: String, needsApproval: Boolean) {
        // Workspace tools are per-workspace, not per-app; the global blacklist is checked
        // from every workspace, so a global entry would leak across them. Refuse rather than
        // silently poisoning the set.
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
}
