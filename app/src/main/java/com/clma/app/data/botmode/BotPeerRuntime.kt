package com.clma.app.data.botmode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
enum class PeerStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, TIMED_OUT }

@Serializable
enum class PeerOrigin { PERSON, OUTSIDE }

@Serializable
data class PeerThread(val id: String, val owner: String, val botId: String, val conversationId: String)

@Serializable
data class PeerTask(
    val id: String,
    val rootId: String,
    val parentId: String?,
    val origin: PeerOrigin,
    val requester: String,
    val sourceBot: String,
    val botId: String,
    val threadId: String,
    val chain: List<String>,
    val delivery: String,
    val brief: String,
    val status: PeerStatus = PeerStatus.QUEUED,
    val result: String = "",
    val detail: String = "",
) {
    val terminal: Boolean get() = status !in setOf(PeerStatus.QUEUED, PeerStatus.RUNNING)
}

@Serializable
data class PeerSnapshot(val threads: List<PeerThread> = emptyList(), val tasks: List<PeerTask> = emptyList())

interface PeerStore {
    fun load(): PeerSnapshot
    fun save(snapshot: PeerSnapshot)
}

interface PeerHost {
    suspend fun isBot(id: String): Boolean
    suspend fun createConversation(botId: String, requester: String): String
    suspend fun turn(thread: PeerThread, task: PeerTask): String
    suspend fun stop(conversationId: String)
}

/** A separate bounded lane; generic sub-agent recursion remains forbidden. */
class BotPeerRuntime(private val scope: CoroutineScope, private val store: PeerStore, private val host: PeerHost) {
    companion object {
        const val MAX_DEPTH = 3
        const val MAX_EXECUTIONS = 12
        const val MAX_STEPS = 8
        const val TIMEOUT_MS = 120_000L
        const val MAX_RECORDS = 512
    }

    private val lock = Mutex()
    private var snapshot = store.load().let { state ->
        state.copy(tasks = state.tasks.map {
            if (it.terminal) it else it.copy(status = PeerStatus.FAILED, detail = "process_restarted; not replayed")
        })
    }.also(store::save)
    private val jobs = mutableMapOf<String, Job>()
    private val settling = mutableSetOf<String>()
    private val active = java.util.concurrent.ConcurrentHashMap<String, PeerTask>()

    fun context(conversationId: String): PeerTask? = active[conversationId]

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun submit(requester: String, sourceBot: String, botId: String, brief: String,
                       delivery: String, threadId: String? = null, unattended: Boolean = false, rootKey: String = Uuid.random().toString()): PeerTask {
        require(brief.isNotBlank() && brief.length <= 20_000) { "invalid_brief" }
        require(delivery in setOf("ask_bot", "delegate_bot", "start_thread", "coordinate_bots")) { "invalid_delivery" }
        require(host.isBot(sourceBot) && host.isBot(botId)) { "bot_not_found" }
        return lock.withLock {
            val parent = active[requester]
            val chain = parent?.chain ?: listOf(sourceBot)
            require(botId !in chain) { "peer_cycle" }
            require(chain.size <= MAX_DEPTH) { "peer_depth_exceeded" }
            require(parent == null || parent.id !in settling && snapshot.tasks.any { it.id == parent.id && !it.terminal }) { "parent_settled" }
            val rootId = parent?.rootId ?: "$requester:$rootKey"
            require(snapshot.tasks.count { it.rootId == rootId } < MAX_EXECUTIONS) { "peer_budget_exceeded" }
            require(snapshot.tasks.size < MAX_RECORDS) { "peer_capacity" }
            val thread = if (threadId != null) {
                snapshot.threads.singleOrNull { it.id == threadId && it.owner == requester && it.botId == botId }
                    ?: error("peer_thread_not_found")
            } else {
                require(snapshot.threads.size < MAX_RECORDS) { "peer_thread_capacity" }
                PeerThread(Uuid.random().toString(), requester, botId, host.createConversation(botId, requester))
            }
            // Never wait on a thread lock while holding a model generation slot.
            require(snapshot.tasks.none { it.threadId == thread.id && !it.terminal }) { "peer_thread_busy" }
            val task = PeerTask(Uuid.random().toString(), rootId, parent?.id,
                parent?.origin ?: if (unattended) PeerOrigin.OUTSIDE else PeerOrigin.PERSON,
                requester, sourceBot, botId, thread.id, chain + botId, delivery, brief)
            val next = snapshot.copy(
                threads = if (threadId == null) snapshot.threads + thread else snapshot.threads,
                tasks = snapshot.tasks + task,
            )
            store.save(next)
            snapshot = next
            // ATOMIC: the body always runs, even if cancelled before dispatch, so the
            // finally block in execute() settles the task instead of leaving it QUEUED.
            val job = scope.launch(start = CoroutineStart.ATOMIC) { execute(task, thread) }
            jobs[task.id] = job
            task
        }
    }

