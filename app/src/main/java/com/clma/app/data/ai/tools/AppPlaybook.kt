package me.rerere.rikkahub.data.ai.tools

/**
 * The pure contract for a **per-app screen-automation playbook**: one cold-memory Markdown
 * document per Android package, e.g. `app-com.tencent.mm.md`.
 *
 * Why it exists: driving an app with accessibility is the most expensive thing the agent does —
 * every disambiguation costs a `read_window_tree` round trip. The first time the agent drives an
 * app it should write down what actually worked (entry points, the selectors that resolve, the
 * dialogs that steal a tap, what to avoid); every later run gets that note **surfaced
 * automatically** when it launches or reads the app, instead of paying for the same discovery
 * twice. That is the whole point: turn a repeated cost into a stored asset.
 *
 * Naming and clipping live here, free of Android, so they are unit-testable; the callers own IO
 * and the model-facing wording.
 */
object AppPlaybookRules {

    /** File-name prefix; the Android package name follows it. */
    const val FILE_PREFIX = "app-"

    /** Cold memory only accepts Markdown documents (see [ColdMemoryRules.isMarkdown]). */
    const val FILE_SUFFIX = ".md"

    /**
     * How much of a stored playbook is injected back into a tool result. Deliberately small: it
     * rides on results the model already reads, so an ever-growing note must not inflate them.
     * Kept in step with the guidance to keep playbooks terse.
     */
    const val MAX_SURFACED_CHARS = 1_200

    /** Marker appended when a playbook is clipped, telling the model how to read the rest. */
    const val TRUNCATION_MARKER = "\n…(truncated — memory_read this file for the rest)"

    /**
     * The cold-memory file name for [packageName], or `null` when it is blank or could not be a
     * safe single-segment document name.
     *
     * Android package names are letters, digits, `.` and `_`; anything else (a `/`, a `\`, a
     * space) is rejected rather than sanitized, because a silently-mangled name would point at a
     * different document than the agent believes it is writing. `..` is rejected outright so the
     * name can never walk out of the cold-memory directory (the same guarantee
     * [ColdMemoryRules.isValidWriteName] gives `memory_write`).
     */
    fun fileNameFor(packageName: String): String? {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return null
        if (pkg.contains("..")) return null
        if (!pkg.all { it.isLetterOrDigit() || it == '.' || it == '_' }) return null
        return "$FILE_PREFIX$pkg$FILE_SUFFIX"
    }

    /**
     * Clips a stored playbook to [MAX_SURFACED_CHARS] (after trimming) so it cannot inflate every
     * tool result. A truncated note carries [TRUNCATION_MARKER] rather than ending mid-sentence
     * with no signal.
     */
    fun clipForSurface(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= MAX_SURFACED_CHARS) return trimmed
        return trimmed.take(MAX_SURFACED_CHARS) + TRUNCATION_MARKER
    }
}
