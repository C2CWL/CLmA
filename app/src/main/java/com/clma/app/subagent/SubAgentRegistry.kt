package com.clma.app.subagent

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/** Live job handles plus durable run history and a completion inbox. Mutations and claims
 * share one lock; publication follows the atomic disk write, never the other way around.
 * Recovery records interrupted jobs as FAILED/process_lost; it does not resume execution.
 */
class SubAgentRegistry(private val store: SubAgentStore? = null) {
    private val lock = Any()
    private val acknowledged = mutableSetOf<String>()
    private val claims = mutableMapOf<String, Set<String>>()
    private val _runs = MutableStateFlow<Map<String, SubAgentRun>>(emptyMap())
    val runs: StateFlow<Map<String, SubAgentRun>> = _runs

    init {
        val snapshot = store?.read() ?: SubAgentSnapshot()
        acknowledged.addAll(snapshot.acknowledged)
        val recovered = snapshot.runs.associate { run ->
            run.id to if (run.status == SubAgentStatus.PENDING || run.status == SubAgentStatus.RUNNING) {
                run.copy(status = SubAgentStatus.FAILED, finishedAtMs = System.currentTimeMillis(),
                    error = "process_lost: execution interrupted; not resumed")
            } else run
        }
        if (recovered.isNotEmpty()) publish(recovered)
    }

    private fun publish(current: Map<String, SubAgentRun>, ack: Set<String> = acknowledged) {
        // Pending/unacknowledged completions are never dropped to meet the history cap.
        val removable = current.values.filter {
            it.status != SubAgentStatus.PENDING && it.status != SubAgentStatus.RUNNING &&
                (!it.completionDeliveryEnabled || !shouldNotifyParent(it) || it.id in ack)
        }.sortedBy { it.finishedAtMs ?: it.startedAtMs }
        val evicted = removable.take((current.size - HISTORY_CAP).coerceAtLeast(0)).map { it.id }.toSet()
        val kept = current - evicted
        val keptAck = ack.intersect(kept.keys)
        store?.write(SubAgentSnapshot(kept.values.toList(), keptAck))
        acknowledged.clear()
        acknowledged.addAll(keptAck)
        _runs.value = kept
    }

    data class Delivery(val token: String, val runs: List<SubAgentRun>)

    /** Claims are process-local: a crash releases them, while the pending record survives. */
    fun claim(parentChatId: String, parentAssistantId: String): Delivery = synchronized(lock) {
        val held = claims.values.flatten().toSet()
        val pending = _runs.value.values.filter {
            it.parentChatId == parentChatId && it.parentAssistantId == parentAssistantId &&
                it.runInBackground && it.completionDeliveryEnabled && shouldNotifyParent(it) &&
                it.id !in acknowledged && it.id !in held
        }.sortedBy { it.finishedAtMs ?: it.startedAtMs }.take(20)
        val token = java.util.UUID.randomUUID().toString()
        claims[token] = pending.map { it.id }.toSet()
        Delivery(token, pending)
    }

    /** Ack only after the containing model request completes successfully, not on dequeue. */
    fun acknowledge(delivery: Delivery) = synchronized(lock) {
        val ids = claims[delivery.token] ?: return@synchronized
        publish(_runs.value, acknowledged + ids)
        claims.remove(delivery.token)
    }

    fun release(delivery: Delivery) { synchronized(lock) { claims.remove(delivery.token) } }

    companion object { const val HISTORY_CAP = 200 }

    /**
     * Side-table of cancellable Jobs for currently RUNNING runs. Removed once the run
     * reaches a terminal status. Kept separate from the StateFlow because [Job] is not
     * serialisable and we don't want UI consumers re-collecting on Job-pointer churn.
     */
    private val activeJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    fun addPending(run: SubAgentRun, job: Job? = null) {
        synchronized(lock) {
            val safeRun = if (run.noResult) run.copy(result = null) else run
            publish(_runs.value + (run.id to safeRun))
        }
        if (job != null) activeJobs[run.id] = job
    }

    fun update(id: String, transform: (SubAgentRun) -> SubAgentRun) = synchronized(lock) {
        val existing = _runs.value[id] ?: return@synchronized
        val updated = transform(existing)
        // no_result is suppression at rest too, not just a model-facing encoder guard.
        publish(_runs.value + (id to if (updated.noResult) updated.copy(result = null) else updated))
    }

    fun setJob(id: String, job: Job) {
        activeJobs[id] = job
    }

    fun get(id: String): SubAgentRun? = _runs.value[id]

    /** Tool callers must supply both identities; missing context fails closed. */
    fun getOwned(id: String, parentChatId: String?, parentAssistantId: String?): SubAgentRun? =
        get(id)?.takeIf {
            parentChatId != null && parentAssistantId != null &&
                it.parentChatId == parentChatId && it.parentAssistantId == parentAssistantId
        }

    fun listOwned(activeOnly: Boolean, parentChatId: String?, parentAssistantId: String?): List<SubAgentRun> =
        list(activeOnly).filter {
            parentChatId != null && parentAssistantId != null &&
                it.parentChatId == parentChatId && it.parentAssistantId == parentAssistantId
        }

    fun list(activeOnly: Boolean): List<SubAgentRun> {
        val all = _runs.value.values
        return if (activeOnly) all.filter { it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING }
        else all.toList()
    }

    fun activeCountForAssistant(parentAssistantId: String): Int =
        _runs.value.values.count {
            it.parentAssistantId == parentAssistantId &&
                (it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING)
        }

    fun globalActiveCount(): Int =
        _runs.value.values.count {
            it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING
        }

    /**
     * Cancel a single run by id. Returns true if a cancellable job existed; false if the
     * run was already in a terminal state or if the id is unknown. Marking the status to
     * CANCELLED is the caller's job (typically the engine after the Job's onCompletion
     * fires) so we don't double-write.
     */
    fun requestCancel(id: String): Boolean {
        val job = activeJobs.remove(id) ?: return false
        job.cancel()
        return true
    }

    /**
     * Cancel every currently-active run dispatched from [parentChatId]. Hooked into the
     * Telegram /stop handler and the in-app stop button so a single tick takes down the
     * parent generation AND all of its sub-agents. Returns the count cancelled.
     */
    fun cancelAllForParent(parentChatId: String): Int {
        var count = 0
        val toCancel = _runs.value.values
            .filter { it.parentChatId == parentChatId && (it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING) }
            .map { it.id }
        for (runId in toCancel) {
            if (requestCancel(runId)) count++
        }
        return count
    }

    fun clearJob(id: String) {
        activeJobs.remove(id)
    }

}
