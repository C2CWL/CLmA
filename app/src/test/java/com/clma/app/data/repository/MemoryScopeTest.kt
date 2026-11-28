package com.clma.app.data.repository

import com.clma.app.data.model.Assistant
import com.clma.app.data.model.AssistantLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MemoryScopeTest {
    @Test
    fun `global memory is partitioned by library`() {
        val classic = Assistant(useGlobalMemory = true)
        val bot = classic.copy(library = AssistantLibrary.BOT)
        assertEquals(MemoryRepository.GLOBAL_MEMORY_ID, classic.memoryScopeId())
        assertEquals(MemoryRepository.BOT_GLOBAL_MEMORY_ID, bot.memoryScopeId())
        assertNotEquals(classic.memoryScopeId(), bot.memoryScopeId())
    }

    @Test
    fun `private memory uses the assistant id`() {
        val classic = Assistant(useGlobalMemory = false)
        val bot = classic.copy(library = AssistantLibrary.BOT)
        assertEquals(classic.id.toString(), classic.memoryScopeId())
        assertEquals(bot.id.toString(), bot.memoryScopeId())
    }
}
