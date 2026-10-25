package com.clma.app.data.vector

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One passage the vector index returned, before it is resolved to a conversation. */
data class ConversationSearchHit(
    val conversationId: String,
    val messageId: String,
    val score: Float,
    val text: String,
)

/**
 * A hit with the conversation it lives in attached - what the tool actually answers with.
 *
 * The title and date are resolved after the search rather than stored in the index: a title is
 * renamed and a conversation is re-dated on every turn, and both are one lookup away from an id
 * that never changes. Storing them would mean re-embedding a message because someone renamed the
 * conversation it is in.
 */
data class ConversationSearchResult(
    val conversationId: String,
    val messageId: String,
    /**
     * The node holding [messageId], so a caller can open the conversation *at* this message. Empty
     * when the message cannot be located — the search page then opens the conversation without a
     * target rather than failing the tap.
     */
    val nodeId: String,
    /**
     * The assistant that owns the conversation, for the search page's scope filter.
     *
     * Resolved here rather than stored in the index for the same reason as the title: the index is
     * deliberately not per-assistant (a message belongs to a conversation, and the conversation is
     * what knows), so filtering by assistant is a lookup, not an index key.
     */
    val assistantId: String,
    val title: String,
    val date: String,
    val score: Float,
    val text: String,
)

/**
 * What `conversation_search` returns for one call.
 *
 * [available] is false when nothing can be embedded. That case used to be impossible for history
 * search - the keyword index it replaces needs no model - so the envelope says plainly that the
 * feature needs one and where to get it, rather than reporting "no results" for a history that is
 * sitting right there.
 */
data class ConversationSearchOutcome(
    val available: Boolean,
    val hits: List<ConversationSearchHit> = emptyList(),
    val indexedMessages: Int = 0,
    /** Some passages were embedded by a different model: a rebuild is needed before trusting it. */
    val stale: Boolean = false,
    val note: String? = null,
)

/**
 * The JSON `conversation_search` answers with.
 *
 * A sibling of the other envelopes rather than a shared shape, because the payloads differ: history
 * answers with conversations and messages, cold memory with files. What "unavailable" means does
 * not differ any more - none of the three has a keyword path behind it, so all three say the same
 * thing: install the model.
 */
object ConversationSearchEnvelope {

    fun hits(
        query: String,
        results: List<ConversationSearchResult>,
        indexedMessages: Int,
        stale: Boolean,
    ): String = buildJsonObject {
        put("query", query)
        put("indexedMessages", indexedMessages)
        if (stale) {
            put("stale", true)
            put(
                "staleNote",
                "Some passages were embedded by a different model; a rebuild restores full accuracy.",
            )
        }
        put("results", buildJsonArray {
            results.forEach { result ->
                add(buildJsonObject {
                    put("conversation_id", result.conversationId)
                    put("message_id", result.messageId)
                    put("title", result.title)
                    put("date", result.date)
                    put("score", result.score)
                    put("snippet", result.text.take(MAX_HIT_CHARS))
                    if (result.text.length > MAX_HIT_CHARS) put("truncated", true)
                })
            }
        })
        if (results.isEmpty()) {
            put("note", emptyNote(indexedMessages))
        }
    }.toString()

    /**
     * Sent when no embedding model is installed. Unlike every other envelope's version of this,
     * there is no exact tool to fall back on: this is the whole of history search, so the hint is
     * an instruction rather than an alternative.
     */
    fun unavailable(detail: String?): String = buildJsonObject {
        put("error", "unavailable")
        put("detail", detail ?: "no embedding model is installed")
        put(
            "hint",
            "History search is by meaning and needs an embedding model. Ask the user to install " +
                "one from the assistant's memory settings; until then, past conversations cannot " +
                "be searched at all.",
        )
    }.toString()

    fun missingQuery(): String = buildJsonObject {
        put("error", "missing_argument")
        put("detail", "query is required")
    }.toString()

    private fun emptyNote(indexedMessages: Int): String = if (indexedMessages == 0) {
        "No conversations are indexed yet. The index is built in the background, a few hundred " +
            "messages at a time - ask the user to rebuild it if it stays empty."
    } else {
        "Nothing matched. Try describing the conversation instead of naming it: this search works " +
            "on meaning, so exact words matter less than they used to."
    }
}
