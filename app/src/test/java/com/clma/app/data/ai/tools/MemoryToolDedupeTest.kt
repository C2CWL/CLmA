package com.clma.app.data.ai.tools

import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.model.AssistantMemory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `memory_tool` create now checks the existing records first: a near duplicate is not stored and
 * the tool answers with the candidate to merge into, while `force: true` still stores a separate
 * record. The real tool builder is exercised against an in-memory list.
 */
class MemoryToolDedupeTest {

    private val json = Json { encodeDefaults = true }

    private inner class Store {
        val memories = mutableListOf(AssistantMemory(1, "用户喜欢简短回复，周末更活跃。"))
        val creations = mutableListOf<String>()

        fun tool(): Tool = buildMemoryTools(
            json = json,
            existing = { memories.toList() },
            onCreation = { content ->
                creations += content
                AssistantMemory(7, content).also { memories += it }
            },
            onUpdate = { id, content -> AssistantMemory(id, content) },
            onDelete = { },
        ).single()
    }

    private fun executeJson(tool: Tool, args: String): String = runBlocking {
        val parts = tool.execute(json.parseToJsonElement(args))
        (parts.single() as UIMessagePart.Text).text
    }

    @Test
    fun `near duplicate create is refused and nothing is stored`() {
        val store = Store()
        val payload = json.parseToJsonElement(
            executeJson(store.tool(), """{"action":"create","content":"用户喜欢简短回复，周末比较活跃。"}""")
        ).jsonObject

        assertEquals("merge_required", payload["result"]?.jsonPrimitive?.content)
        val candidate = payload["candidates"]!!.jsonArray.single().jsonObject
        assertEquals(1, candidate["id"]!!.jsonPrimitive.int)
        assertTrue(store.creations.isEmpty())
        assertEquals(1, store.memories.size)
    }

    @Test
    fun `unrelated create is stored normally`() {
        val store = Store()
        val payload = json.parseToJsonElement(
            executeJson(store.tool(), """{"action":"create","content":"项目发布只用韩国服务器。"}""")
        ).jsonObject

        assertEquals(7, payload["id"]!!.jsonPrimitive.int)
        assertEquals(listOf("项目发布只用韩国服务器。"), store.creations)
    }

    @Test
    fun `force stores a near duplicate as its own record`() {
        val store = Store()
        val payload = json.parseToJsonElement(
            executeJson(
                store.tool(),
                """{"action":"create","content":"用户喜欢简短回复，周末比较活跃。","force":true}"""
            )
        ).jsonObject

        assertEquals(7, payload["id"]!!.jsonPrimitive.int)
        assertEquals(1, store.creations.size)
        assertEquals(2, store.memories.size)
    }

    @Test
    fun `edit is not gated by the duplicate check`() {
        val store = Store()
        val payload = json.parseToJsonElement(
            executeJson(store.tool(), """{"action":"edit","id":1,"content":"用户喜欢简短回复。"}""")
        ).jsonObject

        assertEquals(1, payload["id"]!!.jsonPrimitive.int)
        assertTrue(store.creations.isEmpty())
    }
}
