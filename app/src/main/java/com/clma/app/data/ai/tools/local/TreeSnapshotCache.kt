package me.rerere.rikkahub.data.ai.tools.local

/**
 * The node tree as of the last `read_window_tree` in each conversation.
 *
 * Two things fall out of remembering it, both of which exist so the model does NOT have to
 * remember anything:
 *
 *  - a re-read of a screen that did not move is answered with a tiny `unchanged: true` envelope
 *    instead of the whole tree again (the exact waste the agent doctrine calls out);
 *  - an explicit `diff: true` read returns only the nodes that appeared / disappeared since the
 *    previous read, instead of a fresh 500-node dump.
 *
 * Only node *signatures* are kept (the structural parts the screen-state hash already uses), not
 * the nodes themselves, so an entry is a few KB rather than a few MB. Scoped to the current turn
 * ([clear] runs at the start of a generation), so a diff can never span two turns and a model
 * returning to a screen after a compaction always gets the full tree.
 */
internal object TreeSnapshotCache {

    private const val MAX_CONVERSATIONS = 8

    data class Entry(val hash: Long, val signatures: List<String>)

    private val entries = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
                size > MAX_CONVERSATIONS
        }
    )

    fun get(conversationId: String?): Entry? = conversationId?.let { entries[it] }

    fun put(conversationId: String?, hash: Long, signatures: List<String>) {
        if (conversationId == null) return
        entries[conversationId] = Entry(hash, signatures)
    }

    /** Drops every snapshot; called at the start of a generation. */
    fun clear() {
        entries.clear()
    }
}
