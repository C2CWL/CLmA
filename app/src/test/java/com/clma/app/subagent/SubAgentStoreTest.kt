package com.clma.app.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SubAgentStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun run(id: String, status: SubAgentStatus = SubAgentStatus.SUCCEEDED) = SubAgentRun(
        id = id,
        parentChatId = "chat-1",
        parentAssistantId = "asst-1",
        label = "label-$id",
        task = "task",
        modelId = null,
        tools = null,
        runInBackground = true,
        completionDeliveryEnabled = true,
        timeoutSeconds = 60,
        maxTrips = 12,
        status = status,
        startedAtMs = 1L,
        finishedAtMs = 2L,
        result = "ok-$id",
    )

    @Test
    fun `round trip preserves runs and acknowledgements`() {
        val file = File(tmp.root, "subagent-runs.json")
        val store = SubAgentStore(file)
        store.write(SubAgentSnapshot(listOf(run("a"), run("b")), setOf("a")))
        val loaded = store.read()
        assertEquals(listOf("a", "b"), loaded.runs.map { it.id })
        assertEquals(setOf("a"), loaded.acknowledged)
        assertTrue(file.exists())
        assertTrue(!File(tmp.root, "subagent-runs.json.tmp").exists())
    }

    @Test
    fun `missing file reads as empty snapshot`() {
        val store = SubAgentStore(File(tmp.root, "missing.json"))
        assertEquals(emptyList<SubAgentRun>(), store.read().runs)
        assertEquals(emptySet<String>(), store.read().acknowledged)
    }
}
