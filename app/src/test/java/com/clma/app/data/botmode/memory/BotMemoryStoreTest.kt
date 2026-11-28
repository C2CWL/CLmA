package com.clma.app.data.botmode.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BotMemoryStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val bot = "00000000-0000-0000-0000-000000000001"
    private val other = "00000000-0000-0000-0000-000000000002"

    private fun store(id: String = bot, at: String = "2026-11-28T23:58:00Z") =
        BotMemoryStore(folder.root, id, Clock.fixed(Instant.parse(at), ZoneOffset.UTC))

    @Test
    fun `files are isolated and stale or oversized edits preserve the original`() {
        val memory = store()
        memory.saveIndex("Original", "")
        assertThrows(IllegalStateException::class.java) { memory.saveIndex("Stale", "") }
        assertThrows(IllegalArgumentException::class.java) {
            memory.saveIndex("x".repeat(BotMemoryCaps.FILE_BYTES + 1), "Original")
        }
        assertEquals("Original", memory.index())
        assertEquals("", store(other).index())
        assertTrue(File(folder.root, "botmode/bots/$bot/MEMORY.md").isFile)
    }

    @Test
    fun `topic names normalize Unicode and reject paths`() {
        assertEquals("team-notes", BotMemoryStore.topicName(" Team Notes.MD "))
        assertEquals("中文-主题", BotMemoryStore.topicName("中文　主题"))
        for (name in listOf("../other", "folder/file", "prefix\\file", ".hidden", "x..x", "a".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) { BotMemoryStore.topicName(name) }
        }
        val memory = store()
        memory.remember("One fact", "Team Notes")
        assertEquals(listOf("team-notes"), memory.topics())
        assertTrue(memory.readTopic("team-notes").contains("One fact"))
        assertTrue(memory.deleteTopic("team-notes"))
        assertTrue(memory.topics().isEmpty())
    }

    @Test
    fun `symlinked paths cannot reach classic files`() {
        val memory = store()
        memory.saveIndex("Original", "")
        val classic = folder.newFile("classic.md").apply { writeText("classic secret") }
        val dir = File(folder.root, "botmode/bots/$bot/memory").apply { mkdirs() }
        Files.createSymbolicLink(File(dir, "linked.md").toPath(), classic.toPath())
        assertThrows(IllegalArgumentException::class.java) { memory.readTopic("linked") }
        assertTrue(memory.topics().isEmpty())
        assertEquals("classic secret", classic.readText())
    }

    @Test
    fun `caps use whole UTF-8 characters and only the first 200 lines`() {
        assertEquals("汉", BotMemoryCaps.prefix("汉😀", 6))
        assertEquals(24_000, BotMemoryCaps.bytes(BotMemoryCaps.index("汉".repeat(8001))))
        val text = (1..201).joinToString("\n") { "fact $it" }
        val excerpt = BotMemoryCaps.index(text)
        assertEquals(200, excerpt.lines().size)
        assertFalse(excerpt.contains("fact 201"))
    }

    @Test
    fun `prompt assembles capped index topic names and recent log entries`() {
        val memory = store()
        memory.saveIndex((1..201).joinToString("\n") { "fact $it" }, "")
        memory.remember("Topic body is loaded on demand", "Research")
        store(at = "2026-11-25T10:00:00Z").appendLog("old event")
        store(at = "2026-11-26T10:00:00Z").appendLog("recent event")
        memory.appendLog("today event")
        val prompt = memory.buildPrompt()
        assertTrue(prompt.contains("Your memory (MEMORY.md):\nfact 1"))
        assertTrue(prompt.contains("initial memory excerpt"))
        assertTrue(prompt.contains("- research"))
        assertTrue(prompt.contains("today event") && prompt.contains("recent event"))
        for (excluded in listOf("Topic body", "old event", "fact 201")) assertFalse(prompt.contains(excluded))
    }

    @Test
    fun `daily log appends one line per entry with local dates and timestamps`() {
        val memory = store()
        memory.appendLog("first\n  event", other)
        memory.appendLog("second event")
        store(at = "2026-11-29T00:00:00Z").appendLog("next day")
        assertEquals(listOf("2026-11-29", "2026-11-28"), memory.logs())
        assertEquals("- 23:58 · $other · first event\n- 23:58 · second event\n", memory.readLog("2026-11-28"))
        assertEquals("- 00:00 · next day\n", memory.readLog("2026-11-29"))
        assertEquals("", store(other).readLog("2026-11-28"))
    }

    @Test
    fun `management ids are scoped to the bot and reject changed content`() {
        val first = store()
        val second = store(other)
        first.remember("same fact")
        second.remember("same fact")
        val id = first.managedEntries().single().id
        assertFalse(second.deleteManagedEntry(id))
        val before = first.index()
        first.saveIndex(before.replace("same fact", "updated fact"), before)
        assertFalse(first.deleteManagedEntry(id))
        assertTrue(first.deleteManagedEntry(first.managedEntries().single().id))
        assertEquals("", first.index())
        assertTrue(second.index().contains("same fact"))
    }

    @Test
    fun `forget matches whole words and leaves logs alone`() {
        val memory = store()
        memory.remember("timezone IST")
        memory.remember("visit the dentist")
        memory.remember("switch from IST", "work")
        memory.appendLog("timezone IST")
        assertEquals(2, memory.forget("ist"))
        assertTrue(memory.index().contains("dentist"))
        assertEquals("", memory.readTopic("work"))
        assertTrue(memory.readLog("2026-11-28").contains("timezone IST"))
    }

    @Test
    fun `organization checks concurrent edits and never modifies logs`() {
        val memory = store()
        val entry = memory.remember("Keep the project detail")
        val before = memory.index()
        memory.appendLog("done")
        assertThrows(IllegalStateException::class.java) {
            memory.organize("stale", "", mapOf("project" to listOf(entry)), automatic = false)
        }
        assertTrue(memory.topics().isEmpty())
        memory.setUpkeepEnabled(false)
        memory.organize(before, "", mapOf("project" to listOf(entry)), automatic = true)
        assertEquals(before, memory.index())
        memory.organize(before, "", mapOf("project" to listOf(entry)), automatic = false)
        assertEquals("", memory.index())
        assertEquals("$entry\n", memory.readTopic("project"))
        assertEquals(Instant.parse("2026-11-28T23:58:00Z").toEpochMilli(), memory.lastOrganized())
        assertTrue(memory.readLog("2026-11-28").contains("done"))
    }
}
