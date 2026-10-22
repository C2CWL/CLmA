package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the naming and clipping contract of [AppPlaybookRules]. The behaviour around it — the
 * cold-memory read and the tool-result wiring — is device/socket-bound and covered by the tool
 * envelopes instead; what is testable on a bare JVM lives here.
 */
class AppPlaybookTest {

    @Test
    fun builds_a_single_segment_markdown_name_for_a_package() {
        assertEquals("app-com.tencent.mm.md", AppPlaybookRules.fileNameFor("com.tencent.mm"))
        assertEquals("app-com.android.settings.md", AppPlaybookRules.fileNameFor("com.android.settings"))
        // Underscored / digit-containing package names are legal too.
        assertEquals("app-excp.rikkahub.debug.md", AppPlaybookRules.fileNameFor("excp.rikkahub.debug"))
        // Surrounding whitespace is trimmed, not part of the name.
        assertEquals("app-com.termux.md", AppPlaybookRules.fileNameFor("  com.termux  "))
    }

    @Test
    fun the_name_is_always_writable_through_cold_memory_rules() {
        // A name the playbook writes must survive the very rules memory_write enforces, or the
        // agent would be told to write a document it can never persist.
        for (pkg in listOf("com.tencent.mm", "com.android.settings", "excp.rikkahub.debug")) {
            val name = AppPlaybookRules.fileNameFor(pkg)!!
            assertTrue(name, ColdMemoryRules.isValidWriteName(name))
        }
    }

    @Test
    fun refuses_blank_or_escaping_package_names() {
        assertNull(AppPlaybookRules.fileNameFor(""))
        assertNull(AppPlaybookRules.fileNameFor("   "))
        // Directory separators, spaces and traversal are refused rather than sanitized.
        assertNull(AppPlaybookRules.fileNameFor("com/tencent/mm"))
        assertNull(AppPlaybookRules.fileNameFor("com\\tencent\\mm"))
        assertNull(AppPlaybookRules.fileNameFor("com tencent mm"))
        assertNull(AppPlaybookRules.fileNameFor("../../etc/passwd"))
        assertNull(AppPlaybookRules.fileNameFor("com..tencent"))
    }

    @Test
    fun clips_a_long_playbook_and_marks_the_truncation() {
        val short = "WeChat: 通讯录 tab → search icon → type name → 发送."
        assertEquals(short, AppPlaybookRules.clipForSurface(short))

        val long = "x".repeat(AppPlaybookRules.MAX_SURFACED_CHARS + 500)
        val clipped = AppPlaybookRules.clipForSurface(long)
        assertTrue(clipped.startsWith("x".repeat(64)))
        assertTrue(clipped.endsWith(AppPlaybookRules.TRUNCATION_MARKER))
        assertTrue(clipped.length < long.length)
    }

    @Test
    fun trims_a_stored_playbook_without_altering_its_text() {
        assertEquals("note", AppPlaybookRules.clipForSurface("  \n note \n "))
        assertEquals("", AppPlaybookRules.clipForSurface("   "))
    }
}
