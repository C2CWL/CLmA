package com.clma.app.data.vector

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One passage `memory_search` found, with the file it came from. */
data class ColdMemorySearchHit(
    val file: String,
    val chunkIndex: Int,
    val score: Float,
    val text: String,
)

/**
 * What `memory_search` returns for one call.
 *
 * [available] is false when nothing can be embedded - no model installed, or one that does not fit
 * this device - and that is a state the tool reports rather than throws: a fresh install has no
 * embedding model, and the model still has to be told what to do instead.
 */
data class ColdMemorySearchOutcome(
    val available: Boolean,
    val hits: List<ColdMemorySearchHit> = emptyList(),
    val indexedDocuments: Int = 0,
    /** Some passages were embedded by a different model: a rebuild is needed before trusting it. */
    val stale: Boolean = false,
    val note: String? = null,
)

/**
 * The JSON `memory_search` answers with.
 *
 * Split out of the tool itself so it can be tested without the tool stack: this is where the
 * wording that steers the model lives - what to do when nothing is indexed, when the index is
 * stale, when semantic search is not set up at all - and a wording bug here is invisible until a
 * model does the wrong thing with it.
 */
object MemorySearchEnvelope {

    fun hits(dirLabel: String, query: String, outcome: ColdMemorySearchOutcome): String =
        buildJsonObject {
            put("dir", dirLabel)
            put("query", query)
            put("indexedDocuments", outcome.indexedDocuments)
            if (outcome.stale) {
                put("stale", true)
                put(
                    "staleNote",
                    "Some passages were embedded by a different model; a rebuild restores full accuracy.",
                )
            }
            put("hits", buildJsonArray {
                outcome.hits.forEach { hit ->
                    add(buildJsonObject {
                        put("file", hit.file)
                        put("chunkIndex", hit.chunkIndex)
                        put("score", hit.score)
                        put("text", hit.text.take(MAX_HIT_CHARS))
                        if (hit.text.length > MAX_HIT_CHARS) put("truncated", true)
                    })
                }
            })
            if (outcome.hits.isEmpty()) {
                put("note", emptyNote(outcome.indexedDocuments))
                if (outcome.note != null) put("detail", outcome.note!!)
            }
        }.toString()

    /**
     * Sent when no embedding model is installed. The hint matters more than the error: a model that
     * only finds out semantic search is off will otherwise stop looking for the notes entirely,
     * when the exact-name tools it already has would have answered the question.
     */
    /**
     * Sent when no embedding model is installed.
     *
     * The hint is an instruction, not an alternative. It used to point at `memory_index` /
     * `memory_read` — a keyword-free way to browse the same directory — but that is a fallback for
     * a feature that is simply off, and Moxw does not keep those: the model is what searching by
     * meaning runs on, so the only thing worth telling anyone is how to install it.
     */
    fun unavailable(detail: String?): String = buildJsonObject {
        put("error", "unavailable")
        put("detail", detail ?: "no embedding model is installed")
        put(
            "hint",
            "Searching cold memory needs the embedding model, and none is installed. Ask the user " +
                "to install one from the assistant's memory settings; until then this knowledge " +
                "base cannot be searched.",
        )
    }.toString()

    fun missingQuery(): String = buildJsonObject {
        put("error", "missing_argument")
        put("detail", "query is required")
    }.toString()

    private fun emptyNote(indexedDocuments: Int): String = if (indexedDocuments == 0) {
        "Nothing is indexed for this directory yet. Use memory_index and memory_read instead, or " +
            "ask the user to rebuild the search index."
    } else {
        "No passage matched. Try different words, or memory_index to see what exists."
    }
}

/** Passages one search returns unless the model asks for a different number. */
const val DEFAULT_SEARCH_LIMIT = 8

/** Upper bound on `limit`: a caller asking for a hundred passages is asking for a context blowup. */
const val MAX_SEARCH_LIMIT = 20

/** Characters of a passage returned to the model; the full text stays a `memory_read` away. */
const val MAX_HIT_CHARS = 800
