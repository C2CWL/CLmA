package com.clma.app.data.vector

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.model.Conversation
import com.clma.app.data.repository.ConversationRepository
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

private const val TAG = "ConversationIndex"

/**
 * Keeps the history index up to date, in the background.
 *
 * Two triggers, because history changes in two different ways:
 *
 *  - opening a conversation sweeps the whole history, so anything written since the last sweep is
 *    picked up. Debounced like the other indexes, and budgeted, so the first sweep over a year of
 *    chat is a background round rather than a stall.
 *  - finishing a turn syncs **the conversation just talked in**, on a much shorter debounce, so
 *    what was said a minute ago is already recallable. This is the half that makes the index useful
 *    *within* a conversation instead of only across them.
 *
 * The second trigger is why the source's `sync` takes a scope. A per-turn sync reads one
 * conversation; without the scope it would read every other conversation as deleted, and the first
 * turn after a long chat would wipe the history out of the index.
 *
 * A round is planned from metadata only - conversation ids, message ids, text lengths - so a walk
 * over the entire history costs bounded memory and no model calls. Only the messages that fit the
 * budget are read in full and embedded.
 */
class ConversationIndexCoordinator(
    private val scope: CoroutineScope,
    private val embeddings: EmbeddingService,
    private val source: ConversationVectorSource,
    private val conversationRepository: ConversationRepository,
) {

    data class Status(
        val running: Boolean = false,
        val lastReport: Report? = null,
        val lastError: String? = null,
        val lastRunAtMs: Long = 0L,
    )

    data class Report(
        /** Messages the walk found that are worth indexing. */
        val candidates: Int,
        val read: Int,
        val skipped: Int,
        val deferred: Int,
        val tooBig: Int,
        val indexedNow: Int,
        val unchanged: Int,
        val indexedTotal: Int,
        val chunks: Int,
        val embeddedChunks: Int,
        /** True when this run was limited to one conversation. */
        val scopedToConversation: Boolean = false,
        val reason: String? = null,
    ) {
        val hasMore: Boolean get() = deferred > 0
    }

    /** What the tool answers with: hits already resolved to a conversation. */
    data class Response(
        val available: Boolean,
        val results: List<ConversationSearchResult> = emptyList(),
        val indexedMessages: Int = 0,
        val stale: Boolean = false,
        val note: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    // Concurrent: requestSync reads and writes it on the caller's thread (Main), while a failed
    // round removes the key on Dispatchers.IO. A plain HashMap mutated from both is a
    // ConcurrentModificationException waiting for a failed sync.
    private val lastAttemptAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * One whole-history sweep at a time. The search page, the settings page and a conversation
     * open can all ask for one within the same minute; without this they would run concurrently,
     * fight for the same CPU and each report "indexing" while making the others slower.
     */
    private val sweepInFlight = AtomicBoolean(false)

    /** Sweeps the whole history, unless one ran within [minIntervalMs]. */
    fun requestSync(minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS): Boolean {
        val key = "all"
        val now = System.currentTimeMillis()
        val previous = lastAttemptAtMs[key] ?: 0L
        if (now - previous < minIntervalMs) {
            Log.d(TAG, "skipping history sweep: one ran ${(now - previous) / 1000}s ago")
            return false
        }
        lastAttemptAtMs[key] = now
        // `scope` is AppScope, whose dispatcher is Dispatchers.Main (see RikkaHubApp.AppScope),
        // and this body lists directories, reads files and runs the embedding model. Pinned to
        // IO here so "runs in the background" in the KDoc above is what actually happens.
        //
        // One sweep walks the history once and then runs several budgeted rounds over that walk;
        // see [runReport]. That keeps a large first sync moving without re-walking the whole
        // history for every forty messages.
        scope.launch(Dispatchers.IO) { runCatching { syncNow() }.onFailure { Log.d(TAG, "sweep failed", it) } }
        return true
    }

    /**
     * Syncs the conversation a turn just happened in.
     *
     * Returns false when the debounce skipped it, which is not a failure: a turn that writes six
     * messages should cost one round, not six.
     */
    fun requestSyncOf(conversationId: Uuid, minIntervalMs: Long = DEFAULT_TURN_INTERVAL_MS): Boolean {
        val key = "turn:$conversationId"
        val now = System.currentTimeMillis()
        val previous = lastAttemptAtMs[key] ?: 0L
        if (now - previous < minIntervalMs) return false
        lastAttemptAtMs[key] = now
        // `scope` is AppScope, whose dispatcher is Dispatchers.Main (see RikkaHubApp.AppScope),
        // and this body lists directories, reads files and runs the embedding model. Pinned to
        // IO here so "runs in the background" in the KDoc above is what actually happens.
        scope.launch(Dispatchers.IO) {
            runCatching { syncConversation(conversationId) }
                .onFailure { Log.d(TAG, "turn sync failed", it) }
        }
        return true
    }

    /** One round over the whole history. Returns null when there is nothing to do or no model. */
    suspend fun syncNow(): Report? = runReport(scopedToConversation = null)

    /** One round over a single conversation: what the turn-complete trigger calls. */
    suspend fun syncConversation(conversationId: Uuid): Report? =
        runReport(scopedToConversation = conversationId.toString())

    private suspend fun runReport(scopedToConversation: String?): Report? {
        if (embeddings.ensureLoaded() == null) {
            _status.value = Status(
                running = false,
                lastError = "no embedding model is installed",
                lastRunAtMs = System.currentTimeMillis(),
            )
            return null
        }

        val scoped = scopedToConversation != null
        // A whole-history sweep is exclusive; a scoped turn sync is short and is allowed to run
        // alongside one. Two sweeps at once would only halve each other's speed.
        if (!scoped && !sweepInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "history sweep already running; not starting another")
            return null
        }

        try {
            _status.value = _status.value.copy(running = true, lastError = null)
            return try {
                val ids = if (scoped) listOf(scopedToConversation!!) else conversationRepository.getAllConversationIds()
                // Walk once. Every round below plans against this same walk, so a large first
                // sync does not re-read every conversation in the history once per forty messages.
                val walk = collectCandidates(ids)
                var report: Report? = null
                var rounds = 0
                val maxRounds = if (scoped) 1 else MAX_ROUNDS_PER_SWEEP
                while (rounds < maxRounds) {
                    val roundReport = syncRound(walk, scopedToConversation)
                    report = roundReport
                    rounds++
                    if (!roundReport.hasMore || roundReport.read == 0 || roundReport.indexedNow == 0) break
                    if (rounds < maxRounds) delay(INTER_ROUND_DELAY_MS)
                }
                _status.value = Status(
                    running = false,
                    lastReport = report,
                    lastRunAtMs = System.currentTimeMillis(),
                )
                report
            } catch (e: CancellationException) {
                // The sweep was cancelled (app going away, scope torn down). Leaving `running`
                // true is what makes the next screen show "building index" forever.
                _status.value = _status.value.copy(
                    running = false,
                    lastError = "cancelled",
                    lastRunAtMs = System.currentTimeMillis(),
                )
                throw e
            } catch (e: Throwable) {
                _status.value = Status(
                    running = false,
                    lastError = e.message ?: e::class.java.simpleName,
                    lastRunAtMs = System.currentTimeMillis(),
                )
                Log.d(TAG, "history index failed", e)
                null
            }
        } finally {
            if (!scoped) sweepInFlight.set(false)
        }
    }

    /**
     * One budgeted round over a walk that has already been collected.
     *
     * Updates [status] with the round's numbers while keeping `running` true; the caller clears
     * it when the whole sweep is done. That way the settings row shows progress rather than
     * flickering between "indexing" and "idle" between rounds.
     */
    private suspend fun syncRound(walk: Walk, scopedToConversation: String?): Report {
        val modelId = embeddings.ensureLoaded()?.modelId
        val round = ConversationSearchRules.planRound(
            candidates = walk.candidates,
            // Model-scoped: "indexed" has to mean "has a vector from the model running now".
            indexed = modelId?.let { source.indexedPaths(it) }.orEmpty(),
        )

        // Read only what the round chose, and only once per conversation: the walk that picked
        // the candidates deliberately never held their text.
        var read = 0
        var skipped = 0
        val missed = walk.failedConversations.toMutableSet()
        val docs = mutableListOf<ConversationVectorSource.Doc>()
        round.take.groupBy { it.conversationId }.forEach { (conversationId, group) ->
            val conversation = loadConversation(conversationId)
            if (conversation == null) {
                missed += conversationId
                return@forEach
            }
            val wanted = group.map { it.messageId }.toSet()
            conversation.messageNodes.forEach { node ->
                node.messages.forEach { message ->
                    val messageId = message.id.toString()
                    if (messageId !in wanted) return@forEach
                    val text = messageText(message)
                    if (!ConversationSearchRules.isIndexable(text)) {
                        skipped++
                        return@forEach
                    }
                    docs += ConversationVectorSource.Doc(conversationId, messageId, text)
                    read++
                }
            }
        }

        val synced = source.sync(
            docs = docs,
            // Everything the walk saw - including the messages it did not read this round and
            // the ones too short to index - stays. Only a message that vanished from the history
            // is forgotten.
            seen = walk.candidates.map { it.docKey }.toSet() + round.take.map { it.docKey },
            nowMs = System.currentTimeMillis(),
            scope = scopedToConversation,
            // A conversation that could not be read is not a conversation that was deleted.
            // Forgetting on a read failure is how an index silently empties itself; the next
            // sweep will try these conversations again.
            forgetMissing = missed.isEmpty(),
        )

        val report = Report(
            candidates = walk.candidates.size,
            read = read,
            skipped = skipped,
            deferred = round.deferred,
            tooBig = round.tooBig.size,
            indexedNow = synced.indexed,
            unchanged = synced.unchanged,
            indexedTotal = source.indexedMessages(),
            chunks = synced.chunks,
            embeddedChunks = synced.embeddedChunks,
            scopedToConversation = scopedToConversation != null,
            reason = synced.reason,
        )
        _status.value = _status.value.copy(running = true, lastReport = report)
        Log.d(
            TAG,
            "history index: ${report.read} read, ${report.indexedNow} indexed, " +
                "${report.embeddedChunks} embedded, ${report.deferred} deferred, " +
                "${report.indexedTotal} in the index" +
                if (missed.isEmpty()) "" else ", ${missed.size} unreadable",
        )
        return report
    }

    private data class Walk(
        val candidates: List<ConversationSearchRules.Candidate>,
        /** Conversation ids that could not be read this round; never treated as deletions. */
        val failedConversations: Set<String>,
    )

    /**
     * Metadata for every indexable message of [conversationIds].
     *
     * Deliberately returns lengths rather than text: a history can hold hundreds of thousands of
     * messages, and a round needs their identity and their cost, not their prose. The chosen ones
     * are read afterwards.
     *
     * Conversations that fail to load are reported rather than skipped silently: the caller must
     * not let a read failure look like a deletion.
     */
    private suspend fun collectCandidates(
        conversationIds: List<String>,
    ): Walk {
        val candidates = mutableListOf<ConversationSearchRules.Candidate>()
        val failed = mutableSetOf<String>()
        for (id in conversationIds) {
            val conversation = loadConversation(id)
            if (conversation == null) {
                failed += id
                continue
            }
            conversation.messageNodes.forEach { node ->
                node.messages.forEach { message ->
                    val text = messageText(message)
                    if (text.length < ConversationSearchRules.MIN_MESSAGE_CHARS) return@forEach
                    candidates += ConversationSearchRules.Candidate(
                        conversationId = id,
                        messageId = message.id.toString(),
                        chars = text.length,
                    )
                }
            }
        }
        return Walk(candidates = candidates, failedConversations = failed)
    }

    private suspend fun loadConversation(conversationId: String): Conversation? = try {
        conversationRepository.getConversationById(Uuid.parse(conversationId))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "cannot load conversation '$conversationId': ${e.message}")
        null
    }

    /**
     * The part of a message worth embedding: its text, capped like the keyword index capped it.
     *
     * Only [UIMessagePart.Text] counts. Tool calls and their results are excluded on purpose - they
     * are the bulk of a long history and the least likely thing anyone searches for - and so are
     * reasoning parts, which were never meant to be quoted back.
     */
    private fun messageText(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .take(ConversationSearchRules.MAX_MESSAGE_CHARS)

    /**
     * Semantic history search, resolved into what the tool answers with.
     *
     * The title and the date come from the conversation rather than from the index, one lookup per
     * hit: both change over time and neither is worth re-embedding a message for.
     */
    suspend fun search(query: String, limit: Int): Response {
        val outcome = source.search(query, limit)
        if (!outcome.available) {
            return Response(available = false, note = outcome.note)
        }
        val resolved = outcome.hits.map { hit ->
            val conversation = loadConversation(hit.conversationId)
            ConversationSearchResult(
                conversationId = hit.conversationId,
                messageId = hit.messageId,
                nodeId = nodeIdOf(conversation, hit.messageId),
                assistantId = conversation?.assistantId?.toString().orEmpty(),
                title = conversation?.title?.takeIf { it.isNotBlank() } ?: "Untitled",
                date = dateOf(conversation),
                score = hit.score,
                text = hit.text,
            )
        }
        return Response(
            available = true,
            results = resolved,
            indexedMessages = outcome.indexedMessages,
            stale = outcome.stale,
            note = outcome.note,
        )
    }

    private fun dateOf(conversation: Conversation?): String {
        val millis = conversation?.updateAt?.toEpochMilli() ?: return ""
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }

    /**
     * The node a hit's message lives in, or empty when it cannot be located.
     *
     * The index keys a message by id while the chat page navigates by node, and neither is
     * derivable from the other without the conversation — which this is the one place to already
     * have open. A message that has since been edited out of its node simply yields no target.
     */
    private fun nodeIdOf(conversation: Conversation?, messageId: String): String {
        val parsed = runCatching { Uuid.parse(messageId) }.getOrNull() ?: return ""
        return conversation?.getMessageNodeByMessageId(parsed)?.id?.toString().orEmpty()
    }

    companion object {
        /** Five minutes: long enough that flicking between conversations is free. */
        const val DEFAULT_MIN_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * Half a minute. The turn that just finished should be searchable by the time the user
         * asks about it, without paying a model call per message in a burst.
         */
        const val DEFAULT_TURN_INTERVAL_MS = 30 * 1000L

        /**
         * How many budgeted rounds one sweep may run over the same walk. The first sweep over a
         * large history is the only time this matters; a bound keeps a background catch-up from
         * monopolising the CPU indefinitely.
         */
        private const val MAX_ROUNDS_PER_SWEEP = 6

        /** A breath between rounds so the UI and the rest of the app get a slot. */
        private const val INTER_ROUND_DELAY_MS = 1_500L
    }
}
