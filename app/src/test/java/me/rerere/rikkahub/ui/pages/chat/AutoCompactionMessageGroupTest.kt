package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ContextCompactionPresentation
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import me.rerere.rikkahub.data.model.MessageNode
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class AutoCompactionMessageGroupTest {
    @Test
    fun `compaction card and continuation render as one assistant message`() {
        val user = MessageNode(messages = listOf(UIMessage.user("question")))
        val firstAssistant = UIMessage.assistant("I will inspect it.")
        val firstAssistantNode = MessageNode(messages = listOf(firstAssistant))
        val compaction = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(user, firstAssistantNode),
        ).let { conversation ->
            ConversationCompaction(
                conversationId = conversation.id,
                summary = "compressed history",
                tailStartNodeId = null,
                sourceEndNodeId = firstAssistantNode.id,
                summaryModelId = Uuid.random(),
                isAuto = true,
                sourceTokenEstimate = 10_000,
                createdAt = Instant.now(),
            )
        }
        val firstWithCard = firstAssistantNode.copy(
            messages = listOf(
                firstAssistant.copy(
                    parts = firstAssistant.parts + ContextCompactionPresentation.createTool(compaction),
                )
            ),
        )
        val continuation = MessageNode(messages = listOf(UIMessage.assistant("Here is the answer.")))

        val groups = listOf(user, firstWithCard, continuation).groupAutomaticCompactionMessages()

        assertEquals(2, groups.size)
        assertEquals(listOf(user.id), groups.first().nodes.map { it.id })
        assertEquals(listOf(firstWithCard.id, continuation.id), groups.last().nodes.map { it.id })
        assertEquals(
            listOf("I will inspect it.", "Here is the answer."),
            groups.last().displayMessage.parts
                .filterIsInstance<UIMessagePart.Text>()
                .map { it.text },
        )
    }

    @Test
    fun `ordinary adjacent assistant messages remain separate`() {
        val first = MessageNode(messages = listOf(UIMessage.assistant("one")))
        val second = MessageNode(messages = listOf(UIMessage.assistant("two")))

        val groups = listOf(first, second).groupAutomaticCompactionMessages()

        assertEquals(2, groups.size)
    }

    @Test
    fun `repeated automatic compactions remain one visual assistant message`() {
        val firstMessage = UIMessage.assistant("first")
        val first = MessageNode(
            messages = listOf(firstMessage.copy(
                parts = firstMessage.parts + presentationTool(),
            )),
        )
        val secondMessage = UIMessage.assistant("second")
        val second = MessageNode(
            messages = listOf(secondMessage.copy(
                parts = secondMessage.parts + presentationTool(),
            )),
        )
        val final = MessageNode(messages = listOf(UIMessage.assistant("final")))

        val groups = listOf(first, second, final).groupAutomaticCompactionMessages()

        assertEquals(1, groups.size)
        assertEquals(listOf(first.id, second.id, final.id), groups.single().nodes.map { it.id })
    }

    @Test
    fun `interjection sandwich renders as one continuous assistant message`() {
        val user = MessageNode(messages = listOf(UIMessage.user("question")))
        // 前半被插话打断：finishedAt 保持 null（GenerationLoop 只在整轮结束时设置）
        val half = MessageNode(messages = listOf(UIMessage.assistant("first half")))
        val interjection = MessageNode(messages = listOf(UIMessage.user("wait, do this instead")))
        val rest = MessageNode(messages = listOf(UIMessage.assistant("second half")))

        val groups = listOf(user, half, interjection, rest).groupAutomaticCompactionMessages()

        assertEquals(2, groups.size)
        assertEquals(listOf(half.id, interjection.id, rest.id), groups.last().nodes.map { it.id })
        // 插话转成带标记的 Text part，夹在两段正文中间
        val texts = groups.last().displayMessage.parts.filterIsInstance<UIMessagePart.Text>()
        assertEquals(
            listOf("first half", "wait, do this instead", "second half"),
            texts.map { it.text },
        )
        assertEquals(
            true,
            texts[1].metadata?.get("rikkahub.interjection")?.jsonPrimitive?.booleanOrNull,
        )
    }

    @Test
    fun `finalized half does not merge with the following turn`() {
        // 停止/失败的消息会被 finalize（finishedAt 非 null），不并入下一轮
        val finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val half = MessageNode(
            messages = listOf(UIMessage.assistant("stopped half").copy(finishedAt = finishedAt)),
        )
        val nextQuestion = MessageNode(messages = listOf(UIMessage.user("next question")))
        val rest = MessageNode(messages = listOf(UIMessage.assistant("answer")))

        val groups = listOf(half, nextQuestion, rest).groupAutomaticCompactionMessages()

        assertEquals(3, groups.size)
    }

    @Test
    fun `chained interjections stay one visual message`() {
        val half1 = MessageNode(messages = listOf(UIMessage.assistant("a1")))
        val u1 = MessageNode(messages = listOf(UIMessage.user("i1")))
        val half2 = MessageNode(messages = listOf(UIMessage.assistant("a2")))
        val u2 = MessageNode(messages = listOf(UIMessage.user("i2")))
        val rest = MessageNode(messages = listOf(UIMessage.assistant("a3")))

        val groups = listOf(half1, u1, half2, u2, rest).groupAutomaticCompactionMessages()

        assertEquals(1, groups.size)
        assertEquals(5, groups.single().nodes.size)
    }

    private fun presentationTool() = ContextCompactionPresentation.createTool(
        ConversationCompaction(
            conversationId = Uuid.random(),
            summary = "compressed history",
            tailStartNodeId = null,
            sourceEndNodeId = Uuid.random(),
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 10_000,
            createdAt = Instant.now(),
        )
    )
}
