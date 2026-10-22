package me.rerere.rikkahub.data.ai

/**
 * Files produced by `take_screenshot` during the current turn that should not outlive it.
 *
 * A screenshot taken while driving the screen is a working artifact: the model looks at it, acts,
 * and moves on. Unless the caller asked to keep it (`keep=true`), it is written to a cache path
 * only, and the way to remove it is remembered here so the turn can delete it when it ends
 * ([purge]) instead of leaving a pile of screen dumps behind. The chat bubble for such a
 * screenshot falls back to a "[screenshot cleaned up]" placeholder once the file is gone.
 *
 * Each entry is a *deletion action* rather than a path, because the gallery copy lives behind
 * MediaStore on API 29+ and must be removed through the content resolver, not the file system.
 * Keeping the policy here (and out of Android APIs) is also what makes it unit-testable.
 *
 * Mirrors the lifetime of [AgentTurnTracker]: per turn, in memory, never persisted.
 */
object ScreenshotLedger {

    private val deleters = java.util.Collections.synchronizedSet(LinkedHashSet<() -> Boolean>())

    /** Registers [delete] to run when the turn ends; it reports whether it actually removed something. */
    fun recordTransient(delete: () -> Boolean) {
        deleters.add(delete)
    }

    /**
     * Runs every remembered deletion and forgets them. A deleter that throws or finds nothing is
     * skipped. Returns how many actually removed something.
     */
    fun purge(): Int {
        val snapshot = synchronized(deleters) {
            deleters.toList().also { deleters.clear() }
        }
        var removed = 0
        for (delete in snapshot) {
            if (runCatching { delete() }.getOrDefault(false)) removed++
        }
        return removed
    }

    /** Forgets the remembered deletions without running them (turn ended with nothing to sweep). */
    fun clear() {
        deleters.clear()
    }

    /** Test hook. */
    fun recordedCount(): Int = deleters.size
}
