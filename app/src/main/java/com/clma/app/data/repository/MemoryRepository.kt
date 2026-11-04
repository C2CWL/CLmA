package com.clma.app.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.clma.app.data.memory.MemoryOperation
import com.clma.app.data.memory.MemoryOperationHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.clma.app.data.db.dao.MemoryDAO
import com.clma.app.data.db.entity.MemoryEntity
import com.clma.app.data.model.AssistantMemory

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    val history: MemoryOperationHistory,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map { AssistantMemory(it.id, it.content) }
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String, source: String = "tool"): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val newMemory = old.copy(
            content = content
        )
        if (old.content != content) memoryDAO.updateMemory(newMemory)
        record(old.assistantId, if (old.content == content) "no_op" else "edit", source, id, old.content.length, content.length)
        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
        )
    }

    suspend fun addMemory(assistantId: String, content: String, source: String = "tool"): AssistantMemory {
        val memory = AssistantMemory(
            id = 0,
            content = content,
        )
        val newMemory = memory.copy(
            id = memoryDAO.insertMemory(
                MemoryEntity(
                    assistantId = assistantId,
                    content = memory.content
                )
            ).toInt()
        )
        record(assistantId, "create", source, newMemory.id, 0, content.length)
        return newMemory
    }

    suspend fun deleteMemory(id: Int, source: String = "tool") {
        val old = memoryDAO.getMemoryById(id) ?: return
        memoryDAO.deleteMemory(id)
        record(old.assistantId, "delete", source, id, old.content.length, 0)
    }

    private suspend fun record(scope: String, action: String, source: String, id: Int, before: Int, after: Int) {
        withContext(Dispatchers.IO) {
            history.record(MemoryOperation(System.currentTimeMillis(), scope, "hot", action, source, id, before, after))
        }
    }
}
