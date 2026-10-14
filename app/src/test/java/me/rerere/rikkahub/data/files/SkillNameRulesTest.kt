package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the Agent Skills spec's `name` rules. */
class SkillNameRulesTest {

    @Test fun `spec-shaped names are valid`() {
        for (name in listOf("pdf-processing", "data-analysis", "code-review", "a", "a1", "x-1-y")) {
            assertTrue("$name should be valid", SkillNameRules.isValid(name))
        }
    }

    @Test fun `names the spec forbids are rejected`() {
        for (name in listOf(
            "PDF-Processing",   // uppercase
            "pdf_processing",   // underscore
            "-pdf",             // leading hyphen
            "pdf-",             // trailing hyphen
            "pdf--processing",  // consecutive hyphens
            "has space",
            "dots.are.wrong",
            "",
        )) {
            assertFalse("$name should be invalid", SkillNameRules.isValid(name))
        }
    }

    @Test fun `length is capped at the spec's 64 characters, not the old 40`() {
        val name = "a".repeat(64)
        assertTrue(SkillNameRules.isValid(name))
        assertFalse(SkillNameRules.isValid("a".repeat(65)))
        assertEquals(64, SkillNameRules.normalize("b".repeat(70)).length)
    }

    @Test fun `normalize folds legacy shapes into the spec shape`() {
        assertEquals("my-skill", SkillNameRules.normalize("My_Skill"))
        assertEquals("a-b", SkillNameRules.normalize("--a--b--"))
        assertEquals("hello-world", SkillNameRules.normalize("  Hello   World  "))
        assertEquals("pdf-processing", SkillNameRules.normalize("PDF Processing!"))
    }

    @Test fun `normalize truncation never leaves a trailing hyphen`() {
        // 65 characters in, one over the cap: truncating mid-token must not leave the
        // separator behind, so the result is shorter than the cap rather than ending in `-`.
        val normalized = SkillNameRules.normalize("a".repeat(63) + " b")
        assertTrue("length ${normalized.length}", normalized.length <= SkillNameRules.MAX_LENGTH)
        assertEquals(63, normalized.length)
        assertTrue(SkillNameRules.isValid(normalized))
        assertFalse(normalized.endsWith("-"))
    }

    @Test fun `normalize returns empty when nothing survives`() {
        assertEquals("", SkillNameRules.normalize("!!!"))
        assertEquals("", SkillNameRules.normalize("   "))
        assertEquals("", SkillNameRules.normalize("---"))
    }

    @Test fun `normalize output always satisfies isValid`() {
        for (input in listOf("My_Skill", "PDF Processing!", "a".repeat(70), "x  y", "Ünicode Náme")) {
            val normalized = SkillNameRules.normalize(input)
            assertTrue("normalize($input) = $normalized", SkillNameRules.isValid(normalized))
        }
    }

    @Test fun `every bundled skill name is spec-valid`() {
        // Guards the shipped assets against a rename that would violate the spec.
        val bundled = listOf(
            "agent-core", "auto-reply", "autonomous-agent", "calculate-hash", "interactive-map",
            "kitchen-adventure", "mood-tracker", "morning-briefing",
            "notification-summarise-and-act", "openclaw-converter", "qr-code", "query-wikipedia",
            "smart-forward", "text-spinner", "virtual-piano",
        )
        for (name in bundled) assertTrue(name, SkillNameRules.isValid(name))
    }
}
