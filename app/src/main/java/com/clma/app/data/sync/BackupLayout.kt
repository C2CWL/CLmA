package me.rerere.rikkahub.data.sync

/**
 * Where each file-backed [BackupItem] lives on disk and inside the backup archive.
 *
 * Pure data / pure functions (no Android types) so the mapping is unit-testable off-device.
 * Every file entry is stored under the same relative path it has below the app's `files/`
 * directory (e.g. `skills/foo/SKILL.md`), which is exactly what [PendingRestore] expects when
 * it installs a staged restore.
 */
internal object BackupLayout {

    /** Items whose whole `files/<dir>` subtree is archived as `<dir>/<relative>`. */
    val subtreeFolders: List<Pair<BackupItem, String>> = listOf(
        BackupItem.SKILLS to "skills",
        BackupItem.UPLOAD to "upload",
        BackupItem.IMAGES to "images",
        BackupItem.VIDEOS to "videos",
        BackupItem.FONTS to "fonts",
        BackupItem.TOOL_OUTPUTS to "tool_outputs",
    )

    private val subtreeByDir: Map<String, BackupItem> =
        subtreeFolders.associate { (item, dir) -> dir to item }

    /** Per-workspace area archived as `workspaces/<root>/files/<relative>`. */
    const val WORKSPACES_ROOT = "workspaces"
    const val WORKSPACE_FILES_DIR = "files"

    /**
     * The on-device agent's `~` ([me.rerere.rikkahub.data.ai.tools.local.AgentWorkspace]),
     * archived as `workspace/<relative>`.
     */
    const val AGENT_WORKSPACE_DIR = "workspace"

    const val DATASTORE_DIR = "datastore"

    /**
     * Only these named preference stores are archived — the whole `datastore/` directory is not,
     * because `settings.preferences_pb` is written from `settings.json` instead (which runs the
     * usual migration pipeline) and other stores (e.g. SAF volume grants) are device-specific.
     */
    val configStores: List<String> = listOf(
        "external_automation",
        "browser_prefs",
        "notification_listener",
        "termux_prefs",
        "tool_approval",
        "telegram_bot",
    )

    val configStoreFiles: Set<String> = configStores.mapTo(LinkedHashSet()) { "$it.preferences_pb" }

    /**
     * Which [BackupItem] owns an archive entry, or `null` when the entry is not a file
     * attachment we are willing to write back. Doubles as the restore-side allow-list: the
     * staged restore skips any entry this returns `null` for.
     */
    fun itemForEntry(name: String): BackupItem? {
        val separator = name.indexOf('/')
        if (separator <= 0 || separator == name.length - 1) return null
        val top = name.substring(0, separator)
        val rest = name.substring(separator + 1)
        // Reject empty / dot segments up front; PendingRestore.resolveInside is the safety floor.
        if (rest.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null

        subtreeByDir[top]?.let { return it }

        return when (top) {
            AGENT_WORKSPACE_DIR -> BackupItem.WORKSPACES
            WORKSPACES_ROOT -> {
                val segments = rest.split('/')
                // Only the per-workspace `files/` area is ever archived (never `linux/`, `tmp/`).
                if (segments.size >= 2 && segments[1] == WORKSPACE_FILES_DIR) BackupItem.WORKSPACES else null
            }

            DATASTORE_DIR ->
                if (rest.indexOf('/') < 0 && rest in configStoreFiles) BackupItem.CONFIG else null

            else -> null
        }
    }
}
