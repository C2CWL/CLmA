package com.clma.app.data.vector

/**
 * How much of an indexable collection one round may read, and which documents a round may forget.
 *
 * Two features now need the same two rules - the file library (P4) and conversation history (P5) -
 * and both of them are the kind of rule that fails silently and expensively:
 *
 *  - a round that ignores its budget embeds for an hour on a phone;
 *  - a round that treats "not read this round" as "deleted" deletes the previous round's work, so
 *    the index can never hold more than one round.
 *
 * Neither failure raises anything. The first is slow, the second looks like "search is bad". So the
 * rules live in one place, generic over what a document is, with tests, rather than being written
 * twice from the same memory.
 */
object RoundBudget {

    /**
     * Chooses what to read this round.
     *
     * Documents the index has never seen come first, then everything else in key order. That
     * ordering is what lets a collection bigger than one round's budget converge: the next round
     * picks up where this one stopped instead of re-reading the same prefix forever.
     *
     * [estimateChars] must be cheap - it runs for every candidate, before anything is read. Being
     * wrong costs budget, not correctness.
     */
    fun <T> plan(
        items: List<T>,
        key: (T) -> String,
        sizeOf: (T) -> Long,
        capOf: (T) -> Long,
        estimateChars: (T) -> Int,
        indexed: Set<String>,
        budgetChars: Int,
        maxItems: Int,
    ): Round<T> {
        val tooBig = items.filter { sizeOf(it) > capOf(it) }.map(key)
        val eligible = items
            .filter { sizeOf(it) <= capOf(it) }
            .sortedWith(compareBy({ key(it) in indexed }, { key(it) }))

        val take = mutableListOf<T>()
        var chars = 0L
        for (item in eligible) {
            if (take.size >= maxItems) break
            val cost = estimateChars(item).toLong()
            // Always take at least one: a round that plans nothing because the first item is large
            // would never make progress on a collection of large items.
            if (take.isNotEmpty() && chars + cost > budgetChars) break
            take += item
            chars += cost
        }
        return Round(take = take, deferred = eligible.size - take.size, tooBig = tooBig)
    }

    /**
     * Which indexed documents a round should forget.
     *
     * [seen] is everything the walk mentioned, read this round or not. Only a document absent from
     * it counts as deleted, because that is the only evidence of deletion available. This is the
     * rule described at the top of the file: the one that keeps "not read this round" from meaning
     * "gone".
     */
    fun toForget(indexed: Collection<String>, seen: Collection<String>): List<String> =
        indexed.filter { it !in seen }

    data class Round<T>(
        val take: List<T>,
        /** Accepted items that did not fit this round's budget. */
        val deferred: Int,
        /** Items refused by a size cap, by key. Reported so a missing item can be explained. */
        val tooBig: List<String>,
    )
}
