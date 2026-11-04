package com.clma.app.data.memory

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MemoryOperationHistoryTest {
    @Test fun `journals written before cold file names existed still decode`() {
        val root = Files.createTempDirectory("memory-history-test").toFile()
        try {
            val file = File(root, "operations.json")
            file.writeText("""[{"timestamp":1,"scope":"assistant","category":"cold","action":"create","source":"tool","beforeChars":0,"afterChars":9}]""")
            val history = MemoryOperationHistory(file)
            assertEquals(1, history.entries.value.size)
            assertNull(history.entries.value.single().name)
            assertFalse(history.persistenceFailed.value)
        } finally { root.deleteRecursively() }
    }

    @Test fun `history is bounded and survives reopening without storing memory content`() {
        val root = Files.createTempDirectory("memory-history-test").toFile()
        try {
            val file = File(root, "operations.json")
            val history = MemoryOperationHistory(file)
            repeat(MemoryOperationHistory.LIMIT + 5) { index ->
                history.record(MemoryOperation(index.toLong(), "assistant", "hot", "create", "manual", index))
            }
            assertEquals(MemoryOperationHistory.LIMIT, history.entries.value.size)
            assertEquals(5L, history.entries.value.first().timestamp)
            assertEquals(history.entries.value, MemoryOperationHistory(file).entries.value)
            assertFalse(history.persistenceFailed.value)
            assertFalse(file.readText().contains("content"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `cold mutations and unchanged writes record metadata at the real storage boundary`() {
        val root = Files.createTempDirectory("memory-history-test").toFile()
        try {
            val file = File(root, "operations.json")
            val history = MemoryOperationHistory(file)
            val store = AssistantColdMemoryStore(File(root, "notes"), null, history, "assistant/memory")
            store.writeText("note.md", "private prose")
            store.writeText("note.md", "private prose")
            store.writeText("note.md", "updated prose")
            assertTrue(store.delete("note.md"))
            assertFalse(store.delete("note.md"))
            assertEquals(listOf("create", "no_op", "edit", "delete", "no_op"), history.entries.value.map { it.action })
            assertEquals(13, history.entries.value[1].beforeChars)
            assertFalse(file.readText().contains("private prose"))
            // Cold records identify the Markdown file; the prose itself is never journalled.
            assertEquals(listOf("note.md", "note.md", "note.md", "note.md", "note.md"), history.entries.value.map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun `persistence failure is visible without discarding the successful operation`() {
        val root = Files.createTempDirectory("memory-history-test").toFile()
        try {
            val parent = File(root, "not-a-directory").apply { writeText("blocked") }
            val history = MemoryOperationHistory(File(parent, "operations.json"))
            history.record(MemoryOperation(1, "assistant", "hot", "edit", "manual"))
            assertTrue(history.persistenceFailed.value)
            assertEquals(1, history.entries.value.size)
        } finally { root.deleteRecursively() }
    }
}
