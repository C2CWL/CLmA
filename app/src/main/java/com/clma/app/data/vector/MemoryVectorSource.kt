package com.clma.app.data.vector

/**
 * The knowledge base (cold memory) as an indexed source.
 *
 * A "source" is a kind of thing that can be embedded and searched, and the only thing sources
 * share is the index: each one decides what a document is, how it is chunked, and what a hit
 * means. Here a document is one Markdown file in the assistant's cold-memory directory, so the
 * identity the model already uses for `memory_read` - the file name - is also the index key, and
 * a hit can be handed straight back to `memory_read` for the full text.
 *
 * The scope is part of the source key (`memory:<workspace>:<dir>`) rather than a filter on the
 * query: two assistants with different knowledge bases must not see each other's notes. The cost
 * is that moving a directory re-embeds it, which is the right trade against "search leaks another
 * assistant's memory".
 *
 * The workspace id is part of the key because a directory *relative to a workspace* is the real
 * scope, not the directory name: two workspaces both using the default `memory/` directory used
 * to collapse into one `memory:memory` source, so opening assistant B wiped assistant A's rows
 * (each sync treats the other's documents as deleted) and `memory_search` happily returned them.
 * [WorkspaceLibrarySource] has carried its workspace id all along; this is the same key shape.
 */
