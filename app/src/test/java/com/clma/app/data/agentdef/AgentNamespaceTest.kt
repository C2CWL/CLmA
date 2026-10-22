package me.rerere.rikkahub.data.agentdef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D9 — the slug rule is a **path** rule, so the interesting cases are the hostile ones: a name
 * that would escape the workspace (`..`), one that would nest (`a/b`), one that is empty after
 * normalisation, and one that is too long. Everything is pure, so all of it is testable here.
 */
class AgentNamespaceTest {

    @Test
    fun `a plain name slugifies to itself, lowercased`() {
        assertEquals("researcher", AgentNamespace.slugify("Researcher"))
    }

    @Test
    fun `runs of separators collapse to a single dash and are trimmed`() {
        assertEquals("deep-research-bot", AgentNamespace.slugify("  Deep -- Research   Bot! "))
    }

    @Test
    fun `dots are not permitted anywhere so a parent reference cannot be spelled`() {
        assertEquals("a-b", AgentNamespace.slugify("a..b"))
        // A path is flattened to a single segment, never nested and never escaping.
        assertEquals("etc-passwd", AgentNamespace.slugify("../../etc/passwd"))
        assertFalse(AgentNamespace.isValidSlug("a.b"))
        assertFalse(AgentNamespace.isValidSlug(".."))
    }

    @Test
    fun `a dash in the input is a separator, not a literal`() {
        assertEquals("deep-research", AgentNamespace.slugify("Deep -- Research"))
        assertEquals("a-b", AgentNamespace.slugify("a---b"))
        assertEquals("a-b-c", AgentNamespace.slugify("a  _  b / c"))
    }

    @Test
    fun `a name with no usable character yields the empty slug`() {
        assertEquals("", AgentNamespace.slugify("研究员"))
        assertNull(AgentNamespace.normalizeSlug("研究员"))
    }

    @Test
    fun `slugify truncates and never leaves a trailing dash`() {
        val long = "a".repeat(40) + "-" + "b".repeat(40)
        val slug = AgentNamespace.slugify(long)
        assertEquals(AgentNamespace.MAX_SLUG_LENGTH, slug.length)
        assertFalse(slug.endsWith("-"))
        assertTrue(AgentNamespace.isValidSlug(slug))
    }

    @Test
    fun `normalizeSlug accepts a clean slug and repairs a messy one`() {
        assertEquals("researcher", AgentNamespace.normalizeSlug("  Researcher "))
        assertEquals("my-bot", AgentNamespace.normalizeSlug("My_Bot"))
        assertNull(AgentNamespace.normalizeSlug("   "))
        assertNull(AgentNamespace.normalizeSlug(null))
    }

    @Test
    fun `isValidSlug rejects the shapes normalizeSlug would have to repair`() {
        assertFalse(AgentNamespace.isValidSlug(null))
        assertFalse(AgentNamespace.isValidSlug(""))
        assertFalse(AgentNamespace.isValidSlug("-lead"))
        assertFalse(AgentNamespace.isValidSlug("trail-"))
        assertFalse(AgentNamespace.isValidSlug("Upper"))
        assertFalse(AgentNamespace.isValidSlug("a".repeat(AgentNamespace.MAX_SLUG_LENGTH + 1)))
        assertTrue(AgentNamespace.isValidSlug("a-1"))
    }

    @Test
    fun `namespace and cold-memory paths hang off the agents root`() {
        assertEquals("agents/researcher", AgentNamespace.namespaceDirFor("Researcher"))
        assertEquals("agents/researcher/memory", AgentNamespace.coldMemoryDirFor("Researcher"))
    }

    @Test
    fun `no slug means no namespace at all`() {
        assertNull(AgentNamespace.namespaceDirFor(null))
        assertNull(AgentNamespace.namespaceDirFor("  "))
        assertNull(AgentNamespace.coldMemoryDirFor("../.."))
    }
}
