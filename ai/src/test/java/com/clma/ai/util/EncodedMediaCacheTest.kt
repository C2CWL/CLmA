package com.clma.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedMediaCacheTest {
    @Test
    fun `evicts least recently used entries to stay within the budget`() {
        val cache = EncodedMediaCache(maxChars = 100)
        cache.put("a", "a".repeat(25))
        cache.put("b", "b".repeat(25))
        cache.put("c", "c".repeat(25))
        cache.get("a") // a is now the most recently used
        cache.put("d", "d".repeat(25))
        cache.put("e", "e".repeat(25)) // over budget: b (least recently used) goes first
        assertNull(cache.get("b"))
        assertEquals("a".repeat(25), cache.get("a"))
        assertTrue(cache.sizeChars() <= 100)
    }

    @Test
    fun `an entry larger than a quarter of the budget is not cached`() {
        val cache = EncodedMediaCache(maxChars = 100)
        cache.put("big", "x".repeat(26))
        assertNull(cache.get("big"))
        assertEquals(0L, cache.sizeChars())
    }

    @Test
    fun `replacing a key does not double count it`() {
        val cache = EncodedMediaCache(maxChars = 100)
        cache.put("a", "1".repeat(20))
        cache.put("a", "2".repeat(10))
        assertEquals(10L, cache.sizeChars())
    }

    @Test
    fun `key changes with file identity and parameters`() {
        val base = EncodedMediaCache.keyFor("/upload/a.jpg", 10, 20, "image|true")
        assertTrue(base != EncodedMediaCache.keyFor("/upload/a.jpg", 11, 20, "image|true"))
        assertTrue(base != EncodedMediaCache.keyFor("/upload/a.jpg", 10, 21, "image|true"))
        assertTrue(base != EncodedMediaCache.keyFor("/upload/a.jpg", 10, 20, "image|false"))
    }
}
