package com.clma.ai.util

/**
 * Size-bounded LRU cache of base64-encoded media, so the same images are not decoded,
 * re-compressed and re-encoded on every request, tool step and retry.
 *
 * Keys must identify the exact output (path + size + mtime + encode parameters), so a changed
 * file or different settings never get stale data. Size is counted in characters; base64 is
 * ASCII, which ART stores one byte per character.
 */
class EncodedMediaCache(private val maxChars: Long) {
    private val entries = LinkedHashMap<String, String>(16, 0.75f, true)
    private var totalChars = 0L

    @Synchronized
    fun get(key: String): String? = entries[key]

    @Synchronized
    fun put(key: String, value: String) {
        // An entry larger than a quarter of the budget would evict most of the cache for one item.
        if (value.length > maxChars / 4) return
        entries.remove(key)?.let { totalChars -= it.length }
        entries[key] = value
        totalChars += value.length
        val iterator = entries.entries.iterator()
        while (totalChars > maxChars && iterator.hasNext()) {
            val eldest = iterator.next()
            totalChars -= eldest.value.length
            iterator.remove()
        }
    }

    @Synchronized
    fun sizeChars(): Long = totalChars

    @Synchronized
    fun clear() {
        entries.clear()
        totalChars = 0L
    }

    companion object {
        /** Process-wide cache for request images: 32 MB. */
        val images = EncodedMediaCache(maxChars = 32L * 1024 * 1024)

        fun keyFor(path: String, length: Long, lastModified: Long, params: String): String =
            "$path|$length|$lastModified|$params"
    }
}
