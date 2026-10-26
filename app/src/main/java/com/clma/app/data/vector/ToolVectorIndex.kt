package com.clma.app.data.vector

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.clma.app.data.ai.tools.ToolCatalog
import com.clma.app.data.ai.tools.ToolCatalogEntry
import com.clma.app.data.ai.tools.ToolRankFusion
import com.clma.app.data.ai.tools.ToolSearchAnswer
import com.clma.app.data.ai.tools.matchAll

/**
 * Phase 2 — the impure half of semantic tool retrieval: hold a vector per catalogue entry,
 * produce the missing ones, and hand the ranked names back.
 *
 * Everything decidable without a model lives in [ToolRankFusion] (ranking, fusion, selection) and
 * [ToolVectorCacheRules] (identity, staleness); this class is the part that has to load a model,
 * survive concurrency and be cancellable. Keeping the split there is what lets the ranking
 * contract be tested on a bare JVM, which is where retrieval bugs actually live.
 *
 * ## The one rule that matters at call time
 *
 * **A search waits for the catalogue to be embedded, but only briefly.** [answer] gives the vectors
 * [READY_WAIT_MS] to arrive, then reports [ToolSearchAnswer.Unavailable] — with the reason and what
 * to do about it — rather than a ranking it does not have. A keyword pass is not a smaller version
 * of this feature: a request written in Chinese shares no characters with `scrape_web`, so the
 * lexical scorer returns nothing at all for it, and silently answering with that emptiness is what
 * made the missing model invisible to the user. `tool_browse` is the deterministic index for that
 * state, which is why the note points at it.
 *
 * Vectors are held in memory only. They are derivable from (model, name, text), the catalogue is
 * small and changes with the MCP connections rather than with the conversation, and a persisted
 * copy would need the same invalidation story as the memory index for a fraction of the payoff.
 */
