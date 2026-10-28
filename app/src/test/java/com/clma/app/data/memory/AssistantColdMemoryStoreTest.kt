package com.clma.app.data.memory

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AssistantColdMemoryStoreTest {
    @Test fun `legacy notes copy without overwriting and never return after deletion`() {
        val root = Files.createTempDirectory("cold-memory-test").toFile()
        try {
            val legacy = File(root, "legacy").apply { mkdirs() }
            File(legacy, "INDEX.md").writeText("old index")
            File(legacy, "note.md").writeText("old note")
            File(legacy, "ignore.txt").writeText("not memory")
            val target = File(root, "assistant-a").apply { mkdirs() }
            File(target, "INDEX.md").writeText("new index")
            val store = AssistantColdMemoryStore(target, legacy)
            assertEquals("new index", store.readText("INDEX.md"))
            assertEquals("old note", store.readText("note.md"))
            assertEquals(2, store.listFiles().size)
            assertTrue(File(legacy, "note.md").isFile)
            assertTrue(store.delete("note.md"))
            assertNull(AssistantColdMemoryStore(target, legacy).readText("note.md"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `assistants and expert namespaces remain isolated without a workspace`() {
        val root = Files.createTempDirectory("cold-memory-test").toFile()
        try {
            val a = AssistantColdMemoryStore(File(root, "a/memory"), null)
            val b = AssistantColdMemoryStore(File(root, "b/memory"), null)
            val expert = AssistantColdMemoryStore(File(root, "a/agents/expert/memory"), null)
            a.writeText("note.md", "a")
            b.writeText("note.md", "b")
            expert.writeText("note.md", "expert")
            assertEquals("a", a.readText("note.md"))
            assertEquals("b", b.readText("note.md"))
            assertEquals("expert", expert.readText("note.md"))
            for (name in listOf("../note.md", "nested/note.md", "nested\\note.md")) {
                try { a.readText(name); fail("Accepted unsafe name") }
                catch (_: IllegalArgumentException) { }
            }
        } finally { root.deleteRecursively() }
    }
}
