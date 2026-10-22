package com.clma.app.data.ai.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExtractedTextCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `uploaded file is cached under upload extracted with a size and mtime key`() {
        val upload = tmp.newFolder("upload")
        val source = File(upload, "abc.docx").apply { writeText("zip bytes") }
        assertNull(ExtractedTextCache.read(source))

        val cache = ExtractedTextCache.write(source, "全文")!!
        assertEquals(File(upload, ".extracted"), cache.parentFile)
        assertEquals("abc.docx.${source.length()}-${source.lastModified()}.txt", cache.name)
        assertEquals("/upload/.extracted/${cache.name}", ExtractedTextCache.workspacePathOf(cache))
        assertEquals("全文", ExtractedTextCache.read(source))
    }

    @Test
    fun `changed source invalidates and replaces the old cache`() {
        val upload = tmp.newFolder("upload")
        val source = File(upload, "abc.docx").apply { writeText("v1") }
        val first = ExtractedTextCache.write(source, "old")!!
        source.writeText("version two")
        source.setLastModified(source.lastModified() + 5_000)
        assertNull(ExtractedTextCache.read(source))
        val second = ExtractedTextCache.write(source, "new")!!
        assertFalse(first.exists())
        assertTrue(second.exists())
        assertEquals("new", ExtractedTextCache.read(source))
    }

    @Test
    fun `files outside the upload folder are not cached`() {
        val other = tmp.newFolder("other")
        val source = File(other, "a.docx").apply { writeText("x") }
        assertNull(ExtractedTextCache.cacheFileFor(source))
        assertNull(ExtractedTextCache.write(source, "text"))
    }
}
