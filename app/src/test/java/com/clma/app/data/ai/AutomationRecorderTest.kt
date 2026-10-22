package me.rerere.rikkahub.data.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the host-side observation record that feeds the per-app playbook. The interesting
 * behaviour is: bounded growth, newest-wins de-duplication, and the privacy default (values are
 * clipped and made single-line; typed text is never recorded at all — that is the caller's job).
 */
class AutomationRecorderTest {

    @Before
    fun setUp() {
        AutomationRecorder.reset()
    }

    @After
    fun tearDown() {
        AutomationRecorder.reset()
    }

    @Test
    fun `drain returns the observations per package and clears them`() {
        AutomationRecorder.recordEntry("com.tencent.mm", "launch_app")
        AutomationRecorder.recordAction("com.tencent.mm", ".ui.LauncherUI", "click", "text=\"搜索\"", true)
        AutomationRecorder.recordAction("com.tencent.mm", ".ui.LauncherUI", "click", "text=\"Send\"", false)

        val drained = AutomationRecorder.drain()

        assertEquals(1, drained.size)
        assertEquals("com.tencent.mm", drained.first().packageName)
        assertEquals(
            listOf(
                "- 入口 launch_app",
                "- LauncherUI｜click text=\"搜索\"｜ok",
                "- LauncherUI｜click text=\"Send\"｜miss",
            ),
            drained.first().lines,
        )
        // Draining twice must not re-write the same observations.
        assertTrue(AutomationRecorder.drain().isEmpty())
    }

    @Test
    fun `reset drops everything, so a turn cannot leak into the next`() {
        AutomationRecorder.recordEntry("com.tencent.mm", "launch_app")
        AutomationRecorder.reset()
        assertTrue(AutomationRecorder.drain().isEmpty())
    }

    @Test
    fun `a blank package is ignored`() {
        AutomationRecorder.recordEntry("", "launch_app")
        assertTrue(AutomationRecorder.drain().isEmpty())
    }

    @Test
    fun `an unknown screen renders as a placeholder rather than an empty slot`() {
        AutomationRecorder.recordAction("com.x", null, "click", null, true)
        assertEquals(listOf("- ?｜click｜ok"), AutomationRecorder.drain().first().lines)
    }

    @Test
    fun `per package growth is bounded and keeps the newest`() {
        repeat(AutomationRecorder.MAX_LINES_PER_PACKAGE + 5) { i ->
            AutomationRecorder.recordAction("com.x", ".S", "click", "text=\"b$i\"", true)
        }
        val lines = AutomationRecorder.drain().first().lines
        assertEquals(AutomationRecorder.MAX_LINES_PER_PACKAGE, lines.size)
        assertTrue(lines.last().contains("b${AutomationRecorder.MAX_LINES_PER_PACKAGE + 4}"))
    }

    @Test
    fun `selector labels are compact, single line and clipped`() {
        assertEquals("text=\"搜索\"", AutomationRecorder.selectorLabel("text", "搜索"))
        assertEquals("cd=\"send\"", AutomationRecorder.selectorLabel("content_description", "send"))
        assertEquals("vid=chat_send_button", AutomationRecorder.selectorLabel("view_id_resource_name", "chat_send_button"))
        assertNull(AutomationRecorder.selectorLabel("bogus", "x"))
        assertNull(AutomationRecorder.selectorLabel("text", ""))

        val clipped = AutomationRecorder.selectorLabel("text", "a".repeat(200))!!
        assertTrue(clipped.length < 200)
        // A newline in a node's text must not split the observation across lines.
        assertTrue(AutomationRecorder.selectorLabel("text", "a\nb")!!.contains("a b"))
    }

    @Test
    fun `node labels prefer the structural view id`() {
        assertEquals("vid=ok_button", AutomationRecorder.nodeLabel("ok_button", "登录", null))
        assertEquals("text=\"登录\"", AutomationRecorder.nodeLabel(null, "登录", null))
        assertEquals("cd=\"登录\"", AutomationRecorder.nodeLabel(null, null, "登录"))
        assertNull(AutomationRecorder.nodeLabel(null, null, null))
    }
}
