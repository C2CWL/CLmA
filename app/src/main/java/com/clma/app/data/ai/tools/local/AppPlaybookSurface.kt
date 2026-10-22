package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.AgentTurnTracker
import me.rerere.rikkahub.data.ai.tools.AppPlaybookRules
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Screen-automation experience memory, read side.
 *
 * The write side is ordinary cold memory: when the agent finishes driving an app it calls
 * `memory_write` on that app's playbook document (`AppPlaybookRules.fileNameFor`). The read side
 * is this: `launch_app` / `read_window_tree` ask [surfaceAppPlaybook] for the app they just
 * touched and inline the stored note into their own result. The agent then starts a task already
 * knowing the entry points, the selectors that resolved last time and the dialogs that steal a
 * tap — instead of paying for the same discovery again.
 *
 * Everything is opt-in by construction: with no cold-memory binding
 * [ToolInvocationContext.appPlaybook] is `null`, [surfaceAppPlaybook] returns
 * [AppPlaybookSurface.None], and the tool result is byte-for-byte what it was before this file
 * existed.
 */
internal sealed interface AppPlaybookSurface {
    /** Nothing to add — no binding, blank/unsafe package, or already surfaced this turn. */
    object None : AppPlaybookSurface

    /** A stored playbook, clipped for injection. */
    data class Stored(val fileName: String, val content: String) : AppPlaybookSurface

    /** No playbook yet for this app; carries the file name so the model can create one. */
    data class Missing(val fileName: String) : AppPlaybookSurface
}

/**
 * Resolves what (if anything) to surface for [packageName].
 *
 * @param force when true the note is surfaced even if it already was this turn (used by
 *   `launch_app`, the deliberate start of an app task); when false it is limited to one surfacing
 *   per app per turn so repeated `read_window_tree` calls do not repeat the same note.
 * @param includeMissingHint when true a missing playbook yields [AppPlaybookSurface.Missing] so
 *   the caller can nudge the agent to write one, instead of the usual silent
 *   [AppPlaybookSurface.None].
 */
internal suspend fun surfaceAppPlaybook(
    packageName: String,
    invocationContext: ToolInvocationContext,
    force: Boolean = false,
    includeMissingHint: Boolean = false,
): AppPlaybookSurface {
    val provider = invocationContext.appPlaybook ?: return AppPlaybookSurface.None
    val fileName = AppPlaybookRules.fileNameFor(packageName) ?: return AppPlaybookSurface.None
    if (force) {
        AgentTurnTracker.claimPlaybookSurface(packageName)
    } else if (!AgentTurnTracker.claimPlaybookSurface(packageName)) {
        return AppPlaybookSurface.None
    }
    val text = try {
        provider(fileName)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // A cold-memory read failure must never break a screen-automation tool.
        null
    }
    val clipped = text?.let { AppPlaybookRules.clipForSurface(it) }.orEmpty()
    return when {
        clipped.isNotEmpty() -> AppPlaybookSurface.Stored(fileName, clipped)
        includeMissingHint -> AppPlaybookSurface.Missing(fileName)
        else -> AppPlaybookSurface.None
    }
}

/** Adds the [surface] fields to a tool-result payload. A no-op for [AppPlaybookSurface.None]. */
internal fun JsonObjectBuilder.putAppPlaybook(surface: AppPlaybookSurface) {
    when (surface) {
        AppPlaybookSurface.None -> Unit
        is AppPlaybookSurface.Stored -> {
            put("app_playbook_file", surface.fileName)
            put("app_playbook", surface.content)
            put(
                "app_playbook_note",
                "A stored note on how to drive this app (from cold memory). Apply it. Only " +
                    "memory_read the file if you need the part this clipped.",
            )
        }
        is AppPlaybookSurface.Missing -> {
            put("app_playbook_file", surface.fileName)
            put("app_playbook_missing", true)
            put(
                "app_playbook_hint",
                "No playbook for this app yet. If you end up automating it, memory_write " +
                    "\"${surface.fileName}\" before you finish — the entry points, the selectors " +
                    "that resolved, and any dialogs or traps — so the next run is cheaper.",
            )
        }
    }
}
