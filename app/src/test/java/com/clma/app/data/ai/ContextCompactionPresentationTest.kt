package com.clma.app.data.ai

import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.model.Conversation
import com.clma.app.data.model.ConversationCompaction
import com.clma.app.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ContextCompactionPresentationTest {
    @Test
    fun `display-only compaction tool is retained in chat but removed from request view`() {
        val assistant = UIMessage.assistant("tool completed")
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = listOf(assistant))),
        )
        val presentationTool = ContextCompactionPresentation.createTool(
            compaction = sampleCompaction(conversation),
        )

        val displayedConversation = ContextCompactionPresentation.attachToMessage(
            conversation = conversation,
            messageId = assistant.id,
            tool = presentationTool,
        )
        val displayedMessage = displayedConversation.currentMessages.single()
        assertTrue(ContextCompactionPresentation.hasDisplayTool(displayedMessage))

        val requestView = ContextCompactionView.build(displayedConversation, compaction = null)
        assertFalse(ContextCompactionPresentation.hasDisplayTool(requestView.messages.single()))
        assertEquals("tool completed", requestView.messages.single().toText())
    }

    @Test
    fun `stream updates preserve an attached compaction tool`() {
        val history = MessageNode(messages = listOf(UIMessage.user("earlier context")))
        val assistant = UIMessage.assistant("tool completed")
        val assistantNode = MessageNode(messages = listOf(assistant))
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(history, assistantNode),
        )
        val compaction = ConversationCompaction(
            conversationId = conversation.id,
            summary = "compressed context",
            tailStartNodeId = assistantNode.id,
            sourceEndNodeId = history.id,
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 12_345,
            createdAt = Instant.now(),
        )
        val withCard = ContextCompactionPresentation.attachToMessage(
            conversation = conversation,
            messageId = assistant.id,
            tool = ContextCompactionPresentation.createTool(compaction),
        )
        val requestView = ContextCompactionView.build(withCard, compaction)
        val streamedUpdate = assistant.copy(parts = listOf(UIMessagePart.Text("tool completed with final state")))

        val merged = ContextCompactionView.mergeGeneratedMessages(
            conversation = withCard,
            view = requestView,
            generatedMessages = listOf(requestView.messages.first(), streamedUpdate),
        )

        assertEquals(
            listOf("tool completed with final state"),
            merged.currentMessages.last().parts
                .filterIsInstance<UIMessagePart.Text>()
                .map { it.text },
        )
        assertTrue(ContextCompactionPresentation.hasDisplayTool(merged.currentMessages.last()))
    }

    @Test
    fun `stream updates keep compaction card before parts emitted afterwards`() {
        val initial = UIMessage(
            role = com.clma.ai.core.MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("search results"),
                UIMessagePart.Tool(
                    toolCallId = "search-before-compaction",
                    toolName = "search_web",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("first result")),
                ),
            ),
        )
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = listOf(initial))),
        )
        val card = ContextCompactionPresentation.createTool(sampleCompaction(conversation))
        val withCard = ContextCompactionPresentation.attachToMessage(
            conversation = conversation,
            messageId = initial.id,
            tool = card,
        )
        val replacement = initial.copy(
            parts = initial.parts + UIMessagePart.Tool(
                toolCallId = "search-after-compaction",
                toolName = "web_fetch",
                input = "{}",
                output = listOf(UIMessagePart.Text("second result")),
            ),
        )

        val restored = ContextCompactionPresentation.preserveDisplayTools(
            previous = withCard.currentMessages.single(),
            replacement = replacement,
        )

        assertEquals(
            listOf(
                "search_web",
                ContextCompactionPresentation.TOOL_NAME,
                "web_fetch",
            ),
            restored.parts.filterIsInstance<UIMessagePart.Tool>().map { it.toolName },
        )
    }

    @Test
    fun `compaction card reports retained raw tool count`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = listOf(UIMessage.user("earlier context")))),
        )
        val compaction = sampleCompaction(conversation).copy(
            summary = "compressed context\n\n" +
                "[Raw context retained verbatim after this summary]\n" +
                "raw_messages=1\ncompleted_tool_calls=55\n" +
                "[End raw context retention report]"
        )

        val tool = ContextCompactionPresentation.createTool(compaction)

        assertTrue(tool.input.contains("\"retained_raw_tool_calls\":55"))
        assertTrue((tool.output.single() as UIMessagePart.Text).text.contains("completed_tool_calls=55"))
    }

    @Test
    fun `success card includes before and after estimates`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = listOf(UIMessage.assistant("done")))),
        )
        val tool = ContextCompactionPresentation.createTool(sampleCompaction(conversation), 1000, 400)
        val args = kotlinx.serialization.json.Json.parseToJsonElement(tool.input).jsonObject
        assertEquals("success", args["status"]?.jsonPrimitive?.content)
        assertEquals(1000, args["tokensBefore"]?.jsonPrimitive?.intOrNull)
        assertEquals(400, args["tokensAfter"]?.jsonPrimitive?.intOrNull)
        assertTrue(ContextCompactionPresentation.isDisplayTool(tool))
    }

    @Test
    fun `failure and cancellation cards are display only and contain no exception text`() {
        for (cancelled in listOf(false, true)) {
            val tool = ContextCompactionPresentation.createFailureTool(false, 1000, cancelled)
            val args = kotlinx.serialization.json.Json.parseToJsonElement(tool.input).jsonObject
            assertEquals(if (cancelled) "cancelled" else "failure", args["status"]?.jsonPrimitive?.content)
            assertEquals("manual", args["mode"]?.jsonPrimitive?.content)
            assertEquals(1000, args["tokensBefore"]?.jsonPrimitive?.intOrNull)
            assertFalse(args.containsKey("tokensAfter"))
            val message = UIMessage.assistant("unchanged").copy(parts = listOf(UIMessagePart.Text("unchanged"), tool))
            val stripped = ContextCompactionPresentation.stripDisplayTools(listOf(message)).single()
            assertEquals("unchanged", stripped.toText())
            assertFalse(ContextCompactionPresentation.hasDisplayTool(stripped))
            assertEquals("Context compaction did not complete.", (tool.output.single() as UIMessagePart.Text).text)
        }
    }

    private fun sampleCompaction(conversation: Conversation) = ConversationCompaction(
        conversationId = conversation.id,
        summary = "compressed context",
        tailStartNodeId = null,
        sourceEndNodeId = conversation.messageNodes.single().id,
        summaryModelId = Uuid.random(),
        isAuto = true,
        sourceTokenEstimate = 12_345,
        createdAt = Instant.now(),
    )
}
