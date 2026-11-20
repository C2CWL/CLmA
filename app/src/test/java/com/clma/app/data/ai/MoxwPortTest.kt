package com.clma.app.data.ai

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.InstanceRuntimeSnapshot.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MoxwPortTest {
    @Test fun `projection preserves calls and recent results without changing chat history`() {
        val original = (0..7).map { index ->
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool("call-$index", "read_file", "{}", listOf(
                    UIMessagePart.Text("$index " + "long output ".repeat(1000)),
                )),
            ))
        }
        val spilled = mutableListOf<String>()
        val projected = MicroCompaction.project(original) { _, text ->
            spilled.add(text)
            "saved.txt"
        }
        assertEquals(2, spilled.size)
        assertEquals(original.drop(2), projected.drop(2))
        val first = projected.first().parts.single() as UIMessagePart.Tool
        val originalFirst = original.first().parts.single() as UIMessagePart.Tool
        assertEquals(originalFirst, first.copy(output = originalFirst.output))
        assertTrue((first.output.single() as UIMessagePart.Text).text.contains("saved.txt"))
        assertEquals((originalFirst.output.single() as UIMessagePart.Text).text, spilled.first())
        assertSame(original, MicroCompaction.project(original) { _, _ -> null })
    }

    @Test fun `repeat guidance escalates independently of execution policy`() {
        val allowed = LoopGuardDecision(block = false, priorOccurrences = 2)
        val refused = allowed.copy(block = true)
        assertNull(RepeatBreaker.grade(allowed.copy(priorOccurrences = 1), 0))
        assertEquals(RepeatBreaker.Grade.NUDGE, RepeatBreaker.grade(allowed, 0))
        assertEquals(RepeatBreaker.Grade.ESCALATE, RepeatBreaker.grade(refused, 1))
        assertEquals(RepeatBreaker.Grade.STOP, RepeatBreaker.grade(refused, 2))
    }

    @Test fun `turn summary distinguishes failed completed and pending calls`() {
        val log = TurnEventLog { 42L }
        log.append(TurnEvent.ToolCalled("done", "test"))
        log.append(TurnEvent.ToolCompleted("done", "test", ok = false))
        log.append(TurnEvent.ToolCalled("pending", "test"))
        log.append(TurnEvent.Recovered(null, "stream_restart"))
        assertEquals(2, log.summary().toolCallCount)
        assertEquals(1, log.summary().completedToolCallCount)
        assertEquals(1, log.summary().failureCount)
        assertEquals(1, log.summary().recoveryCount)
        assertEquals(listOf("pending"), log.summary().pendingToolCallIds)
        assertEquals(listOf(0L, 1L, 2L, 3L), log.snapshot().events.map { it.sequence })
    }

    @Test fun `only confirmed absence hides dependent tools and keeps diagnostics`() {
        assertTrue(InstanceRuntimeSnapshot(Presence.PRESENT, Presence.UNKNOWN).unavailableToolNames().isEmpty())
        val hidden = InstanceRuntimeSnapshot(Presence.ABSENT, Presence.ABSENT).unavailableToolNames()
        assertTrue(hidden.containsAll(listOf("termux_run_command", "transcribe_audio_file", "shizuku_exec", "virtual_display_launch")))
        assertFalse(hidden.any { it in setOf("whisper_status", "virtual_display_list", "virtual_display_destroy") })
    }
}
