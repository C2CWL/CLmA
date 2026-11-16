package com.clma.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integrity checks for the curated catalog. These are cheap invariants that would otherwise
 * only surface as a failed multi-gigabyte download or a broken-looking model picker.
 */
class LlamaCppCatalogTest {

    @Test
    fun `vision picks include a separate Q8 vision projector`() {
        val entries = LlamaCppCatalog.ENTRIES.filter { "vision" in it.tags }
        assertTrue(entries.isNotEmpty())
        entries.forEach { entry ->
            assertTrue(entry.file.endsWith("Q8_0.gguf"))
            // New downloads ship the Q8 projector; an already installed F16 one stays
            // accepted at load time, so only the catalog entry is pinned here.
            assertTrue(requireNotNull(entry.projectorFile).endsWith("Q8_0.gguf"))
            assertTrue(entry.projectorSizeBytes > 0)
            assertTrue(requireNotNull(entry.projectorUrl()).startsWith("https://huggingface.co/"))
        }
    }

    @Test
    fun `compaction trial points to full weights and is explicitly experimental`() {
        val entry = LlamaCppCatalog.ENTRIES.single { "compaction" in it.tags }
        assertEquals("lewisdog/qwen3-1.7b-cogs-ingest-GGUF", entry.repo)
        assertEquals("qwen3-1.7b-cogs-ingest-Q8_0.gguf", entry.file)
        assertEquals(1834426496L, entry.sizeBytes)
        assertTrue("experimental" in entry.tags)
        assertTrue("non-thinking" in entry.tags)
        assertEquals(null, entry.projectorFile)
        assertEquals(
            "https://huggingface.co/lewisdog/qwen3-1.7b-cogs-ingest-GGUF/resolve/main/qwen3-1.7b-cogs-ingest-Q8_0.gguf",
            entry.resolveUrl(),
        )
    }

    @Test
    fun `every entry points at a gguf file`() {
        LlamaCppCatalog.ENTRIES.forEach { entry ->
            assertTrue(
                "${entry.displayName}: file \"${entry.file}\" is not a .gguf file",
                entry.file.endsWith(".gguf"),
            )
        }
    }

    @Test
    fun `every entry has a non-zero size`() {
        LlamaCppCatalog.ENTRIES.forEach { entry ->
            assertTrue(
                "${entry.displayName} has a non-positive sizeBytes",
                entry.sizeBytes > 0L,
            )
        }
    }

    @Test
    fun `display names are unique`() {
        val names = LlamaCppCatalog.ENTRIES.map { it.displayName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `download urls are https resolve urls ending in gguf`() {
        LlamaCppCatalog.ENTRIES.forEach { entry ->
            val url = entry.resolveUrl()
            assertTrue(url, url.startsWith("https://huggingface.co/"))
            assertTrue(url, "/resolve/main/" in url)
            assertTrue(url, url.endsWith(".gguf"))
        }
    }
}
