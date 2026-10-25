package com.clma.app.data.vector

/** What is already stored for one chunk of a document. */
data class StoredChunk(
    /** Row id, so an unchanged chunk's embedding can be moved instead of recomputed. */
    val id: Long,
    val index: Int,
    val contentHash: Long,
    /**
     * Which model embedded this row. A plan is only valid against the model that is about to
     * write: a vector is a point in *one* model's space, so a row from another model is not an
     * asset to reuse - it is the wrong answer already stored.
     */
    val modelId: String,
)

/**
 * What to do with one document, decided without reading a single embedding.
 *
 * [SyncPlan.Replace.reuse] maps a *new* chunk index to the row id holding an identical chunk
 * from the previous revision. Re-indexing a document after a one-line edit therefore re-embeds
 * one chunk instead of the whole file, which is the difference between an index that can be kept
 * current in the background and one that has to be rebuilt.
 */
sealed interface SyncPlan {
    /** Byte-for-byte the same document: nothing to embed, nothing to write. */
    data object Unchanged : SyncPlan

    /**
     * The document's chunks changed. [chunks] is the full new revision (an empty list means the
     * document is gone), and [reuse] names the chunks whose vectors can be carried over.
     */
    data class Replace(
        val chunks: List<TextChunk>,
        val reuse: Map<Int, Long>,
    ) : SyncPlan
}

/**
 * The incremental-indexing decision, in one place and free of IO.
 *
 * The point of an index that keeps itself current is that re-embedding costs one model call per
 * chunk, and that call is orders of magnitude more expensive than everything else here. So the
 * cheap question - "did this change?" - is answered from fingerprints alone, and the expensive
 * work is scoped to exactly the chunks that differ.
 */
object IndexSyncRules {

    fun plan(existing: List<StoredChunk>, fresh: List<TextChunk>, modelId: String): SyncPlan {
        val sortedExisting = existing.sortedBy { it.index }

        // A model swap invalidates every stored row at once: the content is unchanged, but the
        // vectors were computed by a different function, and comparing across models is the one
        // mistake that keeps returning plausible answers. Reuse is therefore empty on purpose -
        // carrying a vector over would keep the old model's answer under the new model's name.
        if (sortedExisting.isNotEmpty() && sortedExisting.any { it.modelId != modelId }) {
            return SyncPlan.Replace(chunks = fresh, reuse = emptyMap())
        }

        // Short-circuit on the whole document first: folding the chunk fingerprints gives a
        // document-level fingerprint for free, so the common case (a file that was re-read but
        // not edited) costs one comparison and no per-chunk bookkeeping.
        if (sortedExisting.size == fresh.size) {
            val existingFold = ContentHash.fold(sortedExisting.map { it.contentHash })
            val freshFold = ContentHash.fold(fresh.map { it.contentHash })
            if (sortedExisting.isNotEmpty() && existingFold == freshFold) {
                return SyncPlan.Unchanged
            }
        }

        // Otherwise reuse what can be reused. Position has to match as well as content: two
        // identical paragraphs at different places in a document are different context, and
        // treating them as interchangeable would eventually hand back the wrong surroundings.
        val byPosition = sortedExisting.associateBy { it.index }
        val reuse = buildMap {
            for (chunk in fresh) {
                val previous = byPosition[chunk.index] ?: continue
                if (previous.contentHash == chunk.contentHash) put(chunk.index, previous.id)
            }
        }
        return SyncPlan.Replace(chunks = fresh, reuse = reuse)
    }

    /** True when applying the plan changes nothing at all - no model call, no write. */
    fun isNoOp(plan: SyncPlan): Boolean = plan is SyncPlan.Unchanged

    /**
     * True when the plan can be applied without calling the embedding model. Separate from
     * [isNoOp] because "no embedding" still means writing rows, and a caller that wants to skip
     * the *work* must not confuse the two.
     */
    fun needsNoEmbedding(plan: SyncPlan): Boolean =
        plan is SyncPlan.Replace && plan.chunks.isNotEmpty() && plan.reuse.size == plan.chunks.size
}
