package me.rerere.rikkahub.subagent

/**
 * P2-23 (D7) — the rules behind filing sub-agent conversations into a protected folder.
 *
 * Pure by construction: no Room, no Android, no `kotlin.uuid` (ids are plain strings here, so this
 * file compiles in the local harness and needs no experimental opt-in). The "which folder" choice
 * lives in [me.rerere.rikkahub.data.datastore.Settings] as an `assistantId -> folderId` map — kept
 * there rather than on `FolderEntity` so no `AppDatabase` identity hash moves (that constant is
 * pinned by `ImportedDatabaseReconciler`).
 *
 * The list view already shows only *unfiled* conversations, so filing a sub-agent conversation is
 * what keeps it out of the main list — this object is the whole "where does it go, and may that
 * folder be deleted" policy.
 */
object SubAgentArchiveRules {

    private val UUID_RE = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )

    /**
     * The folder [assistantId]'s sub-agent conversations are filed into, or null for "do not file".
     * A missing entry — or a stored value that is not a UUID — reads as "do not file" rather than
     * throwing, so a hand-edited setting can never break a dispatch.
     */
    fun targetFolderId(targets: Map<String, String>, assistantId: String): String? =
        targets[assistantId]?.takeIf { UUID_RE.matches(it) }

    /**
     * A folder may not be deleted while it is the assistant's archive target **and** holds at least
     * one conversation. An empty one stays deletable, so a folder created by mistake can still be
     * removed: the protection is about not orphaning filed conversations, not about the name.
     */
    fun isProtected(isArchiveTarget: Boolean, conversationCount: Int): Boolean =
        isArchiveTarget && conversationCount > 0
}
