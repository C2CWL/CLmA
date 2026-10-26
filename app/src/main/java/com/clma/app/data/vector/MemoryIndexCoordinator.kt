package com.clma.app.data.vector

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.ai.tools.ColdMemoryRules
import com.clma.app.data.model.Assistant
import com.clma.app.data.repository.WorkspaceRepository
import com.clma.workspace.WorkspaceStorageArea

private const val TAG = "MemoryIndex"

/**
 * Keeps the knowledge-base index up to date, in the background.
 *
 * Deliberately *not* triggered by every write: embedding is a model call per chunk, and a feature
 * that spends it on every keystroke of a note is a feature users turn off. Two triggers instead,
 * matching what the settings screen offers:
 *
 *  - opening a conversation, which is when the model is about to need the index - debounced, so
 *    switching between conversations does not re-scan a directory the last minute already
 *    covered;
 *  - an explicit rebuild from settings, which ignores the debounce.
 *
 * Both paths are incremental: [MemoryVectorSource.sync] compares fingerprints first and only
 * chunks that changed cost a model call, so the common case (nothing changed) is a directory
 * listing and a hash per document.
 */
class MemoryIndexCoordinator(
    private val scope: CoroutineScope,
    private val embeddings: EmbeddingService,
    private val source: MemoryVectorSource,
    private val workspaceRepository: WorkspaceRepository,
    private val settingsStore: SettingsStore,
) {

    /** What the settings screen shows, and what a log line is written from. */
    data class Status(
        val running: Boolean = false,
        val lastReport: MemoryVectorSource.SyncReport? = null,
        val lastError: String? = null,
        /** Epoch millis of the last completed run, successful or not. */
        val lastRunAtMs: Long = 0L,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    // Concurrent: requestSync reads and writes it on the caller's thread (Main), while a failed
    // round removes the key on Dispatchers.IO. A plain HashMap mutated from both is a
    // ConcurrentModificationException waiting for a failed sync.
    private val lastAttemptAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * The scope key for one assistant's knowledge base, or null when it has none configured.
     * Shared with the tool so that a search and a sync always agree on what "this directory"
     * means.
     */
    private fun scopeOf(assistant: Assistant): Pair<String, String>? {
        if (!assistant.coldMemoryEnabled) return null
        val workspaceId = assistant.workspaceId?.toString() ?: return null
        val dir = ColdMemoryRules.normalizeDir(assistant.coldMemoryDir) ?: return null
        return workspaceId to dir
    }

    /**
     * Fires a background sync unless one ran for this assistant within [minIntervalMs].
     * Returns false when it was skipped, which is not a failure - it is the debounce working.
     */
    fun requestSync(assistant: Assistant, minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS): Boolean {
        val (workspaceId, dir) = scopeOf(assistant) ?: return false
        val key = "${assistant.id}:$workspaceId:$dir"
        val now = System.currentTimeMillis()
        val previous = lastAttemptAtMs[key] ?: 0L
        if (now - previous < minIntervalMs) {
            // Logged because the debounce is otherwise invisible: "the index is stale and nothing
            // is happening" is indistinguishable from "the trigger never fired", and that cost a
            // diagnostic round on the first device test.
            Log.d(TAG, "skipping sync of '$dir': one ran ${(now - previous) / 1000}s ago")
            return false
        }
        lastAttemptAtMs[key] = now

        // `scope` is AppScope, whose dispatcher is Dispatchers.Main (see RikkaHubApp.AppScope),
        // and this body lists directories, reads files and runs the embedding model. Pinned to
        // IO here so "runs in the background" in the KDoc above is what actually happens.
        scope.launch(Dispatchers.IO) {
            val report = runCatching { syncNow(assistant) }
                .onFailure { Log.d(TAG, "background sync failed", it) }
                .getOrNull()
            if (report == null) {
                // Nothing was indexed - most often because no model is installed yet. Do not let
                // that count as this assistant's one attempt for the next five minutes: the user
                // is probably installing the model right now, and the next conversation they open
                // should try again rather than wait out a debounce for work that never ran.
                lastAttemptAtMs.remove(key)
            }
        }
        return true
    }

    /**
     * Runs a sync now and returns its report, or null when there is nothing to index. Failures
     * are recorded in [status] and returned as a null report rather than thrown: this runs from
     * a conversation opening, and no conversation should fail to open because an index could not
     * be built.
     */
    suspend fun syncNow(assistant: Assistant): MemoryVectorSource.SyncReport? {
        val (workspaceId, dir) = scopeOf(assistant) ?: return null
        val models = embeddings.ensureLoaded()
        if (models == null) {
            _status.value = Status(
                running = false,
                lastError = "no embedding model is installed",
                lastRunAtMs = System.currentTimeMillis(),
            )
            return null
        }

        _status.value = _status.value.copy(running = true, lastError = null)
        return try {
            val listing = readDocuments(workspaceId, dir)
            if (listing == null) {
                // The directory could not be listed at all. Calling sync with an empty list here
                // would read as "every document was deleted" and wipe a perfectly good index, so
                // this round does nothing and says so.
                _status.value = Status(
                    running = false,
                    lastError = "cannot list knowledge base directory '$dir'",
                    lastRunAtMs = System.currentTimeMillis(),
                )
                Log.d(TAG, "cannot list '$dir'; leaving the index untouched")
                return null
            }
            val report = source.sync(
                workspaceId = workspaceId,
                dir = dir,
                docs = listing.docs,
                nowMs = System.currentTimeMillis(),
                forgetMissing = listing.complete,
            )
            _status.value = Status(
                running = false,
                lastReport = report,
                lastRunAtMs = System.currentTimeMillis(),
            )
            Log.d(TAG, "synced '$dir': ${report.indexed} indexed, ${report.unchanged} unchanged, " +
                "${report.embeddedChunks} chunk(s) embedded")
            report
        } catch (e: Throwable) {
            _status.value = Status(
                running = false,
                lastError = e.message ?: e::class.java.simpleName,
                lastRunAtMs = System.currentTimeMillis(),
            )
            Log.d(TAG, "sync of '$dir' failed", e)
            null
        }
    }

    /** A listing plus whether every file in it could actually be read. */
    private data class Listing(
        val docs: List<MemoryVectorSource.Doc>,
        val complete: Boolean,
    )

    /**
     * Reads every Markdown document in [dir].
     *
     * Returns null when the directory itself cannot be listed: the caller must not feed an empty
     * list to [MemoryVectorSource.sync], because that is indistinguishable from "all documents
     * were deleted". A single unreadable file is dropped from the listing but marks it
     * incomplete, so the sync keeps that file's previous rows instead of forgetting them.
     */
    private suspend fun readDocuments(workspaceId: String, dir: String): Listing? {
        // listFiles() answers "not found" with an empty list, which is indistinguishable from a
        // genuinely empty directory. Treat a missing workspace as an unlistable one so the sync
        // below cannot wipe the index over a deleted or not-yet-restored workspace record.
        if (workspaceRepository.getById(workspaceId) == null) {
            Log.d(TAG, "workspace '$workspaceId' is missing; leaving the index untouched")
            return null
        }
        val entries = try {
            workspaceRepository.listFiles(workspaceId, WorkspaceStorageArea.FILES, dir)
                .filter { !it.isDirectory && ColdMemoryRules.isMarkdown(it.name) }
        } catch (e: Throwable) {
            // The directory may not exist yet; cold memory creates it on first write.
            Log.d(TAG, "cannot list '$dir'", e)
            return null
        }

        val docs = mutableListOf<MemoryVectorSource.Doc>()
        var complete = true
        for (entry in entries) {
            val path = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
            try {
                docs += MemoryVectorSource.Doc(entry.name, workspaceRepository.readText(workspaceId, path))
            } catch (e: Throwable) {
                complete = false
                Log.d(TAG, "cannot read '$path', keeping its old index rows this round", e)
            }
        }
        return Listing(docs = docs, complete = complete)
    }

    /**
     * Semantic search, in the shape the tool layer wants.
     *
     * The mapping lives here rather than in the tool so that the tool keeps holding nothing but
     * the model-facing contract, and so a hit's `docKey` is translated to a file name in exactly
     * one place - the same name `memory_read` accepts.
     */
    suspend fun search(workspaceId: String, dir: String, query: String, limit: Int): ColdMemorySearchOutcome {
        val outcome = source.search(workspaceId, dir, query, limit)
        return ColdMemorySearchOutcome(
            available = outcome.available,
            hits = outcome.hits.map {
                ColdMemorySearchHit(
                    file = it.docKey,
                    chunkIndex = it.chunkIndex,
                    score = it.score,
                    text = it.text,
                )
            },
            indexedDocuments = outcome.indexedDocuments,
            stale = outcome.stale,
        )
    }

    companion object {
        /**
         * Five minutes. Long enough that flicking between conversations is free, short enough
         * that a note written now is searchable in the same sitting.
         */
        const val DEFAULT_MIN_INTERVAL_MS = 5 * 60 * 1000L
    }
}