class MemoryVectorSource(
    private val store: VectorIndexStore,
    private val embeddings: EmbeddingService,
    /**
     * How documents are cut up, before the model's own window has a say. What actually gets
     * chunked is [ChunkSpec.fitting] of this against the loaded model - a note indexed with a
     * 512-token model needs smaller chunks than the same note indexed with a 32K one, and the
     * index is keyed by model, so the two can never be mixed up.
     */
    private val chunkSpec: ChunkSpec = ChunkSpec(mode = ChunkingMode.MARKDOWN),
) {

    /** One document of the knowledge base, already read. */
    data class Doc(val name: String, val text: String)

    data class SyncReport(
        val documents: Int,
        val indexed: Int,
        val unchanged: Int,
        val skipped: Int,
        val chunks: Int,
        val embeddedChunks: Int,
        /** Set when nothing could be done; the message is meant for a log line or a UI hint. */
        val reason: String? = null,
    )

    data class SearchOutcome(
        /**
         * False when no embedding model is installed. Carried separately from an empty [hits]
         * because the two mean opposite things: "nothing matched" is an answer, while "there is
         * nothing to search with" is a reason to use the exact-name tools instead.
         */
        val available: Boolean,
        val hits: List<RetrievedChunk>,
        /** How many documents the index knows about, so a caller can say "indexed 3 of 9". */
        val indexedDocuments: Int,
        /** Chunks embedded by a *different* model: the index needs rebuilding before it is trusted. */
        val stale: Boolean,
    )

    /**
     * Brings the index in line with [docs].
     *
     * [docs] must be a *complete, successfully read* listing: a document missing from it is
     * treated as deleted and its rows are dropped. A caller that could not list the directory
     * must not call this with an empty list - that would wipe a good index because a disk hiccup
     * happened at the wrong moment.
     */
    suspend fun sync(
        /** The workspace the directory lives in - part of the index key, see the class doc. */
        workspaceId: String,
        dir: String,
        docs: List<Doc>,
        nowMs: Long,
        /**
         * False when the listing was incomplete (a directory or file could not be read). A
         * document missing from an incomplete listing is not a deleted document, so nothing is
         * forgotten: otherwise one transient read error empties the knowledge-base index.
         */
        forgetMissing: Boolean = true,
    ): SyncReport {
        val model = embeddings.ensureLoaded()
            ?: return SyncReport(
                documents = docs.size,
                indexed = 0,
                unchanged = 0,
                skipped = 0,
                chunks = 0,
                embeddedChunks = 0,
                reason = "no embedding model is installed",
            )

        val source = sourceOf(workspaceId, dir)
        // One-time cleanup for indexes built before the workspace id joined the key: those rows
        // live under `memory:<dir>`, belong to no identifiable workspace, and can neither be
        // shown (that interleaving is the leak) nor credited to an owner. Dropping them lets the
        // next sync rebuild from the real files; the statement is idempotent, so later runs are
        // a no-op.
        store.forgetSource(legacySourceOf(dir))
        var indexed = 0
        var unchanged = 0
        var skipped = 0
        var chunks = 0
        var embedded = 0

        for (doc in docs) {
            // A single enormous file would stall the index for minutes on a model call per chunk.
            // Refusing it keeps the rest of the knowledge base searchable, and the tool says so.
            if (doc.text.length > MAX_DOC_CHARS) {
                skipped++
                continue
            }
            val fresh = TextChunker.chunk(doc.text, chunkSpec.fitting(model.contextTokens))
            if (fresh.isEmpty()) {
                skipped++
                continue
            }
            val plan = store.plan(source, doc.name, fresh, model.modelId)
            if (IndexSyncRules.isNoOp(plan)) {
                unchanged++
                continue
            }
            val written = store.apply(source, doc.name, plan, model.modelId, nowMs) { texts ->
                embeddings.embedDocuments(texts)
            }
            plan as SyncPlan.Replace
            chunks += written
            embedded += plan.chunks.size - plan.reuse.size
            indexed++
        }

        // Documents that vanished from the listing. Done last, and only for this source, so a
        // failure above leaves the previous index intact rather than half-destroyed.
        if (forgetMissing) {
            val live = docs.map { it.name }.toSet()
            store.docKeys(source).filter { it !in live }.forEach { store.forget(source, it) }
        }

        return SyncReport(
            documents = docs.size,
            indexed = indexed,
            unchanged = unchanged,
            skipped = skipped,
            chunks = chunks,
            embeddedChunks = embedded,
        )
    }

    /**
     * Semantic search over the same directory.
     *
     * Returns no hits - rather than throwing - when no model is installed, because "semantic
     * search is not set up" is a normal state for a fresh install and the tool that calls this
     * has to explain it either way.
     */
    suspend fun search(
        workspaceId: String,
        dir: String,
        query: String,
        limit: Int = VectorIndexStore.DEFAULT_LIMIT,
        relativeFloor: Float = VectorSearchRules.DEFAULT_RELATIVE_FLOOR,
    ): SearchOutcome {
        val source = sourceOf(workspaceId, dir)
        val model = embeddings.ensureLoaded()
            ?: return SearchOutcome(available = false, hits = emptyList(), indexedDocuments = 0, stale = false)
        val queryVector = embeddings.embedQuery(query)
        val result = store.search(source, model.modelId, queryVector, limit, relativeFloor)
        val models = store.modelIdsOf(source)
        return SearchOutcome(
            available = true,
            hits = result.asHits(),
            indexedDocuments = store.docKeys(source).size,
            stale = models.any { it != model.modelId },
        )
    }

    companion object {
        const val KIND = "memory"

        /**
         * The source key for one knowledge base directory of one workspace.
         *
         * The workspace id is not decoration: the directory is stored per assistant, so the same
         * string ("memory") is the default for every assistant in the app. Without the id two
         * workspaces shared one key, which both leaked notes across assistants and made each
         * assistant's sync delete the other's rows (they look like deleted documents).
         */
        fun sourceOf(workspaceId: String, dir: String): String = "$KIND:$workspaceId:$dir"

        /**
         * The pre-workspace key shape (`memory:<dir>`), kept only so [sync] can delete the rows
         * that were written under it. Never used to read or write.
         */
        fun legacySourceOf(dir: String): String = "$KIND:$dir"

        /**
         * Roughly 1.5 MB of text. Above this a single document costs hundreds of embedding calls
         * and minutes of CPU on a phone; the knowledge base is notes, not datasets, and a file
         * this size is almost always an accident.
         */
        const val MAX_DOC_CHARS = 1_500_000
    }
}
