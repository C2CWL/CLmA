package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the SKILL.md frontmatter reader.
 *
 * The load-bearing case is `boolean flag reads the YAML-native form`: the shipped
 * `auto_load: true` is a YAML *boolean*, and the pre-alignment `values[key] as? String`
 * accessor returned `null` for it — which silently disabled auto-load for every bundled
 * core skill and made `seedDefaultSkillsIfNeeded` treat them as ordinary skills.
 */
class SkillFrontmatterParserTest {

    private fun fm(yaml: String) = SkillFrontmatterParser.parse("---\n$yaml\n---\n\nbody\n")

    // -- scalars -------------------------------------------------------------------------

    @Test fun `boolean flag reads the YAML-native form`() {
        val frontmatter = fm("name: agent-core\nauto_load: true\nauto_load_path: SOUL.md")
        assertTrue("unquoted `true` must not be dropped", frontmatter.boolean("auto_load"))
        assertEquals("true", frontmatter["auto_load"])
        assertEquals("SOUL.md", frontmatter["auto_load_path"])
    }

    @Test fun `boolean flag also reads the quoted form`() {
        assertTrue(fm("auto_load: \"true\"").boolean("auto_load"))
        assertTrue(fm("auto_load: 'true'").boolean("auto_load"))
        assertTrue(fm("auto_load: yes").boolean("auto_load"))
        assertFalse(fm("auto_load: \"false\"").boolean("auto_load"))
    }

    @Test fun `boolean flag defaults to false when absent or unparseable`() {
        assertFalse(fm("name: x").boolean("auto_load"))
        assertFalse(fm("auto_load: maybe").boolean("auto_load"))
        assertFalse(fm("auto_load: 3").boolean("auto_load"))
        assertTrue(fm("name: x").boolean("auto_load", default = true))
    }

    @Test fun `numbers are readable as text`() {
        val frontmatter = fm("metadata_ish: 1.0\ncount: 7")
        assertEquals("1.0", frontmatter["metadata_ish"])
        assertEquals("7", frontmatter["count"])
    }

    @Test fun `collections are not stringified`() {
        val frontmatter = fm("metadata:\n  author: me\ntools:\n  - a\n  - b")
        assertNull("a mapping must not be read as a scalar", frontmatter["metadata"])
        assertNull("a list must not be read as a scalar", frontmatter["tools"])
    }

    // -- spec fields ---------------------------------------------------------------------

    @Test fun `allowed-tools splits the spec's space-separated form`() {
        val frontmatter = fm("allowed-tools: Read Write Bash(git:*)")
        assertEquals(listOf("Read", "Write", "Bash(git:*)"), frontmatter.list("allowed-tools"))
    }

    @Test fun `allowed-tools accepts a YAML list and commas`() {
        assertEquals(
            listOf("a", "b"),
            fm("allowed-tools:\n  - a\n  - b").list("allowed-tools"),
        )
        assertEquals(listOf("a", "b"), fm("allowed-tools: a, b").list("allowed-tools"))
        assertEquals(emptyList<String>(), fm("name: x").list("allowed-tools"))
    }

    @Test fun `metadata map is read with string values`() {
        val frontmatter = fm("metadata:\n  author: example-org\n  version: \"1.0\"\n  build: 7")
        assertEquals(
            mapOf("author" to "example-org", "version" to "1.0", "build" to "7"),
            frontmatter.map("metadata"),
        )
        assertEquals(emptyMap<String, String>(), fm("metadata: not-a-map").map("metadata"))
        assertEquals(emptyMap<String, String>(), fm("name: x").map("metadata"))
    }

    @Test fun `license and compatibility read as plain strings`() {
        val frontmatter = fm("license: Apache-2.0\ncompatibility: Requires git and network access")
        assertEquals("Apache-2.0", frontmatter["license"])
        assertEquals("Requires git and network access", frontmatter["compatibility"])
    }

    // -- structure -----------------------------------------------------------------------

    @Test fun `body is extracted without the frontmatter`() {
        val content = "---\nname: x\ndescription: y\n---\n\n# Body\n\ntext\n"
        // Trims the leading newline(s) but keeps the body's own trailing newline.
        assertEquals("# Body\n\ntext\n", SkillFrontmatterParser.extractBody(content))
    }

    @Test fun `CRLF frontmatter parses and extracts`() {
        val content = "---\r\nname: x\r\nauto_load: true\r\n---\r\n\r\nbody\r\n"
        assertEquals("true", SkillFrontmatterParser.parse(content)["auto_load"])
        assertEquals("body", SkillFrontmatterParser.extractBody(content).trim())
    }

    @Test fun `content without frontmatter is returned unchanged`() {
        val content = "# Just markdown\n\nno fences here\n"
        assertEquals(content, SkillFrontmatterParser.extractBody(content))
        assertNull(SkillFrontmatterParser.parse(content)["name"])
    }

    @Test fun `duplicate keys are rejected rather than silently last-wins`() {
        val frontmatter = SkillFrontmatterParser.parse("---\nname: a\nname: b\n---\n\nbody\n")
        assertNull(frontmatter["name"])
    }
}
