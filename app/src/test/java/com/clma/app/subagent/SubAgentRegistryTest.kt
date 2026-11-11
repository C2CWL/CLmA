package com.clma.app.subagent

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SubAgentRegistryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun makeRun(
        id: String,
        parentChat: String? = "chat-1",
        parentAssistant: String = "asst-1",
        status: SubAgentStatus = SubAgentStatus.PENDING,
        background: Boolean = false,
        delivery: Boolean = false,
        noResult: Boolean = false,
        result: String? = null,
        finishedAtMs: Long? = null,
        startedAtMs: Long = System.currentTimeMillis(),
    ): SubAgentRun = SubAgentRun(
        id = id,
        parentChatId = parentChat,
        parentAssistantId = parentAssistant,
        label = "label-$id",
        task = "task",
        modelId = null,
        tools = null,
        runInBackground = background,
        noResult = noResult,
        completionDeliveryEnabled = delivery,
        timeoutSeconds = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
        maxTrips = SubAgentDefaults.DEFAULT_MAX_TRIPS,
        status = status,
        result = result,
        startedAtMs = startedAtMs,
        finishedAtMs = finishedAtMs,
    )

    private fun durableRegistry(): Pair<SubAgentRegistry, File> {
        val file = File(tmp.root, "subagent-runs.json")
        return SubAgentRegistry(SubAgentStore(file)) to file
    }

    @Test fun `addPending puts run in flow`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("a"))
        assertEquals(1, r.runs.value.size)
        assertEquals(SubAgentStatus.PENDING, r.runs.value["a"]?.status)
    }

    @Test fun `update transforms existing run`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("a"))
        r.update("a") { it.copy(status = SubAgentStatus.SUCCEEDED, result = "done") }
        assertEquals(SubAgentStatus.SUCCEEDED, r.runs.value["a"]?.status)
        assertEquals("done", r.runs.value["a"]?.result)
    }

    @Test fun `update on missing id is no-op`() {
        val r = SubAgentRegistry()
        r.update("nope") { it.copy(status = SubAgentStatus.SUCCEEDED) }
        assertTrue(r.runs.value.isEmpty())
    }

    @Test fun `list active_only filters terminal`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("running", status = SubAgentStatus.RUNNING))
        r.addPending(makeRun("done", status = SubAgentStatus.SUCCEEDED))
        r.addPending(makeRun("failed", status = SubAgentStatus.FAILED))
        assertEquals(3, r.list(activeOnly = false).size)
        assertEquals(1, r.list(activeOnly = true).size)
        assertEquals("running", r.list(activeOnly = true)[0].id)
    }

    @Test fun `activeCountForAssistant counts only matching parent in non-terminal`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("a1", parentAssistant = "asst-1", status = SubAgentStatus.RUNNING))
        r.addPending(makeRun("a2", parentAssistant = "asst-1", status = SubAgentStatus.SUCCEEDED))
        r.addPending(makeRun("b1", parentAssistant = "asst-2", status = SubAgentStatus.RUNNING))
        assertEquals(1, r.activeCountForAssistant("asst-1"))
        assertEquals(1, r.activeCountForAssistant("asst-2"))
    }

    @Test fun `requestCancel returns false when no job registered`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("a"))
        assertFalse(r.requestCancel("a"))
    }

    @Test fun `cancelAllForParent returns count of cancellable jobs`() {
        val r = SubAgentRegistry()
        for (id in listOf("a", "b", "c")) {
            r.addPending(makeRun(id, parentChat = "chat-X", status = SubAgentStatus.RUNNING), job = Job())
        }
        r.addPending(makeRun("other", parentChat = "chat-Y", status = SubAgentStatus.RUNNING), job = Job())
        r.addPending(makeRun("done", parentChat = "chat-X", status = SubAgentStatus.SUCCEEDED), job = null)
        assertEquals(3, r.cancelAllForParent("chat-X"))
    }

    @Test fun `unknown id returns null on get`() {
        val r = SubAgentRegistry()
        assertNull(r.get("nonexistent"))
    }

    @Test fun `noResult clears result at rest on add and update`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("a", noResult = true, result = "secret"))
        assertNull(r.runs.value["a"]?.result)
        r.update("a") { it.copy(status = SubAgentStatus.SUCCEEDED, result = "still-secret") }
        assertNull(r.runs.value["a"]?.result)
    }

    @Test fun `getOwned and listOwned fail closed without matching parent`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("a", parentChat = "chat-1", parentAssistant = "asst-1", status = SubAgentStatus.SUCCEEDED))
        assertNull(r.getOwned("a", null, "asst-1"))
        assertNull(r.getOwned("a", "chat-1", null))
        assertNull(r.getOwned("a", "chat-2", "asst-1"))
        assertNull(r.getOwned("a", "chat-1", "asst-2"))
        assertEquals("a", r.getOwned("a", "chat-1", "asst-1")?.id)
        assertTrue(r.listOwned(false, null, "asst-1").isEmpty())
        assertEquals(listOf("a"), r.listOwned(false, "chat-1", "asst-1").map { it.id })
    }

    @Test fun `claim acknowledge release keeps undelivered notices replayable`() {
        val (r, _) = durableRegistry()
        r.addPending(
            makeRun(
                "bg",
                status = SubAgentStatus.SUCCEEDED,
                background = true,
                delivery = true,
                result = "done",
                finishedAtMs = 10L,
            ),
        )
        val first = r.claim("chat-1", "asst-1")
        assertEquals(listOf("bg"), first.runs.map { it.id })
        // Concurrent claim while held must not double-deliver.
        assertTrue(r.claim("chat-1", "asst-1").runs.isEmpty())
        r.release(first)
        val again = r.claim("chat-1", "asst-1")
        assertEquals(listOf("bg"), again.runs.map { it.id })
        r.acknowledge(again)
        r.release(again)
        assertTrue(r.claim("chat-1", "asst-1").runs.isEmpty())
    }

    @Test fun `claim ignores foreign assistant and foreground completions`() {
        val r = SubAgentRegistry()
        r.addPending(
            makeRun(
                "mine",
                status = SubAgentStatus.SUCCEEDED,
                background = true,
                delivery = true,
                result = "x",
                finishedAtMs = 1L,
            ),
        )
        r.addPending(
            makeRun(
                "theirs",
                parentAssistant = "asst-2",
                status = SubAgentStatus.SUCCEEDED,
                background = true,
                delivery = true,
                result = "y",
                finishedAtMs = 2L,
            ),
        )
        r.addPending(
            makeRun(
                "fg",
                status = SubAgentStatus.SUCCEEDED,
                background = false,
                delivery = false,
                result = "z",
                finishedAtMs = 3L,
            ),
        )
        assertEquals(listOf("mine"), r.claim("chat-1", "asst-1").runs.map { it.id })
    }

    @Test fun `process loss marks interrupted runs failed without resuming them`() {
        val file = File(tmp.root, "lost.json")
        val first = SubAgentRegistry(SubAgentStore(file))
        first.addPending(makeRun("running", status = SubAgentStatus.RUNNING, background = true, delivery = true))
        first.addPending(
            makeRun(
                "done",
                status = SubAgentStatus.SUCCEEDED,
                background = true,
                delivery = true,
                result = "kept",
                finishedAtMs = 5L,
            ),
        )
        val recovered = SubAgentRegistry(SubAgentStore(file))
        assertEquals(SubAgentStatus.FAILED, recovered.runs.value["running"]?.status)
        assertTrue(recovered.runs.value["running"]?.error?.startsWith("process_lost:") == true)
        assertEquals(SubAgentStatus.SUCCEEDED, recovered.runs.value["done"]?.status)
        assertEquals("kept", recovered.runs.value["done"]?.result)
        // Interrupted background delivery stays claimable after restart.
        assertEquals(setOf("running", "done"), recovered.claim("chat-1", "asst-1").runs.map { it.id }.toSet())
    }

    @Test fun `history cap never drops unacknowledged deliveries`() {
        val (r, _) = durableRegistry()
        // Fill with acknowledged terminals that are eligible for eviction.
        repeat(SubAgentRegistry.HISTORY_CAP) { i ->
            val id = "old-$i"
            r.addPending(
                makeRun(
                    id,
                    status = SubAgentStatus.SUCCEEDED,
                    background = true,
                    delivery = true,
                    result = "old",
                    finishedAtMs = i.toLong(),
                    startedAtMs = i.toLong(),
                ),
            )
            val d = r.claim("chat-1", "asst-1")
            r.acknowledge(d)
            r.release(d)
        }
        r.addPending(
            makeRun(
                "pending-notice",
                status = SubAgentStatus.SUCCEEDED,
                background = true,
                delivery = true,
                result = "keep-me",
                finishedAtMs = 10_000L,
                startedAtMs = 10_000L,
            ),
        )
        r.addPending(makeRun("extra", status = SubAgentStatus.SUCCEEDED, finishedAtMs = 10_001L, startedAtMs = 10_001L))
        assertTrue(r.runs.value.containsKey("pending-notice"))
        assertEquals(listOf("pending-notice"), r.claim("chat-1", "asst-1").runs.map { it.id })
        assertTrue(r.runs.value.size <= SubAgentRegistry.HISTORY_CAP + 1)
    }
}
