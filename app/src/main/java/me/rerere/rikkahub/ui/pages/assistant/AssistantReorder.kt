package me.rerere.rikkahub.ui.pages.assistant

/**
 * List moves for the assistant settings reorder.
 *
 * sh.calvin.reorderable's `onMove` must not return until the backing list has
 * actually changed. The library then waits (up to a second) for the lazy
 * layout to catch up, and until it does the dragged row sits on a predicted
 * offset. Returning before the move — a disk write that only later updates
 * the list — applies that offset and then snaps it back. That snap is the twitch.
 *
 * Persistence is intentionally not in here. Callers move a snapshot list
 * inside `onMove` and write settings once the gesture ends.
 */
internal object AssistantReorder {
    fun <T> move(items: MutableList<T>, from: Int, to: Int) {
        if (from == to) return
        if (from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
    }

    /**
     * Apply a dragged key order onto the latest persisted list.
     * Items created while the gesture was in flight stay, appended in their
     * current relative order. Items deleted while dragging are not resurrected.
     */
    fun <T, K> merge(current: List<T>, orderedKeys: List<K>, key: (T) -> K): List<T> {
        if (orderedKeys.isEmpty() || current.isEmpty()) return current
        val rank = HashMap<K, Int>(orderedKeys.size)
        orderedKeys.forEachIndexed { index, id ->
            rank.putIfAbsent(id, index)
        }
        return current.sortedBy { rank[key(it)] ?: Int.MAX_VALUE }
    }
}
