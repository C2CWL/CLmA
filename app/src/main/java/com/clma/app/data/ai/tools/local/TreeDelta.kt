package me.rerere.rikkahub.data.ai.tools.local

/**
 * Pure diff of two node-signature lists, behind `read_window_tree(diff = true)`.
 *
 * Kept free of Android types on purpose: the interesting behaviour (what counts as "new", what
 * counts as "gone", and that duplicates do not fool it) is then unit-testable without a device.
 */
internal object TreeDelta {

    /**
     * Indices into [current] whose signature was not present in [previous] — the nodes that
     * appeared. Order follows [current], so the caller can zip back to its own node objects.
     */
    fun addedIndices(current: List<String>, previous: Set<String>): List<Int> =
        current.withIndex()
            .filter { (_, signature) -> signature !in previous }
            .map { (index, _) -> index }

    /**
     * Signatures present in [previous] but gone from [current], de-duplicated and in the order
     * they were first seen.
     */
    fun removedSignatures(current: Set<String>, previous: List<String>): List<String> =
        previous.filter { it !in current }.distinct()
}
