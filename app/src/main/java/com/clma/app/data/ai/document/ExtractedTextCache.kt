package com.clma.app.data.ai.document

import java.io.File
import java.nio.charset.Charset

/**
 * Disk cache of the text extracted from uploaded documents.
 *
 * Files live in `filesDir/upload/.extracted/`, which the workspace sees as
 * `/upload/.extracted/`, so the model can read a long document chunk by chunk with
 * workspace_read_file. The cache key is the source file's name + size + modification time, so a
 * replaced file is never served stale text, and a document is parsed once instead of on every
 * request (every turn and every tool step re-sends the history).
 *
 * Only files that already live directly in the `upload` folder are cached.
 */
object ExtractedTextCache {
    const val DIR_NAME = ".extracted"
    private const val UPLOAD_DIR_NAME = "upload"
    private const val WORKSPACE_DIR = "/upload/$DIR_NAME"

    /** Cache file for [source], or null when [source] is not an uploaded file. */
    fun cacheFileFor(source: File): File? {
        val parent = source.parentFile ?: return null
        if (parent.name != UPLOAD_DIR_NAME) return null
        return File(File(parent, DIR_NAME), cacheFileName(source))
    }

    internal fun cacheFileName(source: File): String =
        "${source.name}.${source.length()}-${source.lastModified()}.txt"

    /** Workspace path of a cache file, e.g. `/upload/.extracted/abc.docx.123-456.txt`. */
    fun workspacePathOf(cacheFile: File): String = "$WORKSPACE_DIR/${cacheFile.name}"

    /** Cached text for [source], or null when absent/unreadable. */
    fun read(source: File): String? {
        val cache = cacheFileFor(source) ?: return null
        if (!cache.isFile) return null
        return runCatching { cache.readText(Charsets.UTF_8) }.getOrNull()
    }

    /**
     * Like [write], but transcodes [source] (a plain-text file in [charset], [bomBytes] skipped)
     * into the cache as a stream, so a huge text file never has to be in memory as one String.
     */
    fun writeStreaming(source: File, charset: Charset, bomBytes: Int): File? {
        val cache = cacheFileFor(source) ?: return null
        return runCatching {
            val dir = cache.parentFile!!
            dir.mkdirs()
            dir.listFiles()?.forEach { old ->
                if (old.name != cache.name && old.name.startsWith("${source.name}.")) old.delete()
            }
            val tmp = File(dir, "${cache.name}.tmp")
            source.inputStream().use { input ->
                var toSkip = bomBytes.toLong()
                while (toSkip > 0) {
                    val skipped = input.skip(toSkip)
                    if (skipped <= 0) break
                    toSkip -= skipped
                }
                input.reader(charset).use { reader ->
                    tmp.writer(Charsets.UTF_8).use { writer -> reader.copyTo(writer) }
                }
            }
            if (!tmp.renameTo(cache)) {
                tmp.delete()
                return null
            }
            cache
        }.getOrNull()
    }

    /**
     * Writes [text] as the cache for [source] (UTF-8) and removes older cache files of the same
     * source. Returns the cache file, or null when [source] is not cacheable or the write failed.
     */
    fun write(source: File, text: String): File? {
        val cache = cacheFileFor(source) ?: return null
        return runCatching {
            val dir = cache.parentFile!!
            dir.mkdirs()
            dir.listFiles()?.forEach { old ->
                if (old.name != cache.name && old.name.startsWith("${source.name}.")) old.delete()
            }
            val tmp = File(dir, "${cache.name}.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(cache)) {
                tmp.delete()
                return null
            }
            cache
        }.getOrNull()
    }
}
