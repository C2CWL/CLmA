package com.clma.app.data.vector

import com.clma.llamacpp.VectorMath

/**
 * One retrieved chunk, with everything needed to show it or to re-read its document.
 *
 * Lives here, next to the ranking, rather than with the store: it is the currency both the store
 * and the source adapters pass around, and keeping it in a file with no Room or Android imports
 * is what makes [asHits] testable on a plain JVM.
 */
data class RetrievedChunk(
    val source: String,
    val docKey: String,
    val chunkIndex: Int,
    val text: String,
    val score: Float,
)

/** One retrieved item and its similarity to the query. */
data class ScoredChunk<T>(val item: T, val score: Float)

/**
 * The outcome of a search, including how much of the index was unusable.
 *
 * [skipped] is carried rather than logged and forgotten because a non-zero value means the index
 * and the active model have drifted apart - chunks embedded by a previous model are mixed into
 * the same table. That is a recoverable state (a re-index fixes it), but it has to be visible,
 * otherwise search just quietly gets worse.
 */
data class VectorSearchResult<T>(val hits: List<ScoredChunk<T>>, val skipped: Int)

/**
 * Unwraps a ranked result into plain hits, carrying each score onto its chunk.
 *
 * A named function rather than an inline `map` because the layering is easy to get backwards -
 * [VectorSearchResult] holds `ScoredChunk`s, not the chunks themselves - and both call sites got
 * it wrong in exactly opposite ways before this existed. Tested, so the next reader has one place
 * to look instead of two places to guess.
 */
fun VectorSearchResult<RetrievedChunk>.asHits(): List<RetrievedChunk> =
    hits.map { it.item.copy(score = it.score) }

/**
 * Ranks candidates against a query vector.
 *
 * Brute force on purpose: 768 dimensions by ten thousand chunks is about eight million
 * multiply-adds, i.e. single-digit milliseconds with the vectors in memory, which is well below
 * the cost of the embedding call that produced the query. An approximate-nearest-neighbour
 * structure would add a build step, a recall/latency trade-off to tune, and a persistence
 * format of its own, to save a millisecond. Revisit at a hundred thousand chunks, not before.
 *
 * ## Why there is no absolute score threshold
 *
 * Embedding models do not share a similarity scale. Measured on EmbeddingGemma 2, two unrelated
 * sentences ("the cat sat on the mat" / "quarterly tax filing deadlines") still score 0.56, and
 * an unrelated noun pair ("猫" / "汽车发动机") scores 0.78 - so any hand-picked constant like
 * "keep hits above 0.8" is a fact about one model's output distribution, not about relevance.
 * The knob here is therefore *relative to the best hit in this very query*
 * ([relativeFloor]), which means the same number keeps working when the model is swapped.
 *
 * Pure, so the ranking policy is unit-tested rather than argued about.
 */
object VectorSearchRules {

    /**
     * Fraction of the best hit's score a chunk must reach to be returned.
     *
     * 0 means "return the top [limit] and let the caller decide"; the usual setting is around
     * 0.9, which drops the long tail of near-miss chunks while never dropping the best hit.
     * Only meaningful for non-negative scores, so the filter is applied only when the best
     * score is positive.
     */
    const val DEFAULT_RELATIVE_FLOOR = 0f

    fun <T> rank(
        query: FloatArray,
        candidates: List<Pair<T, FloatArray>>,
        limit: Int,
        relativeFloor: Float = DEFAULT_RELATIVE_FLOOR,
    ): VectorSearchResult<T> {
        require(limit > 0) { "limit must be positive" }
        require(query.isNotEmpty()) { "the query vector is empty" }
        require(relativeFloor in 0f..1f) { "relativeFloor must be in [0, 1]" }

        var skipped = 0
        val scored = ArrayList<ScoredChunk<T>>(candidates.size)
        for ((item, vector) in candidates) {
            // A width that differs from the query's cannot be scored at all. Skipping (rather
            // than throwing) keeps search alive during the window where a model has just been
            // replaced and the re-index has not caught up; the count is reported upward.
            if (vector.size != query.size) {
                skipped++
                continue
            }
            val score = VectorMath.cosine(query, vector)
            // A non-finite score means a poisoned vector got into the index (or the query itself
            // is one): an engine that emitted NaN/Inf, which nothing upstream rejected. Treating
            // it as unusable rather than ranked keeps one bad row from surfacing as a hit - and
            // since a comparison involving NaN is never less than anything, an unguarded NaN
            // sorts *above* every honest score.
            if (!score.isFinite()) {
                skipped++
                continue
            }
            scored += ScoredChunk(item, score)
        }

        scored.sortByDescending { it.score }
        if (scored.size > limit) scored.subList(limit, scored.size).clear()

        val best = scored.firstOrNull()?.score ?: 0f
        val filtered = if (relativeFloor > 0f && best > 0f) {
            scored.filter { it.score >= best * relativeFloor }
        } else {
            scored
        }
        return VectorSearchResult(hits = filtered, skipped = skipped)
    }
}
