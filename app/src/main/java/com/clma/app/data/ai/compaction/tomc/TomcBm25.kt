package com.clma.app.data.ai.compaction.tomc

import kotlin.math.ln

/**
 * Tokenizer for the retrieval half of TOMC.
 *
 * Chinese has no spaces, so a single character is often a morpheme and a pair is the smallest
 * unit that carries meaning: CJK text yields unigrams AND bigrams, Latin text yields
 * lowercased words of 2..24 chars. Everything else is a separator. No dictionary, no model,
 * no Android API — the whole thing has to stay a pure function so it can run in a unit test
 * and in a 2ms coroutine on the phone.
 */
internal object TomcTokenizer {

    /** Latin words longer than this are treated as opaque ids (hashes, urls), not vocabulary. */
    private const val MAX_WORD = 24
    private const val MIN_WORD = 2

    private val STOPWORDS: Set<String> = setOf(
        "the", "a", "an", "of", "to", "in", "is", "are", "was", "were", "and", "or", "for",
        "on", "at", "by", "it", "this", "that", "with", "as", "be", "from", "not", "no", "do",
        "的", "了", "是", "在", "和", "与", "也", "就", "都", "而", "及", "或", "我", "你",
    )

    fun tokens(text: String): List<String> {
        val out = ArrayList<String>(text.length / 3 + 8)
        val word = StringBuilder()
        var previousCjk: Char? = null

        fun flush() {
            if (word.length in MIN_WORD..MAX_WORD) {
                val w = word.toString().lowercase()
                if (w !in STOPWORDS) out += w
            }
            word.setLength(0)
        }

        for (raw in text) {
            val c = raw.lowercaseChar()
            when {
                c.isCjk() -> {
                    flush()
                    if (c.toString() !in STOPWORDS) out += c.toString()
                    previousCjk?.let { out += "$it$c" }
                    previousCjk = c
                }

                c.isLetterOrDigit() -> {
                    previousCjk = null
                    word.append(c)
                }

                else -> {
                    previousCjk = null
                    flush()
                }
            }
        }
        flush()
        return out
    }

    private fun Char.isCjk(): Boolean =
        this in '\u4e00'..'\u9fff' || this in '\u3400'..'\u4dbf'
}

/**
 * Okapi BM25 over conversation segments, with the three adaptations TOMC actually needs:
 *
 *  1. **No-signal terms are dropped.** A term present in every segment has idf 0 and would only
 *     add noise, so [idf] returns 0 and it contributes nothing.
 *  2. **Nothing matched means recency, not arbitrariness.** [rank] falls back to newest-first
 *     instead of returning an empty list — an empty list would let the caller silently drop
 *     the whole conversation.
 *  3. **The caller's budget decides how many survive.** [rank] takes a `limit` rather than
 *     assuming a constant; the compressor computes that limit from characters, so a tight
 *     budget yields few precise hits instead of many truncated ones.
 */
internal class TomcBm25(documents: List<List<String>>) {

    private val docs: List<List<String>> = documents
    private val lengths: IntArray = IntArray(docs.size) { docs[it].size }
    private val averageLength: Double =
        if (docs.isEmpty() || lengths.all { it == 0 }) 0.0 else lengths.average()

    private val frequencies: List<Map<String, Int>> = docs.map { tokens ->
        val counts = HashMap<String, Int>(tokens.size * 2)
        for (token in tokens) counts[token] = (counts[token] ?: 0) + 1
        counts
    }

    private val documentFrequency: Map<String, Int> = HashMap<String, Int>().also { acc ->
        for (counts in frequencies) {
            for (term in counts.keys) acc[term] = (acc[term] ?: 0) + 1
        }
    }

    val size: Int get() = docs.size

    private fun idf(term: String): Double {
        val n = docs.size
        if (n == 0) return 0.0
        val df = documentFrequency[term] ?: return 0.0
        // Ubiquitous term: nothing to discriminate between segments.
        if (df >= n) return 0.0
        return ln(1.0 + (n - df + 0.5) / (df + 0.5))
    }

    fun score(query: List<String>, index: Int): Double {
        if (index !in docs.indices || averageLength <= 0.0) return 0.0
        val counts = frequencies[index]
        val norm = K1 * (1.0 - B + B * lengths[index] / averageLength)
        var total = 0.0
        for (term in query.toSet()) {
            val tf = (counts[term] ?: continue).toDouble()
            total += idf(term) * tf * (K1 + 1.0) / (tf + norm)
        }
        return total
    }

    /**
     * Indices, best first. Ties keep document order (older first) so the same input always
     * produces the same selection — a compressor that reshuffles identical context breaks the
     * provider's prompt cache for no benefit.
     */
    /** Indices that actually matched, best first, with their scores. Empty when nothing did. */
    fun rankScored(query: List<String>): List<Pair<Int, Double>> =
        docs.indices
            .map { it to score(query, it) }
            .filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })

    fun rank(query: List<String>, limit: Int = docs.size): List<Int> {
        if (docs.isEmpty() || limit <= 0) return emptyList()
        val matched = rankScored(query).map { it.first }
        if (matched.isNotEmpty()) return matched.take(limit)
        return docs.indices.reversed().take(limit)
    }

    companion object {
        /** Standard Okapi constants; tuned for short, chat-sized segments. */
        const val K1 = 1.2
        const val B = 0.75
    }
}
