package com.clma.app.data.ai

import com.clma.ai.core.Tool
import com.clma.ai.provider.Model
import com.clma.ai.provider.TextGenerationParams
import com.clma.ai.ui.UIMessage
import com.clma.app.data.datastore.AiLogLevel
import com.clma.app.data.datastore.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AILoggingManagerTest {
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val settings = MutableStateFlow(Settings(aiLogLevel = AiLogLevel.DEBUG))
    private val manager = AILoggingManager(settings, { settings.value = it(settings.value) }, scope)
    private val messages = listOf(UIMessage.user("password=secret personal text"))
    private var schemaReads = 0
    private val params = TextGenerationParams(
        model = Model(modelId = "test"),
        tools = listOf(Tool(
            name = "test",
            description = "test schema",
            parameters = { schemaReads++; null },
            execute = { error("inspection must never execute a tool") },
        )),
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `default off skips capture for streaming and non streaming without disabling normal logs`() {
        assertFalse(manager.getContextInspectionEnabled().value)
        listOf(true, false).forEach { stream ->
            manager.recordGeneration(params, messages, stream, "memory")
        }
        assertEquals(0, schemaReads)
        assertEquals(2, manager.getLogs().value.size)
        manager.getLogs().value.filterIsInstance<AILogging.Generation>().forEach {
            assertNull(it.context)
            assertTrue(it.messages.isEmpty())
            assertEquals(1, it.summary!!.messageCount)
        }
        assertEquals(AiLogLevel.DEBUG, settings.value.aiLogLevel)
        assertTrue(settings.value.recordRequests)
    }

    @Test
    fun `disabling clears every inspection but preserves summaries and does not resurrect previews`() = runBlocking {
        manager.setContextInspectionEnabled(true)
        listOf(true, false).forEach { stream ->
            manager.recordGeneration(params, messages, stream, "memory")
        }
        val captured = manager.getLogs().value.filterIsInstance<AILogging.Generation>()
        assertEquals(2, schemaReads)
        assertTrue(captured.first().context!!.sections.all { it.preview == null })
        assertTrue(captured.last().context!!.sections.any { it.preview != null })
        assertTrue(captured.last().context!!.sections.none { it.preview?.contains("password=secret") == true })

        manager.setContextInspectionEnabled(false)
        assertFalse(settings.value.contextInspectionEnabled)
        assertEquals(2, manager.getLogs().value.size)
        manager.getLogs().value.filterIsInstance<AILogging.Generation>().forEach {
            assertNull(it.context)
            assertNotNull(it.summary)
        }
        // An inspection prepared before the disable cannot be retained afterwards.
        manager.addLog(captured.last())
        assertNull((manager.getLogs().value.last() as AILogging.Generation).context)
        manager.setContextInspectionEnabled(true)
        assertTrue(manager.getLogs().value.filterIsInstance<AILogging.Generation>().all { it.context == null })
        manager.recordGeneration(params, messages, false, "memory")
        assertNotNull((manager.getLogs().value.last() as AILogging.Generation).context)
        assertEquals(AiLogLevel.DEBUG, settings.value.aiLogLevel)
        assertTrue(settings.value.recordRequests)
    }

    @Test
    fun `settings changes strip previews at info and clear logs at off even with inspector enabled`() = runBlocking {
        manager.setContextInspectionEnabled(true)
        manager.recordGeneration(params, messages, true, "memory")
        manager.setLogLevel(AiLogLevel.INFO)
        assertTrue((manager.getLogs().value.single() as AILogging.Generation).context!!.sections.all { it.preview == null })
        manager.recordGeneration(params, messages, false, "memory")
        assertTrue((manager.getLogs().value.last() as AILogging.Generation).context!!.sections.all { it.preview == null })
        manager.setLogLevel(AiLogLevel.OFF)
        assertTrue(manager.getLogs().value.isEmpty())
        manager.recordGeneration(params, messages, false, "memory")
        assertEquals(2, schemaReads)
        assertTrue(manager.getLogs().value.isEmpty())
        assertTrue(settings.value.recordRequests)
    }

    @Test
    fun `restored enabled setting is observed and external disable removes inspections`() {
        val restored = MutableStateFlow(Settings(aiLogLevel = AiLogLevel.DEBUG, contextInspectionEnabled = true))
        val restoredManager = AILoggingManager(restored, { restored.value = it(restored.value) }, scope)
        assertTrue(restoredManager.getContextInspectionEnabled().value)
        restoredManager.recordGeneration(params, messages, false, "memory")
        assertNotNull((restoredManager.getLogs().value.single() as AILogging.Generation).context)
        restored.value = restored.value.copy(contextInspectionEnabled = false)
        assertNull((restoredManager.getLogs().value.single() as AILogging.Generation).context)
    }
}
