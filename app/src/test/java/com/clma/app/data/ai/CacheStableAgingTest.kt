package com.clma.app.data.ai

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.compaction.PrecisionObservationMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bot cost-saving batches request-only ageing so the request prefix stays byte-identical between
 * steps (a provider prompt cache only matches an unchanged prefix). Batch 1 must keep the classic
 * behaviour exactly.
 */
class CacheStableAgingTest {
    private val longOutput = "HEAD-" + "x".repeat(2_000) + "-TAIL"

    private fun screenshot(n: Int) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "shot-$n",
                toolName = "screenshot",
                input = "{}",
                output = listOf(UIMessagePart.Image("file:///shot/$n.png")),
            ),
        ),
    )

    private fun user(n: Int, image: Boolean = false) = UIMessage(
        role = MessageRole.USER,
        parts = listOfNotNull(
            UIMessagePart.Text("turn $n"),
            if (image) UIMessagePart.Image("file:///upload/$n.jpg") else null,
        ),
    )

    private fun read(n: Int) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "call-$n",
                toolName = "workspace_read_file",
                input = """{"path":"/tmp/$n.txt"}""",
                output = listOf(UIMessagePart.Text(longOutput)),
            ),
        ),
    )

    private fun toolImages(messages: List<UIMessage>) = messages.map { message ->
        message.parts.filterIsInstance<UIMessagePart.Tool>().sumOf { tool -> tool.output.count { it is UIMessagePart.Image } }
    }

    @Test
    fun `tool screenshots with batch one keep only the newest two`() {
        val history = (1..6).map(::screenshot)
        assertEquals(listOf(0, 0, 0, 0, 1, 1), toolImages(history.ageOldToolImages()))
    }

    @Test
    fun `tool screenshots with a batch age in steps and keep the prefix stable in between`() {
        // Growing one screenshot at a time, the aged request for n and n+1 shares its prefix
        // except right at the step boundaries (6 -> 4 aged, 10 -> 8 aged).
        val all = (1..13).map(::screenshot)
        var previous: List<UIMessage>? = null
        var prefixBreaks = 0
        for (n in 1..13) {
            val aged = all.take(n).ageOldToolImages(batch = 4)
            previous?.let { before ->
                if (aged.take(before.size) != before) prefixBreaks++
            }
            previous = aged
        }
        assertEquals(2, prefixBreaks)
        assertEquals(
            listOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1),
            toolImages(all.ageOldToolImages(batch = 4)),
        )
    }

    @Test
    fun `user images with batch one match the classic window and a batch ages in steps`() {
        val history = (1..9).map { user(it, image = true) }
        val classic = history.ageOldUserImages()
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1, 1, 1), classic.map { m -> m.parts.count { it is UIMessagePart.Image } })
        val batched = history.ageOldUserImages(batch = 4)
        // 9 turns, keep 4: 5 over the window, aged in steps of 4 -> the oldest 4 aged.
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1, 1), batched.map { m -> m.parts.count { it is UIMessagePart.Image } })
        // Adding a turn that does not cross a step leaves the earlier messages identical.
        val grown = (history + user(10, image = true)).ageOldUserImages(batch = 4)
        assertEquals(batched, grown.take(batched.size))
    }

    @Test
    fun `observation mask with a batch masks in steps`() {
        fun masked(messages: List<UIMessage>) = messages.map { message ->
            message.parts.filterIsInstance<UIMessagePart.Tool>().any { tool ->
                tool.output.filterIsInstance<UIMessagePart.Text>().any { it.text.contains(PrecisionObservationMask.MARKER) }
            }
        }
        val five = (1..5).map(::read)
        // Classic: newest two verbatim, the other three masked.
        assertEquals(listOf(true, true, true, false, false), masked(PrecisionObservationMask.apply(five, enabled = true)))
        // Batch 8: not enough calls past the window to mask a whole batch yet.
        assertEquals(List(5) { false }, masked(PrecisionObservationMask.apply(five, enabled = true, batch = 8)))
        val ten = (1..10).map(::read)
        val tenMasked = masked(PrecisionObservationMask.apply(ten, enabled = true, batch = 8))
        assertEquals(List(8) { true } + List(2) { false }, tenMasked)
        // One more call does not touch the earlier request.
        val eleven = PrecisionObservationMask.apply(ten + read(11), enabled = true, batch = 8)
        assertEquals(PrecisionObservationMask.apply(ten, enabled = true, batch = 8), eleven.take(10))
        assertTrue(masked(eleven).take(8).all { it })
    }
}