class ToolVectorIndex(
    private val embeddings: EmbeddingService,
    /** Where a background prewarm runs. The app scope: a catalogue does not belong to a screen. */
    private val scope: CoroutineScope,
) {

    /**
     * Diagnostics for the assistant page: which model, and how much of the last catalogue it
     * covers. [cached] against [total] rather than a bare count, because "12 of 40" is the
     * sentence that tells a user whether the last rebuild finished or whether their MCP server
     * just added thirty tools.
     */
    data class Snapshot(val modelId: String?, val cached: Int, val total: Int) {
        val hasVector: Boolean get() = modelId != null
        val ready: Boolean get() = total > 0 && cached == total
    }

    /**
     * Concurrent because reads happen on a search (hot path, no lock) while a prewarm writes.
     * A `HashMap` here would be a live race between the two, and the failure mode of that is a
     * corrupted map rather than a wrong ranking.
     */
    private val cache = ConcurrentHashMap<String, CachedToolVector>()

    /** Serialises embedding work so a prewarm and an explicit rebuild cannot duplicate it. */
    private val embeddingLock = Mutex()

    @Volatile
    private var activeModelId: String? = null

    /** The last catalogue this index was asked to cover, so a rebuild can mean something. */
    @Volatile
    private var lastEntries: List<ToolCatalogEntry> = emptyList()

    private val _state = MutableStateFlow(Snapshot(modelId = null, cached = 0, total = 0))

    /** The same thing [snapshot] returns, for a screen that has to react to it. */
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** What is held right now. */
    fun snapshot(): Snapshot = _state.value.copy(
        modelId = activeModelId,
        cached = countCached(lastEntries),
    )

    /** Drops what is held and produces it again — the visible "Rebuild" behind the status row. */
    suspend fun rebuild(): Boolean {
        val entries = lastEntries
        if (entries.isEmpty()) return false
        // Not a model swap: the same model re-embeds the same catalogue. That is the point of the
        // button — it is the answer to "the ranking looks wrong and I want to see it redone".
        cache.clear()
        publish(entries)
        return prepare(entries)
    }

    private fun countCached(entries: List<ToolCatalogEntry>): Int {
        val model = activeModelId ?: return 0
        return entries.count { ToolVectorCacheRules.lookup(model, it, cache) != null }
    }

    private fun publish(entries: List<ToolCatalogEntry>) {
        _state.value = Snapshot(
            modelId = activeModelId,
            cached = countCached(entries),
            total = entries.size,
        )
    }

    /**
     * Embeds whatever [entries] are missing, and reports whether the catalogue is rankable
     * afterwards. False means "no model installed", "the model failed", or "an embedding failed" —
     * all of which the caller treats as "semantic search is off", not as an error.
     */
    suspend fun prepare(entries: List<ToolCatalogEntry>): Boolean {
        if (entries.isEmpty()) return false
        lastEntries = entries
        val model = embeddings.ensureLoaded() ?: run {
            publish(entries)
            return false
        }

        val missing = embeddingLock.withLock {
            if (model.modelId != activeModelId) {
                // Every vector in here came out of a different vector space. Keeping them would
                // mean comparing incomparable numbers and calling the result relevance.
                cache.clear()
                activeModelId = model.modelId
            }
            ToolVectorCacheRules.missing(model.modelId, entries, cache)
        }
        if (missing.isEmpty()) {
            publish(entries)
            return ToolVectorCacheRules.ready(model.modelId, entries, cache)
        }

        val vectors = runCatching { embeddings.embedDocuments(missing.map(ToolVectorCacheRules::embedTextOf)) }
            .onFailure { Log.d(TAG, "embedding ${missing.size} tool entries failed", it) }
            .getOrNull()
            ?: run {
                publish(entries)
                return false
            }

        missing.forEachIndexed { index, entry ->
            val vector = vectors.getOrNull(index) ?: return@forEachIndexed
            cache[ToolVectorCacheRules.key(model.modelId, entry.name)] =
                CachedToolVector(ToolVectorCacheRules.textHash(entry), vector)
        }
        publish(entries)
        return ToolVectorCacheRules.ready(model.modelId, entries, cache)
    }

    /**
     * Warms [entries] in the background. Cheap to call on every request: when the cache already
     * covers the catalogue this is a map scan.
     */
    fun prewarm(entries: List<ToolCatalogEntry>) {
        if (entries.isEmpty()) return
        // `scope` is AppScope, whose dispatcher is the main thread, and this body reads files and
        // runs the embedding model. Pinned to IO here so "runs in the background" above is what
        // actually happens.
        scope.launch(Dispatchers.IO) {
            runCatching { prepare(entries) }
                .onFailure { Log.d(TAG, "tool vector prewarm failed", it) }
        }
    }

    /**
     * The ranked names for [query], best first — or null when they cannot be produced right now
     * (no model, catalogue not embedded yet, embedding failed). Null is the fast, silent path back
     * to the caller's lexical ranking.
     */
    suspend fun rank(query: String, entries: List<ToolCatalogEntry>): List<String>? {
        val model = activeModelId ?: return null
        if (query.isBlank() || entries.isEmpty()) return null
        val vectors = entries.map { ToolVectorCacheRules.lookup(model, it, cache) }
        // One missing vector is enough to answer lexically: a partial ranking would silently
        // pretend the un-embedded tools were considered and found irrelevant.
        if (vectors.any { it == null }) return null
        // Capped because the query can be a whole pasted document: the tool intent is in the
        // opening lines, the model would truncate it anyway, and an input larger than the
        // embedder's context is not something this path should discover at runtime.
        val queryVector = runCatching { embeddings.embedQuery(query.trim().take(MAX_QUERY_CHARS)) }
            .onFailure { Log.d(TAG, "embedding the tool query failed", it) }
            .getOrNull()
            ?: return null
        return ToolRankFusion.rankByCosine(
            queryVector,
            entries.map { it.name },
            vectors.map { it ?: return null },
        )
    }

    /**
     * The ranking of [catalog] for [query] as names, best first — empty when it cannot be produced.
     *
     * This is the "what does this turn get without asking" ranking. Empty is the honest answer: the
     * turn then carries exactly what the user pinned and what the model opened, and nothing
     * guessed. `tool_search` is always attached, so a turn that starts with no ranking can still go
     * and look — it just has to say what it is looking for first.
     */
    suspend fun ranking(query: String, catalog: ToolCatalog): List<String> =
        fused(query, catalog)?.map { it.name }.orEmpty()

    /**
     * What `tool_search` should answer with: the catalogue's entries best first, or the reason it
     * cannot rank them.
     *
     * The lexical and vector channels are fused rather than chosen between — the lexical pass is
     * exact and free when the query *is* a tool's name, the vector pass reaches tools whose names
     * share nothing with it, and [ToolRankFusion.fuse] combines the *orderings*, which is the only
     * part of a cosine here that is trustworthy (see that file on why an absolute cutoff is not an
     * option). Fusion is not a fallback: both run on every search. When neither can, the answer
     * says so instead of guessing.
     */
    suspend fun answer(query: String, catalog: ToolCatalog): ToolSearchAnswer {
        val ranked = fused(query, catalog) ?: return ToolSearchAnswer.Unavailable(
            if (snapshot().hasVector) WARMING_NOTE else NO_MODEL_NOTE,
        )
        return ToolSearchAnswer.Ranked(ranked)
    }

    /**
     * The fused ranking, or null when it cannot be produced right now. Empty is a real answer
     * ("nothing in this catalogue is close"); null is "not yet".
     */
    private suspend fun fused(query: String, catalog: ToolCatalog): List<ToolCatalogEntry>? {
        if (query.isBlank() || catalog.entries.isEmpty()) return emptyList()
        if (!awaitRankable(catalog.entries)) return null
        val vector = rank(query, catalog.entries) ?: return null
        val fused = ToolRankFusion.fuse(
            lexical = catalog.matchAll(query).map { it.name },
            vector = vector,
        )
        return fused.mapNotNull { catalog.entry(it) }
    }

    /**
     * Waits up to [READY_WAIT_MS] for [entries] to become rankable, and reports whether they are.
     *
     * The wait is a second chance at work already under way: [prewarm] is launched when the tool
     * surface is built, so by the time a search arrives the only thing between the caller and a
     * ranking is a few milliseconds of embedding. A catalogue that is already covered returns
     * immediately — [prepare] is then a map scan.
     *
     * No model at all returns false *without* waiting: patience does not fix that, and the caller
     * has a different sentence for it.
     */
    private suspend fun awaitRankable(entries: List<ToolCatalogEntry>): Boolean {
        // No model *ready* is not the same as no model *installed*. prewarm() is launched when the
        // tool surface is built, so on the first turn after a cold start the model is still being
        // loaded: returning here without waiting silently cost that turn its semantic ranking (and
        // told the model "no embedding model is installed" if it went and asked). prepare() has the
        // real answer — it loads the model, and reports false only when there is none.
        val model = activeModelId
        if (model != null && ToolVectorCacheRules.ready(model, entries, cache)) return true
        return withTimeoutOrNull(READY_WAIT_MS) { prepare(entries) } == true
    }

    private companion object {
        const val TAG = "ToolVectorIndex"

        /** How much of a query is embedded. See the cap's use site. */
        const val MAX_QUERY_CHARS = 1000

        /**
         * How long a search waits for the catalogue's vectors before saying it cannot. Sized to
         * disappear next to a model round trip while still bounding the very first search after a
         * cold start.
         */
        const val READY_WAIT_MS = 3_000L

        const val NO_MODEL_NOTE =
            "Semantic search is unavailable: no embedding model is installed. Tell the user to " +
                "install one in Settings -> Embedding model. Meanwhile `tool_browse` lists the " +
                "whole catalogue by name, paged — use it and open the names you need."

        const val WARMING_NOTE =
            "Semantic search is not ready yet: the tool catalog is still being embedded. " +
                "Search again in a moment, or use `tool_browse` in the meantime."
    }
}
