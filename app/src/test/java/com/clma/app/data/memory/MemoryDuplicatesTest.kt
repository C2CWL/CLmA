package com.clma.app.data.memory

import com.clma.app.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Near-duplicate detection behind `memory_tool` create: the normalisation, the similarity score
 * and the candidate pick are pure, so they are pinned here without a database.
 */
class MemoryDuplicatesTest {

    @Test
    fun `normalisation ignores case punctuation and spacing`() {
        assertEquals("user prefers brief replies", normalizeMemoryText("  User, prefers BRIEF replies!  "))
    }

    @Test
    fun `equivalent rewording scores above the threshold`() {
        assertTrue(
            memorySimilarity(
                "用户喜欢简短回复，周末更活跃。",
                "用户喜欢简短回复，周末比较活跃。",
            ) > MEMORY_DUPLICATE_THRESHOLD
        )
    }

    @Test
    fun `unrelated or empty text stays below the threshold`() {
        assertTrue(
            memorySimilarity("用户喜欢简短回复。", "发布只用韩国服务器。") < MEMORY_DUPLICATE_THRESHOLD
        )
        assertTrue(memorySimilarity("", "anything at all") < MEMORY_DUPLICATE_THRESHOLD)
        assertTrue(memorySimilarity("项目发布只用韩国服务器。", "") < MEMORY_DUPLICATE_THRESHOLD)
    }

    @Test
    fun `findMemoryDuplicate returns the closest record`() {
        val existing = listOf(
            AssistantMemory(1, "项目发布只用韩国服务器。"),
            AssistantMemory(2, "用户喜欢简短回复，周末更活跃。"),
        )
        val hit = findMemoryDuplicate("用户喜欢简短回复，周末比较活跃。", existing)
        assertNotNull(hit)
        assertEquals(2, hit!!.memory.id)
        assertTrue(hit.similarity > MEMORY_DUPLICATE_THRESHOLD)
    }

    @Test
    fun `findMemoryDuplicate returns null when nothing is close`() {
        assertNull(findMemoryDuplicate("发布走韩国服务器", listOf(AssistantMemory(1, "用户喜欢简短回复。"))))
        assertNull(findMemoryDuplicate("", listOf(AssistantMemory(1, "用户喜欢简短回复。"))))
        assertNull(findMemoryDuplicate("用户喜欢简短回复。", emptyList()))
    }
}
