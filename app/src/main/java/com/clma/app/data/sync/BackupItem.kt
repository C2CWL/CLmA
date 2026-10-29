package com.clma.app.data.sync

import kotlinx.serialization.Serializable

/**
 * One selectable slice of app data in a local / WebDAV / S3 backup.
 *
 * The enum is persisted inside [com.clma.app.data.datastore.WebDavConfig] and
 * [com.clma.app.data.sync.s3.S3Config] by *name*, so [FILES] must never be removed:
 * settings written before the granular split carried `["DATABASE","FILES"]`, and dropping the
 * value would make those settings fail to decode (the tolerant decoder would fall back to a
 * default config and silently lose the WebDAV/S3 credentials). [FILES] is therefore kept as a
 * legacy umbrella and is transparently expanded by [normalize].
 */
@Serializable
enum class BackupItem {
    DATABASE,

    /** Legacy pre-granular "all files" umbrella. Never shown in the picker. */
    FILES,

    SKILLS,
    UPLOAD,
    IMAGES,
    VIDEOS,
    FONTS,
    WORKSPACES,

    /** Assistant-owned cold memory under files/assistant_memory/. */
    ASSISTANT_MEMORY,
    TOOL_OUTPUTS,

    /** Non-[com.clma.app.data.datastore.Settings] preference stores (see [BackupLayout]). */
    CONFIG,

    /** Encrypted secrets (agent, workflow, skills) protected with password. */
    SECRETS,
    ;

    companion object {
        /** Picker order. [FILES] is intentionally absent. */
        val selectable: List<BackupItem> = listOf(
            DATABASE,
            SKILLS,
            UPLOAD,
            IMAGES,
            VIDEOS,
            FONTS,
            WORKSPACES,
            ASSISTANT_MEMORY,
            TOOL_OUTPUTS,
            CONFIG,
            SECRETS,
        )

        /** What a legacy [FILES] selection covered, plus the file folders added with it. */
        private val fileItems: List<BackupItem> = listOf(
            SKILLS,
            UPLOAD,
            IMAGES,
            VIDEOS,
            FONTS,
            WORKSPACES,
            ASSISTANT_MEMORY,
            TOOL_OUTPUTS,
        )

        /**
         * Expand the legacy [FILES] umbrella, drop anything not in [selectable] and return the
         * result in a stable picker order. Every read site funnels through here so an old
         * persisted selection keeps backing up everything a user would expect.
         */
        fun normalize(items: Collection<BackupItem>): List<BackupItem> {
            val set = items.toMutableSet()
            if (set.remove(FILES)) set.addAll(fileItems)
            return selectable.filter { it in set }
        }
    }
}
