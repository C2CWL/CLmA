package com.clma.app.data.ai

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.compaction.PrecisionObservationMask
import com.clma.app.data.botmode.BotCostPolicy
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.AssistantLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-send estimate must not undercount what a Bot actually sends: Bot cost-saving keeps up to
 * batch-1 more old images (batch 4 -> 3) and tool outputs (batch 8 -> 7) than the classic sliding
 * window. Classic estimates must stay exactly as before.
 */
class BotPreSendEstimateTest {
    private val mb = 1024L * 1024
    private val imageBytes = mb / 3 * 4 + 4
    private val fileSize: (String) -> Long? = { mb }
    private val bot = Assistant(library = AssistantLibrary.BOT)
    private val botImageBatch = BotCostPolicy.imageAgingBatch(bot)
    private val botMaskBatch = BotCostPolicy.observationMaskBatch(bot)

    private fun userImage(n: Int) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text("turn $n"), UIMessagePart.Image("file:///u/$n.jpg")),
    )

    private fun screenshot(n: Int) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "shot-$n",
                toolName = "screenshot",
                input = "{}",
                output = listOf(UIMessagePart.Image("file:///s/$n.png")),
            ),
        ),
    )

    private fun read(n: Int) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "call-$n",
                toolName = "workspace_read_file",
                input = """{"path":"/tmp/$n.txt"}""",
                output = listOf(UIMessagePart.Text("HEAD-" + "y".repeat(4_000) + "-TAIL-$n")),
            ),
        ),
    )

    private fun imageCount(messages: List<UIMessage>) = messages.sumOf { message ->
        message.parts.count { it is UIMessagePart.Image } +
            message.parts.filterIsInstance<UIMessagePart.Tool>().sumOf { tool ->
                tool.output.count { it is UIMessagePart.Image }
            }
    }

    /** What the generation loop really sends for a Bot (image ageing + S1 mask). */
    private fun botRequest(messages: List<UIMessage>) = PrecisionObservationMask.apply(
        messages.ageOldToolImages(batch = botImageBatch).ageOldUserImages(batch = botImageBatch),
        enabled = true,
        batch = botMaskBatch,
    )

    @Test
    fun `bot policy uses the batches these tests assume`() {
        assertEquals(4, botImageBatch)
        assertEquals(8, botMaskBatch)
        assertEquals(1, BotCostPolicy.imageAgingBatch(Assistant()))
    }

    @Test
    fun `bot user images - three extra kept images are counted`() {
        // 11 turns, keep 4: classic ages 7, a Bot ages 4 -> the Bot sends 3 more images.
        val turns = (1..11).map(::userImage)
        val sent = turns.ageOldUserImages(batch = botImageBatch)
        assertEquals(7, imageCount(sent))
        assertEquals(4, imageCount(turns.ageOldUserImages()))

        val botEstimate = PreSendContextGuard.estimateRequestBytes(turns, botImageBatch, fileSize)
        val classicEstimate = PreSendContextGuard.estimateRequestBytes(turns, fileSize = fileSize)
        assertEquals(PreSendContextGuard.rawRequestBytes(sent, fileSize), botEstimate)
        val elided = ELIDED_USER_IMAGE_TEXT.length
        assertEquals(classicEstimate + 3 * (imageBytes - elided), botEstimate)
    }

    @Test
    fun `bot tool screenshots - three extra kept screenshots are counted`() {
        // 9 screenshots, keep 2: classic ages 7, a Bot ages 4 -> the Bot sends 3 more.
        val shots = (1..9).map(::screenshot)
        val sent = shots.ageOldToolImages(batch = botImageBatch)
        assertEquals(5, imageCount(sent))
        assertEquals(2, imageCount(shots.ageOldToolImages()))

        val botEstimate = PreSendContextGuard.estimateRequestBytes(shots, botImageBatch, fileSize)
        val classicEstimate = PreSendContextGuard.estimateRequestBytes(shots, fileSize = fileSize)
        assertEquals(PreSendContextGuard.rawRequestBytes(sent, fileSize), botEstimate)
        assertTrue(botEstimate - classicEstimate >= 3 * (imageBytes - 200))
    }

    @Test
    fun `bot tool outputs - seven extra unmasked outputs are counted`() {
        // 9 completed reads, keep 2: classic masks 7, a Bot (batch 8) masks none yet.
        val reads = (1..9).map(::read)
        val sent = botRequest(reads)
        assertEquals(reads, sent)
        val classicSent = PrecisionObservationMask.apply(reads, enabled = true)
        assertTrue(ContextBudgetPlanner.estimateContextTokens(classicSent) < ContextBudgetPlanner.estimateContextTokens(sent))

        // The pre-send estimators count tool outputs in full, so they cover all 9.
        assertTrue(PreSendContextGuard.estimateTokens(reads, null) >= ContextBudgetPlanner.estimateContextTokens(sent))
        assertTrue(
            PreSendContextGuard.estimateRequestBytes(reads, botImageBatch, fileSize) >=
                PreSendContextGuard.rawRequestBytes(sent, fileSize),
        )
    }

    @Test
    fun `mixed bot history - estimates are an upper bound of the real request at every length`() {
        val history = buildList {
            for (n in 1..12) {
                add(userImage(n))
                add(screenshot(n))
                add(read(n))
            }
        }
        for (end in 1..history.size) {
            val prefix = history.take(end)
            val sent = botRequest(prefix)
            assertTrue(
                "bytes underestimated at $end",
                PreSendContextGuard.estimateRequestBytes(prefix, botImageBatch, fileSize) >=
                    PreSendContextGuard.rawRequestBytes(sent, fileSize),
            )
            assertTrue(
                "tokens underestimated at $end",
                PreSendContextGuard.estimateTokens(prefix, null) >= ContextBudgetPlanner.estimateContextTokens(sent),
            )
        }
    }

    @Test
    fun `byte limit sees the bot's extra images`() {
        val turns = (1..11).map(::userImage)
        val classicBytes = PreSendContextGuard.estimateRequestBytes(turns, fileSize = fileSize)
        val botBytes = PreSendContextGuard.estimateRequestBytes(turns, botImageBatch, fileSize)
        val limit = (classicBytes + botBytes) / 2

        val classic = PreSendContextGuard.decide(
            messages = turns,
            autoCompactionEnabled = true,
            triggerTokens = Int.MAX_VALUE,
            hardLimitTokens = null,
            fileSize = fileSize,
            maxRequestBytes = limit,
        )
        assertEquals(PreSendContextDecision.Send, classic)
        val botDecision = PreSendContextGuard.decide(
            messages = turns,
            autoCompactionEnabled = true,
            triggerTokens = Int.MAX_VALUE,
            hardLimitTokens = null,
            fileSize = fileSize,
            maxRequestBytes = limit,
            imageAgingBatch = botImageBatch,
        )
        assertEquals(PreSendContextDecision.OverByteLimit(botBytes, limit, compact = true), botDecision)
    }

    @Test
    fun `classic estimate is unchanged - default batch equals the old sliding window`() {
        val history = (1..10).flatMap { listOf(userImage(it), screenshot(it)) }
        val oldBehaviour = PreSendContextGuard.rawRequestBytes(history.ageOldToolImages().ageOldUserImages(), fileSize)
        assertEquals(oldBehaviour, PreSendContextGuard.estimateRequestBytes(history, fileSize = fileSize))
        assertEquals(oldBehaviour, PreSendContextGuard.estimateRequestBytes(history, 1, fileSize))
    }
}
