package com.clma.app.data.botmode

import com.clma.app.utils.JsonInstant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotPeerRuntimeTest {
    private class Store(initial: PeerSnapshot = PeerSnapshot()) : PeerStore {
        var snapshot = initial
        override fun load() = snapshot
        override fun save(snapshot: PeerSnapshot) {
            this.snapshot = JsonInstant.decodeFromString(PeerSnapshot.serializer(),
                JsonInstant.encodeToString(PeerSnapshot.serializer(), snapshot))
        }
    }

    private class Host : PeerHost {
        var serial = 0
        val stopped = mutableListOf<String>()
        var respond: suspend (PeerThread, PeerTask) -> String = { _, task -> "answer:${task.brief}" }
        override suspend fun isBot(id: String) = id in listOf("a", "b", "c", "d", "e")
        override suspend fun createConversation(botId: String, requester: String) = "conversation-${++serial}"
        override suspend fun turn(thread: PeerThread, task: PeerTask) = respond(thread, task)
        override suspend fun stop(conversationId: String) { stopped += conversationId }
    }

    private suspend fun rejects(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $message")
        } catch (e: IllegalArgumentException) {
            assertEquals(message, e.message)
        }
    }

    @Test fun `answers persist and threads can be reused only by their owner`() = runTest {
        val store = Store()
        val host = Host()
        val runtime = BotPeerRuntime(this, store, host)
        val first = runtime.submit("owner", "a", "b", "first", "start_thread")
        assertEquals(PeerStatus.COMPLETED, runtime.await("owner", first.id).status)
        assertEquals("answer:first", runtime.get("owner", first.id)?.result)
        assertNull(runtime.get("other", first.id))
        val next = runtime.submit("owner", "a", "b", "next", "ask_bot", first.threadId)
        assertEquals(first.threadId, runtime.await("owner", next.id).threadId)
        assertEquals(1, host.serial)
        assertEquals(2, store.snapshot.tasks.size)
        assertTrue(host.stopped.isNotEmpty())
    }

    @Test fun `nested peers inherit origin and budget and reject cycles and excess depth`() = runTest {
        val store = Store()
        val host = Host().apply { respond = { _, _ -> awaitCancellation() } }
        val runtime = BotPeerRuntime(this, store, host)
        val first = runtime.submit("owner", "a", "b", "work", "delegate_bot", unattended = true)
        runCurrent()
        val b = store.snapshot.threads.single().conversationId
        rejects("peer_cycle") { runtime.submit(b, "b", "a", "loop", "ask_bot") }
        val second = runtime.submit(b, "b", "c", "work", "delegate_bot")
        runCurrent()
        val c = store.snapshot.threads.single { it.id == second.threadId }.conversationId
        val third = runtime.submit(c, "c", "d", "work", "delegate_bot")
        runCurrent()
        val d = store.snapshot.threads.single { it.id == third.threadId }.conversationId
        rejects("peer_depth_exceeded") { runtime.submit(d, "d", "e", "deep", "ask_bot") }
        assertEquals(first.rootId, third.rootId)
        assertEquals(second.id, third.parentId)
        assertEquals(PeerOrigin.OUTSIDE, third.origin)
        runtime.cancel("owner", first.id)
        assertTrue(store.snapshot.tasks.all { it.status == PeerStatus.CANCELLED })
        assertNull(runtime.context(b))
        assertNull(runtime.context(c))
        assertNull(runtime.context(d))
    }

    @Test fun `shared execution budget does not reset when a task completes`() = runTest {
        val runtime = BotPeerRuntime(this, Store(), Host())
        repeat(BotPeerRuntime.MAX_EXECUTIONS) {
            val task = runtime.submit("owner", "a", "b", "work", "ask_bot", rootKey = "turn")
            runtime.await("owner", task.id)
        }
        rejects("peer_budget_exceeded") {
            runtime.submit("owner", "a", "b", "extra", "ask_bot", rootKey = "turn")
        }
        val next = runtime.submit("owner", "a", "b", "new turn", "ask_bot", rootKey = "next")
        assertEquals(PeerStatus.COMPLETED, runtime.await("owner", next.id).status)
    }

    @Test fun `timeout settles task and stops host without wall clock waiting`() = runTest {
        val host = Host().apply { respond = { _, _ -> awaitCancellation() } }
        val runtime = BotPeerRuntime(this, Store(), host)
        val task = runtime.submit("owner", "a", "b", "work", "ask_bot")
        runCurrent()
        advanceTimeBy(BotPeerRuntime.TIMEOUT_MS)
        runCurrent()
        assertEquals(PeerStatus.TIMED_OUT, runtime.await("owner", task.id).status)
        assertEquals(1, host.stopped.size)
    }

    @Test fun `cancellation before dispatch still settles the queued task`() = runTest {
        val runtime = BotPeerRuntime(this, Store(), Host())
        val task = runtime.submit("owner", "a", "b", "work", "delegate_bot")
        assertEquals(PeerStatus.CANCELLED, runtime.cancel("owner", task.id).status)
    }

    @Test fun `host failures become failed tasks and restart never replays queued work`() = runTest {
        val store = Store()
        val host = Host().apply { respond = { _, _ -> error("host_failed") } }
        val runtime = BotPeerRuntime(this, store, host)
        val task = runtime.submit("owner", "a", "b", "work", "ask_bot")
        assertEquals("host_failed", runtime.await("owner", task.id).detail)
        assertEquals(PeerStatus.FAILED, runtime.get("owner", task.id)?.status)
        val recovered = Store(store.snapshot.copy(tasks = listOf(task)))
        val restartedHost = Host()
        val restarted = BotPeerRuntime(this, recovered, restartedHost)
        assertEquals(PeerStatus.FAILED, restarted.get("owner", task.id)?.status)
        assertEquals(0, restartedHost.serial)
    }

    @Test fun `coordination returns every outcome including failed peers`() = runTest {
        val host = Host().apply { respond = { _, task ->
            if (task.botId == "c") error("peer_failed") else "ok"
        } }
        val runtime = BotPeerRuntime(this, Store(), host)
        val outcomes = runtime.coordinate("owner", "a", listOf("b", "c"), "brief", false, "group")
        assertEquals(listOf(PeerStatus.COMPLETED, PeerStatus.FAILED), outcomes.map { it.status })
        assertEquals(1, outcomes.map { it.rootId }.distinct().size)
    }
}
