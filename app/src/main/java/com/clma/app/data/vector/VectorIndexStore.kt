package com.clma.app.data.vector

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads and writes the vector index.
 *
 * The embedding model is passed in as a lambda rather than held as a dependency: this class then
 * has no idea that embeddings run on llama.cpp, which keeps the index usable from a unit test
 * with a fake embedder and leaves the door open for a different (or remote) model later.
 *
 * All the decisions - what changed, what to re-embed, how to rank - live in the pure rules
 * objects ([IndexSyncRules], [VectorSearchRules]); this class is only the part that has to talk
 * to SQLite.
 */
class VectorIndexStore(
    private val database: VectorIndexDatabase,
    private val dao: VectorChunkDao = database.vectorChunkDao(),
) {

    /**
     * Brings one document up to date and returns the number of rows written.
     *
     * [embed] receives only the chunks that actually need a new vector, in order, and must return
     * one vector per input. Chunks named in `plan.reuse` are carried over from their existing
     * rows, so editing one line of a long document costs one embedding call, not one per chunk.
     */
    suspend fun apply(
        source: String,
        docKey: String,
        plan: SyncPlan,
        modelId: String,
        nowMs: Long,
        embed: suspend (List<String>) -> List<FloatArray>,
    ): Int = withContext(Dispatchers.IO) {
        if (plan !is SyncPlan.Replace) return@withContext 0

        // Read the reusable vectors before deleting anything: they live in the very rows the
        // replacement is about to drop.
        val reused = plan.reuse.mapValues { (_, id) -> dao.embeddingOf(id) }
        val pending = plan.chunks.filter { it.index !in plan.reuse }
        val fresh = if (pending.isEmpty()) emptyList() else embed(pending.map { it.text })
        require(fresh.size == pending.size) {
            "the embedder returned ${fresh.size} vectors for ${pending.size} chunks"
        }
        fresh.forEach { vector ->
            require(vector.all { it.isFinite() }) {
                "the embedder returned a vector with NaN/Inf; the model file or backend is " +
                    "producing garbage, and storing it would poison every later comparison"
            }
        }
        val freshByIndex = pending.indices.associate { pending[it].index to fresh[it] }

        val dim = (fresh.firstOrNull()?.size ?: reused.values.firstOrNull()?.let { bytes ->
            bytes?.let { EmbeddingCodec.decode(it).size }
        } ?: 0)
        fresh.forEach { vector ->
            require(vector.size == dim) {
                "the embedder returned mixed widths (${vector.size} and $dim); one model produced them"
            }
        }

        val rows = plan.chunks.mapNotNull { chunk ->
            val embedding = freshByIndex[chunk.index]
                ?: reused[chunk.index]?.let { EmbeddingCodec.decode(it) }
                ?: return@mapNotNull null
            VectorChunkEntity(
                source = source,
                docKey = docKey,
                chunkIndex = chunk.index,
                contentHash = chunk.contentHash,
                modelId = modelId,
                dim = embedding.size,
                embedding = EmbeddingCodec.encode(embedding),
                text = chunk.text,
                updatedAt = nowMs,
            )
        }

        database.withTransaction {
            dao.deleteDoc(source, docKey)
            if (rows.isNotEmpty()) dao.insert(rows)
        }
        rows.size
    }

    /** The chunks to embed for [docKey], or [SyncPlan.Unchanged]. */
    suspend fun plan(
        source: String,
        docKey: String,
        fresh: List<TextChunk>,
        modelId: String,
    ): SyncPlan = withContext(Dispatchers.IO) {
        IndexSyncRules.plan(dao.fingerprints(source, docKey).map { it.toStoredChunk() }, fresh, modelId)
    }

    /**
     * Brute-force similarity search over one source.
     *
     * The whole candidate set is decoded per query. At the sizes this is built for (thousands of
     * chunks, 768 floats each) that is a few hundred microseconds of decoding against a
     * millisecond of arithmetic, so a cache would add invalidation bugs to save little; if the
     * candidate set ever grows by an order of magnitude, the fix is a held-in-memory matrix, not
     * an approximate index.
     */
    suspend fun search(
        source: String,
        modelId: String,
        query: FloatArray,
        limit: Int = DEFAULT_LIMIT,
        relativeFloor: Float = VectorSearchRules.DEFAULT_RELATIVE_FLOOR,
    ): VectorSearchResult<RetrievedChunk> = withContext(Dispatchers.IO) {
        val candidates = dao.candidates(source, modelId).map {
            RetrievedChunk(
                source = it.source,
                docKey = it.docKey,
                chunkIndex = it.chunkIndex,
                text = it.text,
                score = 0f,
            ) to EmbeddingCodec.decode(it.embedding)
        }
        val ranked = VectorSearchRules.rank(
            query = query,
            candidates = candidates,
            limit = limit,
            relativeFloor = relativeFloor,
        )
        // Passed straight through: `VectorSearchResult<T>` already carries its hits wrapped in
        // their scores, so re-mapping here is how this line was got wrong twice.
        ranked
    }

    /** Forgets one document. Used when the source reports it deleted. */
    suspend fun forget(source: String, docKey: String) = withContext(Dispatchers.IO) {
        dao.deleteDoc(source, docKey)
    }

    /** Forgets a whole source, e.g. when the assistant that owns it is removed. */
    suspend fun forgetSource(source: String) = withContext(Dispatchers.IO) {
        dao.deleteSource(source)
    }

    suspend fun docKeys(source: String): List<String> = withContext(Dispatchers.IO) { dao.docKeys(source) }

    /** Same list, restricted to rows the current model produced — see [VectorChunkDao.docKeysOfModel]. */
    suspend fun docKeys(source: String, modelId: String): List<String> =
        withContext(Dispatchers.IO) { dao.docKeysOfModel(source, modelId) }

    suspend fun modelIdsOf(source: String): List<String> = withContext(Dispatchers.IO) {
        dao.modelIdsOfSource(source)
    }

    /**
     * Sources whose key begins with [prefix], exactly.
     *
     * The DAO's LIKE may over-report (see there); the exact filter is applied here so callers can
     * treat the result as precise. Used to drop a source that has moved - a library pointed at a
     * different directory leaves no trace behind.
     */
    suspend fun sourcesWithPrefix(prefix: String): List<String> = withContext(Dispatchers.IO) {
        dao.sourcesWithPrefix(prefix).filter { it.startsWith(prefix) }
    }

    companion object {
        /** Enough context for a model to act on, small enough not to crowd out the conversation. */
        const val DEFAULT_LIMIT = 8
    }
}
