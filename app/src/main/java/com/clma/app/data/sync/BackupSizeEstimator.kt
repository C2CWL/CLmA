package com.clma.app.data.sync

import java.io.File

/**
 * Rough, pre-compression size of each [BackupItem], read from the same places
 * [BackupManager.createBackup] archives. Pure `java.io.File` code so it is unit-testable.
 */
internal object BackupSizeEstimator {

    /** Total bytes of all regular files below [dir]; 0 when it does not exist. */
    fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    /**
     * @param filesDir the app's `files/` directory.
     * @param databaseFiles the main database file plus its `-wal`, if any.
     */
    fun estimate(filesDir: File, databaseFiles: List<File>): Map<BackupItem, Long> {
        val subtreeDirs = BackupLayout.subtreeFolders.toMap()
        return BackupItem.selectable.associateWith { item ->
            when (item) {
                BackupItem.DATABASE -> databaseFiles.filter { it.isFile }.sumOf { it.length() }
                BackupItem.CONFIG -> BackupLayout.configStoreFiles.sumOf { name ->
                    File(filesDir, "${BackupLayout.DATASTORE_DIR}/$name").takeIf { it.isFile }?.length() ?: 0L
                }
                BackupItem.WORKSPACES -> workspacesSize(filesDir)
                else -> subtreeDirs[item]?.let { directorySize(File(filesDir, it)) } ?: 0L
            }
        }
    }

    /** Sum of [sizes] for the [selected] items (legacy FILES expanded via [BackupItem.normalize]). */
    fun totalFor(sizes: Map<BackupItem, Long>, selected: Collection<BackupItem>): Long =
        BackupItem.normalize(selected).sumOf { sizes[it] ?: 0L }

    /** Same areas as BackupManager.addWorkspaces: each `workspaces/<root>/files` plus the agent's `~`. */
    private fun workspacesSize(filesDir: File): Long {
        val roots = File(filesDir, BackupLayout.WORKSPACES_ROOT).listFiles().orEmpty()
        val perWorkspace = roots
            .filter { it.isDirectory }
            .sumOf { directorySize(File(it, BackupLayout.WORKSPACE_FILES_DIR)) }
        return perWorkspace + directorySize(File(filesDir, BackupLayout.AGENT_WORKSPACE_DIR))
    }
}
