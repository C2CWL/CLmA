// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/memory-store.ts, server/workspace.ts, server/memory-topics.ts, server/memory-entries.ts, server/memory-forget.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.memory

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal object BotMemoryCaps {
    const val INDEX_LINES = 200
    const val INDEX_BYTES = 24_000
    const val FILE_BYTES = 256 * 1024
    const val TOPICS = 256
    const val NOTE_CHARS = 1_000
    const val LOG_ENTRY_CHARS = 1_000
    const val RECENT_LOG_BYTES = 4_000

    fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size

    fun prefix(text: String, maxBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    fun index(text: String) = prefix(
        text.lineSequence().take(INDEX_LINES).joinToString("\n"),
        INDEX_BYTES,
    )
}

data class BotManagedMemory(val id: String, val content: String)

internal class BotMemoryStore(filesDir: File, botId: String, private val clock: Clock = Clock.systemDefaultZone()) {
    private val root = filesDir.canonicalFile
    private val directory = "botmode/bots/${UUID.fromString(botId)}"

    fun index(): String = synchronized(lock) { read(file("MEMORY.md")) }
    fun saveIndex(text: String, expected: String) = synchronized(lock) {
        check(index() == expected) { "MEMORY.md changed" }
        write(file("MEMORY.md"), text)
    }

    fun topics(): List<String> = synchronized(lock) {
        markdownNames("memory").filter { runCatching { topicName(it) == it }.getOrDefault(false) }
    }

    fun logs(): List<String> = synchronized(lock) {
        markdownNames("log").filter { runCatching { LocalDate.parse(it).toString() == it }.getOrDefault(false) }
            .sortedDescending()
    }

    fun readTopic(name: String): String = synchronized(lock) { read(topicFile(name)) }
    fun deleteTopic(name: String) = synchronized(lock) { Files.deleteIfExists(topicFile(name).toPath()) }
    fun readLog(day: String): String = synchronized(lock) { read(file("log/${LocalDate.parse(day)}.md")) }
    fun today(): LocalDate = LocalDate.now(clock)

    fun remember(text: String, topic: String? = null, maxChars: Int = BotMemoryCaps.NOTE_CHARS): String = synchronized(lock) {
        val note = oneLine(text)
        require(note.isNotEmpty() && note.length <= maxChars) { "Use one fact of up to $maxChars characters" }
        val target = topic?.let(::topicFile) ?: file("MEMORY.md")
        if (topic != null) require(target.exists() || topics().size < BotMemoryCaps.TOPICS) { "Too many topics" }
        val before = read(target)
        val entry = "- ${today()} · $note"
        val identity = fact(entry)
        val existing = before.lineSequence().firstOrNull {
            datedEntry.containsMatchIn(it) && fact(it) == identity
        }
        if (existing != null) return@synchronized existing
        write(target, append(before, entry))
        entry
    }

    fun managedEntries(): List<BotManagedMemory> = synchronized(lock) {
        index().lineSequence().filter { datedEntry.containsMatchIn(it) }.distinct().map(::managedEntry).toList()
    }

    fun deleteManagedEntry(id: String): Boolean = synchronized(lock) {
        val before = index()
        val entry = managedEntries().singleOrNull { it.id == id } ?: return@synchronized false
        write(file("MEMORY.md"), before.lineSequence().filterNot { it == entry.content }.joinToString("\n"))
        true
    }

    fun managedEntry(line: String): BotManagedMemory {
        val digest = MessageDigest.getInstance("SHA-256").digest("$directory\n$line".toByteArray(Charsets.UTF_8))
        return BotManagedMemory(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest), line)
    }

    fun forget(passage: String): Int = synchronized(lock) {
        val folded = fold(passage)
        require(folded.count(Char::isLetterOrDigit) >= 3) { "Use at least three letters or digits" }
        val pattern = if (folded.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
            Regex(Regex.escape(folded))
        } else Regex("(?<![\\p{L}\\p{N}])${Regex.escape(folded)}(?![\\p{L}\\p{N}])")
        var removed = 0
        for (target in listOf(file("MEMORY.md")) + topics().map(::topicFile)) {
            val before = read(target)
            val after = before.lineSequence().filterNot {
                pattern.containsMatchIn(fold(it)).also { matches -> if (matches) removed++ }
            }.joinToString("\n")
            if (before != after) write(target, after)
        }
        removed
    }

    fun appendLog(text: String, conversationId: String? = null) = synchronized(lock) {
        val now = LocalDateTime.now(clock)
        val target = file("log/${now.toLocalDate()}.md")
        val body = oneLine(text).take(BotMemoryCaps.LOG_ENTRY_CHARS)
        require(body.isNotBlank()) { "Write one non-blank event" }
        val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        val source = conversationId?.let { " · ${UUID.fromString(it)}" }.orEmpty()
        write(target, append(read(target), "- $time$source · $body"))
    }

    fun upkeepEnabled(): Boolean = synchronized(lock) { read(file("upkeep-disabled")).isEmpty() }
    fun setUpkeepEnabled(enabled: Boolean) = synchronized(lock) {
        val marker = file("upkeep-disabled")
        if (enabled) Files.deleteIfExists(marker.toPath()) else write(marker, "disabled")
        Unit
    }
    fun lastOrganized(): Long? = synchronized(lock) { read(file("last-organized")).toLongOrNull() }

    fun organize(expected: String, index: String, moves: Map<String, List<String>>, automatic: Boolean) =
        synchronized(lock) {
            check(this.index() == expected) { "MEMORY.md changed" }
            if (automatic && !upkeepEnabled()) return@synchronized
            check(BotMemoryCaps.index(index) == index) { "Organized index exceeds the prompt cap" }
            val updates = moves.map { (name, entries) ->
                val target = topicFile(name)
                val old = read(target)
                val seen = old.lineSequence().map(::fact).toMutableSet()
                val updated = entries.filter { seen.add(fact(it)) }.fold(old, ::append)
                require(BotMemoryCaps.bytes(updated) <= BotMemoryCaps.FILE_BYTES) { "Topic is full" }
                target to updated
            }
            require((topics() + moves.keys).distinct().size <= BotMemoryCaps.TOPICS) { "Too many topics" }
            // Copy before removing anything from the index. Retrying skips entries already copied.
            updates.forEach { (target, updated) -> write(target, updated) }
            if (index != expected) write(file("MEMORY.md"), index)
            write(file("last-organized"), clock.millis().toString())
        }

    private fun topicFile(name: String): File {
        val topic = topicName(name)
        return file("memory/$topic.md")
    }

    private fun markdownNames(folder: String): List<String> = file(folder).listFiles().orEmpty()
        .filter { it.extension == "md" && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }
        .map { it.nameWithoutExtension }.sorted()

    private fun file(relative: String): File {
        var current = root
        for (part in "$directory/$relative".split('/')) {
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath())) { "Memory paths cannot be links" }
        }
        return current
    }

    private fun read(target: File): String {
        if (Files.notExists(target.toPath(), NOFOLLOW_LINKS)) return ""
        if (!Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS)) throw IOException("Invalid memory file")
        return target.inputStream().use {
            val data = it.readNBytesCompat(BotMemoryCaps.FILE_BYTES + 1)
            require(data.size <= BotMemoryCaps.FILE_BYTES) { "Memory file exceeds size cap" }
            data.toString(Charsets.UTF_8)
        }
    }

    private fun write(target: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= BotMemoryCaps.FILE_BYTES) { "Memory file exceeds size cap" }
        val parent = requireNotNull(target.parentFile)
        Files.createDirectories(parent.toPath())
        val temp = Files.createTempFile(parent.toPath(), ".memory-", ".tmp")
        try {
            temp.toFile().outputStream().use { it.write(bytes); it.fd.sync() }
            Files.move(temp, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        private val lock = Any()
        private val spaced = Regex("[\\s\\p{Z}]+")
        private val datedEntry = Regex("^- \\d{4}-\\d{2}-\\d{2} · ")
        private val heading = Regex("^[-*+] (?:\\d{4}-\\d{2}-\\d{2} · )?")
        private val validTopic = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{0,63}")

        fun topicName(input: String): String {
            val name = Normalizer.normalize(input.trim(), Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT).removeSuffix(".md").replace(spaced, "-")
            require(validTopic.matches(name) && !name.contains("..") &&
                !name.endsWith('.') && BotMemoryCaps.bytes(name) <= 240
            ) { "Invalid topic name" }
            return name
        }

        fun oneLine(text: String): String = text.replace(spaced, " ").trim()
        fun fact(line: String): String = fold(line.trim().replaceFirst(heading, ""))
        private fun fold(text: String): String = oneLine(Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"'))

        private fun append(before: String, line: String) = if (before.isEmpty()) "$line\n"
            else "${before.trimEnd('\n')}\n$line\n"
    }
}

/** Bounded read compatible with Android API 26. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
