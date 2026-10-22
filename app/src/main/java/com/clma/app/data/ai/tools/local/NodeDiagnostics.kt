package me.rerere.rikkahub.data.ai.tools.local

/**
 * "Your selector missed — did you mean one of these?"
 *
 * A bare `no_match` is the single most common way a screen-automation run stalls: the model has
 * one guess, it is wrong, and (without a suggestion) it either retries the same value or burns a
 * whole `read_window_tree` to find out why. These helpers rank the values actually present on
 * the same axis so the tool can answer with the three closest candidates instead.
 *
 * Pure and cheap: similarity runs over every visible node's value on a miss, on the
 * AccessibilityService thread, so it uses a character-bigram Dice coefficient (no word
 * segmentation, so it works for CJK as well as Latin) rather than an expensive edit-distance.
 */
internal object NodeDiagnostics {

    /** How many suggestions a miss returns. */
    const val MAX_CANDIDATES = 3

    /** Below this a candidate is noise, not a suggestion. */
    const val MIN_SCORE = 0.34

    /** A candidate value, with its position in the caller's pool and its similarity score. */
    data class Ranked(val index: Int, val value: String, val score: Double)

    /**
     * Similarity in `0.0..1.0`. Exact (case-insensitive) match is `1.0`; a containment is scaled
     * by the length ratio so the closer length wins; otherwise the Dice coefficient over
     * character bigrams. Strings shorter than two characters can only match exactly.
     */
    fun similarity(wanted: String, actual: String): Double {
        val a = wanted.trim().lowercase()
        val b = actual.trim().lowercase()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (b.contains(a) || a.contains(b)) {
            val shorter = minOf(a.length, b.length).toDouble()
            val longer = maxOf(a.length, b.length).toDouble()
            return 0.7 + 0.3 * (shorter / longer)
        }
        val gramsA = bigrams(a)
        val gramsB = bigrams(b)
        val totalA = gramsA.values.sum()
        val totalB = gramsB.values.sum()
        if (totalA == 0 || totalB == 0) return 0.0
        var shared = 0
        for ((gram, count) in gramsA) {
            val other = gramsB[gram] ?: continue
            shared += minOf(count, other)
        }
        return (2.0 * shared) / (totalA + totalB)
    }

    private fun bigrams(value: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (i in 0 until value.length - 1) {
            val gram = value.substring(i, i + 2)
            out[gram] = (out[gram] ?: 0) + 1
        }
        return out
    }

    /**
     * The best candidates from [values], highest score first. Duplicate values are collapsed and
     * ties keep their original order, so the suggestion list is deterministic.
     */
    fun rank(
        wanted: String,
        values: List<String>,
        limit: Int = MAX_CANDIDATES,
        minScore: Double = MIN_SCORE,
    ): List<Ranked> = values.withIndex()
        .map { (index, value) -> Ranked(index, value, similarity(wanted, value)) }
        .filter { it.score >= minScore }
        .distinctBy { it.value }
        .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.index })
        .take(limit)
}
