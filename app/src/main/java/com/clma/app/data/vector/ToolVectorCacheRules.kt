package com.clma.app.data.vector

import com.clma.app.data.ai.tools.ToolCatalogEntry
import com.clma.app.data.ai.tools.ToolRankFusion

/**
 * Phase 2 — the cache identity of one tool's vector.
 *
 * A tool's vector is only reusable while three things hold: the same **model** produced it (two
 * models' vectors live in different spaces, so comparing them is meaningless rather than merely
 * inaccurate), the same **name** addresses it, and the same **text** was embedded (an MCP server
 * can rewrite a tool's description between sessions, and a stale vector would quietly keep
 * ranking the old wording).
 *
 * Keying by name rather than by text keeps the cache one-vector-per-tool; keying by text would
 * leak the previous wording for every edit. The text fingerprint rides along in the value instead,
 * which is what lets [lookup] answer "is this entry's vector still the vector for *this* text?".
 *
 * Pure — no coroutines, no model, no files — so the cache contract is unit-testable on a bare JVM
 * (`ToolVectorCacheRulesTest`), while the embedding and the concurrency live in [ToolVectorIndex].
 */
data class CachedToolVector(
    /** Fingerprint of the text this vector was produced from. */
    val textHash: Long,
    val vector: FloatArray,
)

object ToolVectorCacheRules {

    /** `modelId|name` — the identity of a cached vector. */
    fun key(modelId: String, name: String): String = "$modelId|$name"

    /** The exact text an entry is embedded as; one definition, shared with the embedder. */
    fun embedTextOf(entry: ToolCatalogEntry): String = ToolRankFusion.embedText(entry.name, entry.summary)

    fun textHash(entry: ToolCatalogEntry): Long = ContentHash.of(embedTextOf(entry))

    /**
     * The cached vector for [entry] under [modelId], or null when there is none — because it was
     * never embedded, or because the text has changed since it was.
     */
    fun lookup(
        modelId: String,
        entry: ToolCatalogEntry,
        cache: Map<String, CachedToolVector>,
    ): FloatArray? {
        val hit = cache[key(modelId, entry.name)] ?: return null
        return if (hit.textHash == textHash(entry)) hit.vector else null
    }

    /**
     * The entries whose vector has to be produced before the catalogue can be ranked semantically,
     * in the order they were given. Empty ⇒ the caller can rank without embedding anything.
     */
    fun missing(
        modelId: String,
        entries: List<ToolCatalogEntry>,
        cache: Map<String, CachedToolVector>,
    ): List<ToolCatalogEntry> = entries.filter { lookup(modelId, it, cache) == null }

    /**
     * Whether every entry can be ranked right now.
     *
     * An empty catalogue is *not* ready: there is nothing to rank, and saying "ready" would let a
     * caller skip the very check that keeps it from embedding an empty list.
     */
    fun ready(
        modelId: String,
        entries: List<ToolCatalogEntry>,
        cache: Map<String, CachedToolVector>,
    ): Boolean = entries.isNotEmpty() && missing(modelId, entries, cache).isEmpty()
}