    suspend fun get(requester: String, id: String): PeerTask? = lock.withLock {
        snapshot.tasks.singleOrNull { it.id == id && it.requester == requester }
    }

    suspend fun await(requester: String, id: String): PeerTask {
        val job = lock.withLock {
            require(snapshot.tasks.any { it.id == id && it.requester == requester }) { "peer_task_not_found" }
            jobs[id]
        }
        try { job?.join() } catch (e: CancellationException) {
            withContext(NonCancellable) { cancel(requester, id) }
            throw e
        }
        return requireNotNull(get(requester, id))
    }

    suspend fun cancel(requester: String, id: String): PeerTask {
        val job = lock.withLock {
            require(snapshot.tasks.any { it.id == id && it.requester == requester }) { "peer_task_not_found" }
            jobs[id]
        }
        job?.cancel()
        job?.join()
        return requireNotNull(get(requester, id))
    }

    suspend fun coordinate(requester: String, source: String, bots: List<String>, brief: String,
                           unattended: Boolean, rootKey: String): List<PeerTask> = kotlinx.coroutines.coroutineScope {
        require(bots.size in 1..4 && bots.distinct().size == bots.size) { "invalid_peer_group" }
        val tasks = mutableListOf<PeerTask>()
        try {
            for (bot in bots) {
                tasks += submit(requester, source, bot, brief, "coordinate_bots",
                    unattended = unattended, rootKey = rootKey)
            }
            tasks.map { task -> async { await(requester, task.id) } }.awaitAll()
        } catch (e: Exception) {
            withContext(NonCancellable) { tasks.forEach { cancel(requester, it.id) } }
            throw e
        }
    }

    private suspend fun execute(task: PeerTask, thread: PeerThread) {
        var status = PeerStatus.FAILED
        var result = ""
        var detail = ""
        try {
            // Cancelled before dispatch: settle as CANCELLED without ever calling the host.
            currentCoroutineContext().ensureActive()
            change(task.id, PeerStatus.RUNNING)
            active[thread.conversationId] = task
            result = withTimeout(TIMEOUT_MS) {
                require(host.isBot(task.botId) && host.isBot(task.sourceBot)) { "bot_not_found" }
                host.turn(thread, task).take(24_000).also { require(it.isNotBlank()) { "peer_empty_result" } }
            }
            status = PeerStatus.COMPLETED
        } catch (e: TimeoutCancellationException) {
            status = PeerStatus.TIMED_OUT
            detail = "peer_timeout"
        } catch (e: CancellationException) {
            status = PeerStatus.CANCELLED
            detail = "peer_cancelled"
            throw e
        } catch (e: Exception) {
            detail = e.message.orEmpty().take(500)
        } finally {
            withContext(NonCancellable) {
                val children = lock.withLock {
                    settling += task.id
                    snapshot.tasks.filter { it.parentId == task.id && !it.terminal }
                }
                children.forEach { cancel(it.requester, it.id) }
                try {
                    host.stop(thread.conversationId)
                } finally {
                    active.remove(thread.conversationId)
                    try { change(task.id, status, result, detail) }
                    finally { lock.withLock { jobs.remove(task.id); settling.remove(task.id) } }
                }
            }
        }
    }

    private suspend fun change(id: String, status: PeerStatus, result: String = "", detail: String = "") = lock.withLock {
        val next = snapshot.copy(tasks = snapshot.tasks.map {
            if (it.id == id) it.copy(status = status, result = result, detail = detail) else it
        })
        store.save(next)
        snapshot = next
    }
}
