package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessageCollapseTest {

    @Test
    fun `short text is not a likely overflow`() {
        assertFalse(UserMessageCollapse.likelyOverflows("hello"))
        assertFalse(UserMessageCollapse.likelyOverflows("a\nb\nc\nd\ne\nf\ng"))
        assertFalse(UserMessageCollapse.likelyOverflows("a".repeat(239)))
    }

    @Test
    fun `many characters or lines are a likely overflow`() {
        assertTrue(UserMessageCollapse.likelyOverflows("a".repeat(240)))
        assertTrue(UserMessageCollapse.likelyOverflows("\n".repeat(8)))
        assertFalse(UserMessageCollapse.likelyOverflows("\n".repeat(7)))
    }

    @Test
    fun `under the cap stays full height with no toggle`() {
        val decision = UserMessageCollapse.layout(fullHeightPx = 100, limitPx = 180, expanded = false)
        assertEquals(100, decision.heightPx)
        assertFalse(decision.showToggle)
    }

    @Test
    fun `over the cap collapses until expanded and the toggle stays`() {
        val collapsed = UserMessageCollapse.layout(fullHeightPx = 500, limitPx = 180, expanded = false)
        assertEquals(180, collapsed.heightPx)
        assertTrue(collapsed.showToggle)

        val expanded = UserMessageCollapse.layout(fullHeightPx = 500, limitPx = 180, expanded = true)
        assertEquals(500, expanded.heightPx)
        assertTrue(expanded.showToggle)
    }

    @Test
    fun `equal to the cap does not collapse`() {
        val decision = UserMessageCollapse.layout(fullHeightPx = 180, limitPx = 180, expanded = false)
        assertEquals(180, decision.heightPx)
        assertFalse(decision.showToggle)
    }

    @Test
    fun `non positive cap never collapses`() {
        val decision = UserMessageCollapse.layout(fullHeightPx = 500, limitPx = 0, expanded = false)
        assertEquals(500, decision.heightPx)
        assertFalse(decision.showToggle)
    }
}
