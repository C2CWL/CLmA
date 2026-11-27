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
        val original = (0..7).map(::toolResult)
        val spilled = mutableListOf<String>()
        val projection = MicroCompaction().project(original, inputBudget = 1) { _, text ->
            spilled.add(text)
            "saved.txt"
        }
        val projected = projection.messages
        assertEquals(2, projection.newResults)
        assertEquals(2, spilled.size)
        assertEquals(original.drop(2), projected.drop(2))
        val first = projected.first().getTools().single()
        val originalFirst = original.first().getTools().single()
        assertEquals(originalFirst, first.copy(output = originalFirst.output))
        assertTrue((first.output.single() as UIMessagePart.Text).text.contains("saved.txt"))
        assertEquals((originalFirst.output.single() as UIMessagePart.Text).text, spilled.first())
        assertSame(original, MicroCompaction().project(original, 1) { _, _ -> null }.messages)
    }

    @Test fun `pressure triggers batches while append alone preserves the projected prefix`() {
        val projector = MicroCompaction()
        val original = (0..7).map(::toolResult)
        val firstBudget = ContextBudgetPlanner.estimateContextTokens(original).toLong()
        val noSpill: (String, String) -> String? = { _, _ -> error("unexpected spill") }
        assertSame(original, projector.project(original, null, spill = noSpill).messages)
        assertSame(original, projector.project(original, firstBudget + 1, spill = noSpill).messages)

        val batch = projector.project(original, firstBudget) { id, _ -> "$id.txt" }
        assertEquals(2, batch.newResults)
        val next = original + toolResult(8)
        val appended = projector.project(next, firstBudget, spill = noSpill)
        assertEquals(0, appended.newResults)
        assertEquals(batch.messages, appended.messages.dropLast(1))

        val nextBudget = ContextBudgetPlanner.estimateContextTokens(appended.messages).toLong()
        val nextBatch = projector.project(next, nextBudget) { id, _ -> "$id.txt" }
        assertEquals(1, nextBatch.newResults)
        assertEquals(batch.messages.take(2), nextBatch.messages.take(2))
        assertEquals(next.takeLast(6), nextBatch.messages.takeLast(6))
    }

    @Test fun `edited output cannot reuse a stale spill reference`() {
        val projector = MicroCompaction()
        val original = (0..6).map(::toolResult)
        assertEquals(1, projector.project(original, 1) { _, _ -> "old.txt" }.newResults)
        val edited = listOf(original.first().copy(parts = toolResult(9).parts.map {
            (it as UIMessagePart.Tool).copy(toolCallId = "call-0")
        })) + original.drop(1)
        assertSame(edited, projector.project(edited, null) { _, _ -> error("unexpected spill") }.messages)
    }

    @Test fun `missing spill keeps the original result readable`() {
        val projector = MicroCompaction()
        val original = (0..6).map(::toolResult)
        assertEquals(1, projector.project(original, 1) { _, _ -> "saved.txt" }.newResults)
        val restored = projector.project(original, null, isReadable = { false }) { _, _ -> error("unexpected spill") }
        assertSame(original, restored.messages)
    }

    @Test fun `input budget respects output allowance and unknown windows`() {
        assertNull(MicroCompaction.inputBudget(null, null))
        assertEquals(80_000L, MicroCompaction.inputBudget(100_000, null))
        assertEquals(70_000L, MicroCompaction.inputBudget(100_000, 30_000))
    }

    private fun toolResult(index: Int) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool("call-$index", "read_file", "{}", listOf(
            UIMessagePart.Text("$index " + "long output ".repeat(1000)),
        ))),
    )

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
