package com.clma.app.data.botmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotTaskChipTest {

    private fun step(id: String, status: BotTaskStepStatus) = BotTaskStep(id, id, status)

    @Test
    fun `idle shows nothing`() {
        assertNull(botTaskChipSummary(listOf(step("idle", BotTaskStepStatus.PENDING)), isGenerating = false))
        assertNull(botTaskChipSummary(emptyList(), isGenerating = false))
    }

    @Test
    fun `current step is the first unfinished one`() {
        val steps = listOf(
            step("tool:a", BotTaskStepStatus.DONE),
            step("tool:b", BotTaskStepStatus.DONE),
            step("tool:c", BotTaskStepStatus.ACTIVE),
            step("tool:d", BotTaskStepStatus.PENDING),
        )
        val s = botTaskChipSummary(steps, isGenerating = true)!!
        assertEquals(3, s.current)
        assertEquals(4, s.total)
        assertEquals("tool:c", s.title)
        assertFalse(s.allDone)
    }

    @Test
    fun `finished run reports all done on the last step`() {
        val steps = listOf(step("tool:a", BotTaskStepStatus.DONE), step("tool:b", BotTaskStepStatus.DONE))
        val s = botTaskChipSummary(steps, isGenerating = false)!!
        assertTrue(s.allDone)
        assertEquals(2, s.current)
    }
}
