package com.clma.app.data.vector

/**
 * Conversation history as an indexed source.
 *
 * One document per message, keyed `<conversationId>|<messageId>`. The keyword index this replaces
 * (an FTS5 table) made the same choice, for the same reason: history search answers "where did we
 * say this", and an answer that names a conversation but not the message is only half an answer.
 * A message may still be *several chunks* - it can be long, and no model's window is - so the
 * document key is the message while the chunks are the message's paragraphs.
 *
 * Unlike the other sources this one is global rather than scoped: history search was never
 * per-assistant, and a user looking for what they discussed before does not care which assistant
 * it was with. There is therefore exactly one source key, and [sync]'s `scope` parameter is what
 * keeps a per-conversation sync from taking the rest of the history with it.
 */
class ConversationVectorSource(
    private val store: VectorIndexStore,
    private val embeddings: EmbeddingService,
) {

    /** One message, already read. */
    data class Doc(val conversationId: String, val messageId: String, val text: String) {
        val docKey: String get() = ConversationSearchRules.docKeyOf(conversationId, messageId)
    }

    data class SyncReport(
        val documents: Int,
        val indexed: Int,
        val unchanged: Int,
        val skipped: Int,
        val chunks: Int,
        val embeddedChunks: Int,
        val reason: String? = null,
    )

    /**
     * Brings the index in line with [docs].
     *
     * [seen] is every message the walk mentioned, read this round or not; a key absent from it is
     * the only evidence of deletion. [scope] narrows which deletions are allowed to happen: null
     * means the walk covered the whole history, while a conversation id means only that
     * conversation's messages may be forgotten - the per-turn sync reads one conversation and must
     * not be able to delete every other one.
     */
    suspend fun sync(
        docs: List<Doc>,
        seen: Set<String>,
        nowMs: Long,
        scope: String? = null,
        /**
         * False when this round could not read every conversation it meant to. A missing key is
         * then not evidence of a deleted message, so nothing is forgotten: a transient read
         * failure must not silently delete the part of the index it touched.
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

        var indexed = 0
        var unchanged = 0
        var skipped = 0
        var chunks = 0
        var embedded = 0

        for (doc in docs) {
            // One *document* per message - the doc key stays the message, so a hit still resolves
            // to something a person can open. One *chunk* per message is not enough, though: a
            // message may be up to MAX_MESSAGE_CHARS long, which is several times what any
            // model's window holds, and the runtime refuses a text that does not fit rather than
            // truncating it. The spec is sized against the loaded model, and [search] collapses a
            // message's chunks back to its best one.
            val fresh = TextChunker.chunk(doc.text, MESSAGE_SPEC.fitting(model.contextTokens))
            val plan = store.plan(SOURCE, doc.docKey, fresh, model.modelId)
            if (IndexSyncRules.isNoOp(plan)) {
                unchanged++
                continue
            }
            val written = store.apply(SOURCE, doc.docKey, plan, model.modelId, nowMs) { texts ->
                embeddings.embedDocuments(texts)
            }
            plan as SyncPlan.Replace
            chunks += written
            embedded += plan.chunks.size - plan.reuse.size
            indexed++
        }

        if (forgetMissing) {
            val indexedKeys = store.docKeys(SOURCE)
            val forget = if (scope == null) {
                ConversationSearchRules.toForget(indexedKeys, seen)
            } else {
                ConversationSearchRules.toForgetIn(scope, indexedKeys, seen)
            }
            forget.forEach { store.forget(SOURCE, it) }
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
     * Semantic search over the whole history.
     *
     * Returns no hits - rather than throwing - when no model is installed. This is the one source
     * where that state is worth shouting about: history search used to work without a model, so
     * "unavailable" here is a feature that stopped working, not a feature that was never set up.
     */
    suspend fun search(
        query: String,
        limit: Int = VectorIndexStore.DEFAULT_LIMIT,
        relativeFloor: Float = VectorSearchRules.DEFAULT_RELATIVE_FLOOR,
    ): ConversationSearchOutcome {
        val model = embeddings.ensureLoaded()
            ?: return ConversationSearchOutcome(available = false)
        val queryVector = embeddings.embedQuery(query)
        // More candidates than the caller asked for: a message is several chunks, and collapsing
        // them afterwards would otherwise quietly return fewer messages than were requested.
        val result = store.search(
            source = SOURCE,
            modelId = model.modelId,
            query = queryVector,
            limit = limit * CHUNK_HEADROOM,
            relativeFloor = relativeFloor,
        )
        val models = store.modelIdsOf(SOURCE)
        return ConversationSearchOutcome(
            available = true,
            hits = result.asHits()
                // A hit is meant to be a message, so only the best-scoring chunk of each message
                // is kept. The list arrives sorted by score, so the first chunk of a key is its
                // best one.
                .distinctBy { it.docKey }
                .take(limit)
                .mapNotNull { chunk ->
                    val conversationId = ConversationSearchRules.conversationIdOf(chunk.docKey)
                        ?: return@mapNotNull null
                    ConversationSearchHit(
                        conversationId = conversationId,
                        messageId = chunk.docKey.substringAfter('|'),
                        score = chunk.score,
                        text = chunk.text,
                    )
                },
            indexedMessages = store.docKeys(SOURCE).size,
            stale = models.any { it != model.modelId },
        )
    }

    /**
     * The message keys already in the index **under [modelId]**, for ordering a round.
     *
     * Model-scoped: a row left over from a previous model is not indexed as far as the running model
     * is concerned, and treating it as indexed is what made a rebuild stuck on the first N messages.
     */
    suspend fun indexedPaths(modelId: String): Set<String> = store.docKeys(SOURCE, modelId).toSet()

    /** How many messages are indexed - what the settings row shows. */
    suspend fun indexedMessages(): Int = store.docKeys(SOURCE).size

    companion object {
        const val SOURCE = ConversationSearchRules.SOURCE

        /**
         * Messages are prose, not documents: paragraph boundaries only, and no Markdown
         * breadcrumb, because a message that opens with `#` is not a heading.
         */
        private val MESSAGE_SPEC = ChunkSpec(mode = ChunkingMode.PLAIN, headingBreadcrumb = false)

        /** How many chunks per message the search path assumes when sizing its candidate pool. */
        private const val CHUNK_HEADROOM = 4
    }
}
