package com.clma.app.data.vector

/**
 * What history search is allowed to index, and how much of it one round may read.
 *
 * The index is over **one message per document**, which is the granularity the keyword index it
 * replaces already used: a hit should point at something a person can open, not at "somewhere in
 * this conversation". Two consequences worth stating, because they are load- bearing:
 *
 *  - a document key carries the conversation as well as the message (`<conversationId>|<messageId>`)
 *    so a hit can be resolved back to a title and a date without a second index, and so a sync
 *    limited to one conversation can tell its own keys from everyone else's;
 *  - messages are read from the JSON blob a conversation stores, so the round is planned from
 *    *metadata* (ids and lengths) and only the chosen messages are read in full. That is what keeps
 *    a walk over an entire history cheap: the memory a round holds is proportional to the budget,
 *    not to the history.
 */
object ConversationSearchRules {

    /** The one source every conversation shares: history search is not per-assistant. */
    const val SOURCE = "conversation"

    /**
     * Messages below this are not worth a model call. A history is full of "ok", "thanks", "go on",
     * and embedding them buys nothing a search would ever want - while costing exactly as much as
     * embedding something that matters.
     */
    const val MIN_MESSAGE_CHARS = 12

    /** Matches the keyword index's own cap, so the two see the same text. */
    const val MAX_MESSAGE_CHARS = 10_000

    /**
     * One round's budget. A history is the largest thing in the app - it grows with every
     * conversation the user ever had - so this is the number that keeps the first sync from
     * embedding for an hour on a phone.
     *
     * Deliberately small. A round that takes minutes leaves the status stuck on "indexing" for
     * that whole time, and on a phone the embedding model is slow enough that the old 600k/300
     * round could run for an hour. Small rounds finish, and [RoundBudget] resumes where the last
     * one stopped, so a large history converges in many short steps instead of one endless one.
     */
    const val MAX_CHARS_PER_ROUND = 40_000
    const val MAX_MESSAGES_PER_ROUND = 40

    private const val SEPARATOR = '|'

    fun docKeyOf(conversationId: String, messageId: String): String = "$conversationId$SEPARATOR$messageId"

    /** The conversation a document key belongs to, or null when it is not in this shape. */
    fun conversationIdOf(docKey: String): String? =
        docKey.substringBefore(SEPARATOR, missingDelimiterValue = "").takeIf { it.isNotEmpty() }

    /** True for a blank or trivially short message. */
    fun isIndexable(text: String): Boolean = text.trim().length >= MIN_MESSAGE_CHARS

    fun estimatedChars(chars: Int): Int = chars.coerceAtMost(MAX_MESSAGE_CHARS)

    /**
     * One message, as the walk sees it before anything is read in full. [chars] is the length of
     * the text that would be embedded - the same number [estimatedChars] budgets with.
     */
    data class Candidate(
        val conversationId: String,
        val messageId: String,
        val chars: Int,
    ) {
        val docKey: String get() = docKeyOf(conversationId, messageId)
    }

    fun planRound(
        candidates: List<Candidate>,
        indexed: Set<String>,
        budgetChars: Int = MAX_CHARS_PER_ROUND,
        maxMessages: Int = MAX_MESSAGES_PER_ROUND,
    ): RoundBudget.Round<Candidate> = RoundBudget.plan(
        items = candidates,
        key = { it.docKey },
        sizeOf = { it.chars.toLong() },
        capOf = { MAX_MESSAGE_CHARS.toLong() },
        estimateChars = { estimatedChars(it.chars) },
        indexed = indexed,
        budgetChars = budgetChars,
        maxItems = maxMessages,
    )

    /**
     * Which indexed messages a full walk should forget. See [RoundBudget.toForget].
     */
    fun toForget(indexed: Collection<String>, seen: Collection<String>): List<String> =
        RoundBudget.toForget(indexed, seen)

    /**
     * Which indexed messages a **single-conversation** sync should forget.
     *
     * Same rule, narrowed: the per-turn trigger only reads the conversation being talked in, and
     * without this filter it would read every other conversation as "not seen" and delete the whole
     * history. Only keys belonging to [conversationId] are eligible, so a message deleted inside one
     * conversation is forgotten while the rest of the index is untouched.
     */
    fun toForgetIn(
        conversationId: String,
        indexed: Collection<String>,
        seen: Collection<String>,
    ): List<String> = RoundBudget.toForget(
        indexed = indexed.filter { conversationIdOf(it) == conversationId },
        seen = seen,
    )
}
