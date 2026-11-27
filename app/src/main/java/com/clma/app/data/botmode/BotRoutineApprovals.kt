package com.clma.app.data.botmode

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private const val TAG = "BotRoutineApprovals"
private const val PREFS_NAME = "bot_routine_approvals"
private const val PREFS_KEY = "parked_runs"

/**
 * A Bot routine run that stopped because a tool needs the user's yes.
 *
 * The run row ([com.clma.app.data.db.entity.ScheduledJobRunEntity]) keeps outcome
 * `awaiting_approval` while parked; this entry carries what the resume needs to finish that row
 * later without a schema change: the row's immutable fields, the time already worked (waiting
 * is never counted against the run limit) and when the wait gives up.
 */
@Serializable
data class ParkedRoutineRun(
    val conversationId: String,
    val assistantId: String,
    val jobId: String,
    val jobName: String,
    val runId: String,
    val mode: String = "llm",
    val scheduledAtMs: Long,
    val startedAtMs: Long,
    val ledgerId: String? = null,
    /** Time the run spent working (model + tools) before this park, summed over earlier parks. */
    val workedMs: Long,
    val parkedAtMs: Long,
    val expiresAtMs: Long,
    /** True while a resume worker is driving the turn again (the user answered every card). */
    val resuming: Boolean = false,
)

/** Pure rules for parked runs; kept apart from the store so they are unit-testable. */
object BotRoutineApprovalRules {
    /** Wall-clock cap on the WORK of one routine run; matches the existing cron 15 min cap. */
    const val RUN_LIMIT_MS: Long = 15L * 60_000L

    /** A parked run whose cards nobody answered within this window is cancelled. */
    const val APPROVAL_TTL_MS: Long = 24L * 60L * 60_000L

    /**
     * Budget left for a resumed run: the limit minus the time already worked (waiting excluded).
     * Zero once the limit is used up, so repeated approval cycles cannot stretch the work past
     * [RUN_LIMIT_MS] in total.
     */
    fun remainingBudgetMs(workedMs: Long, limitMs: Long = RUN_LIMIT_MS): Long =
        (limitMs - workedMs.coerceAtLeast(0L)).coerceAtLeast(0L)

    fun isExpired(run: ParkedRoutineRun, nowMs: Long): Boolean = nowMs >= run.expiresAtMs

    fun expiresAt(parkedAtMs: Long, ttlMs: Long = APPROVAL_TTL_MS): Long = parkedAtMs + ttlMs
}

/**
 * Process-wide store of parked Bot routine runs, persisted in SharedPreferences so a parked run
 * survives the worker exiting and the process dying. Small (one entry per waiting run).
 */
object BotRoutineApprovals {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ParkedRoutineRun.serializer())
    @Volatile private var prefs: SharedPreferences? = null
    private val _parked = MutableStateFlow<List<ParkedRoutineRun>>(emptyList())

    /** Every parked run, oldest first. */
    val parked: StateFlow<List<ParkedRoutineRun>> = _parked.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString(PREFS_KEY, null) ?: return
        _parked.value = runCatching { json.decodeFromString(serializer, raw) }
            .onFailure { Log.w(TAG, "init: unreadable parked runs, dropping", it) }
            .getOrDefault(emptyList())
    }

    fun get(conversationId: Uuid): ParkedRoutineRun? = get(conversationId.toString())

    fun get(conversationId: String): ParkedRoutineRun? =
        _parked.value.firstOrNull { it.conversationId == conversationId }

    fun isParked(conversationId: Uuid): Boolean = get(conversationId) != null

    /** Parked runs of routine [jobId]. */
    fun ofJob(jobId: String): List<ParkedRoutineRun> = _parked.value.filter { it.jobId == jobId }

    /**
     * True when ledger row [ledgerId] belongs to a run that is legitimately waiting for the
     * user (parked, not mid-resume), so boot recovery must not call it stranded.
     */
    fun isWaitingLedger(ledgerId: String): Boolean =
        _parked.value.any { it.ledgerId == ledgerId && !it.resuming }

    fun conversationIds(): Set<String> = _parked.value.mapTo(mutableSetOf()) { it.conversationId }

    @Synchronized
    fun put(run: ParkedRoutineRun) {
        _parked.value = _parked.value.filterNot { it.conversationId == run.conversationId } + run
        persist()
    }

    @Synchronized
    fun remove(conversationId: String) {
        val next = _parked.value.filterNot { it.conversationId == conversationId }
        if (next.size == _parked.value.size) return
        _parked.value = next
        persist()
    }

    private fun persist() {
        val p = prefs ?: return
        p.edit().putString(PREFS_KEY, json.encodeToString(serializer, _parked.value)).apply()
    }
}
