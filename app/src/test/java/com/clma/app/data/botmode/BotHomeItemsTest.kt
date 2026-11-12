package com.clma.app.data.botmode

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.ToolApprovalState
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.model.Conversation
import com.clma.app.data.model.MessageNode
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class BotHomeItemsTest {

    private val assistantId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `preview is one short line from the last text message`() {
        val conv = conversation(
            UIMessage.user("hi"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("**磁盘清理**已完成，\n\n释放 12GB"))),
        )
        assertEquals("磁盘清理已完成， 释放 12GB", botPreviewLine(conv))
    }

    @Test
    fun `preview truncates and falls back to the title`() {
        val long = conversation(UIMessage.user("x".repeat(100)))
        assertEquals(61, botPreviewLine(long, maxLength = 60).length)
        assertTrue(botPreviewLine(long, maxLength = 60).endsWith("…"))

        val toolOnly = conversation(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool(ToolApprovalState.Auto))),
            title = "修仓库",
        )
        assertEquals("修仓库", botPreviewLine(toolOnly))
        assertEquals("", botPreviewLine(null))
    }

    @Test
    fun `pending approval only counts the latest turn`() {
        val pending = conversation(
            UIMessage.user("merge it"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool(ToolApprovalState.Pending))),
        )
        assertTrue(hasPendingApproval(pending))

        val answeredLater = conversation(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool(ToolApprovalState.Pending))),
            UIMessage.user("never mind"),
        )
        assertFalse(hasPendingApproval(answeredLater))
        assertFalse(hasPendingApproval(null))
    }

    @Test
    fun `latest conversation ignores pinned order and sub-agent chats`() {
        val old = conversation(title = "old", updateAt = Instant.ofEpochSecond(100)).copy(isPinned = true)
        val new = conversation(title = "new", updateAt = Instant.ofEpochSecond(200))
        val sub = conversation(title = "sub", updateAt = Instant.ofEpochSecond(300))
            .copy(parentConversationId = Uuid.random())
        assertEquals("new", latestTopLevelConversation(listOf(old, new, sub))?.title)
        assertNull(latestTopLevelConversation(emptyList()))
    }

    @Test
    fun `visible topics keep peer threads and drop generic sub-agent runs`() {
        val top = conversation(title = "top")
        val peer = conversation(title = "[Peer] 小墨").copy(parentConversationId = Uuid.random())
        val sub = conversation(title = "lookup weather").copy(parentConversationId = Uuid.random())
        assertTrue(isBotVisibleTopic(top))
        assertTrue(isBotVisibleTopic(peer))
        assertFalse(isBotVisibleTopic(sub))
    }

    @Test
    fun `open prefers unread conversation then latest then a fresh id`() {
        val unread = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val latest = Uuid.parse("22222222-2222-2222-2222-222222222222")
        val fresh = Uuid.parse("33333333-3333-3333-3333-333333333333")
        assertEquals(unread, resolveBotOpenConversationId(unread, latest) { fresh })
        assertEquals(latest, resolveBotOpenConversationId(null, latest) { fresh })
        assertEquals(fresh, resolveBotOpenConversationId(null, null) { fresh })
    }

    @Test
    fun `pinned rows first in pin order, then newest`() {
        val a = row(Uuid.random(), 100)
        val b = row(Uuid.random(), 300)
        val c = row(Uuid.random(), 200)
        val d = row(Uuid.random(), null)
        val sorted = sortBotHomeRows(listOf(a, b, c, d), pinnedOrder = listOf(d.assistantId, a.assistantId))
        assertEquals(listOf(d, a, b, c), sorted)
    }

    @Test
    fun `time label like a chat app`() {
        val now = Instant.parse("2026-11-09T12:00:00Z") // 20:00 in Shanghai, a Monday
        assertEquals(
            BotTimeLabel(BotTimeLabelKind.TIME, "15:05"),
            botTimeLabel(Instant.parse("2026-11-09T07:05:00Z"), now, zone),
        )
        assertEquals(BotTimeLabelKind.YESTERDAY, botTimeLabel(Instant.parse("2026-11-08T07:00:00Z"), now, zone).kind)
        assertEquals(
            BotTimeLabel(BotTimeLabelKind.WEEKDAY, "SATURDAY"),
            botTimeLabel(Instant.parse("2026-11-07T07:00:00Z"), now, zone),
        )
        assertEquals(BotTimeLabel(BotTimeLabelKind.DATE, "10/1"), botTimeLabel(Instant.parse("2026-10-01T07:00:00Z"), now, zone))
        assertEquals(
            BotTimeLabel(BotTimeLabelKind.DATE, "2025/10/1"),
            botTimeLabel(Instant.parse("2025-10-01T07:00:00Z"), now, zone),
        )
        assertEquals(BotTimeLabelKind.NONE, botTimeLabel(null, now, zone).kind)
    }

    private fun row(id: Uuid, updatedSec: Long?) = BotHomeRow(
        assistantId = id,
        latestConversationId = null,
        preview = "",
        updatedAt = updatedSec?.let { Instant.ofEpochSecond(it) },
        unread = 0,
        pinned = false,
        pendingApproval = false,
        generating = false,
    )

    private fun conversation(
        vararg messages: UIMessage,
        title: String = "",
        updateAt: Instant = Instant.ofEpochSecond(0),
    ) = Conversation(
        id = Uuid.random(),
        assistantId = assistantId,
        title = title,
        messageNodes = messages.map { MessageNode.of(it) },
        updateAt = updateAt,
    )

    private fun tool(approval: ToolApprovalState) = UIMessagePart.Tool(
        toolCallId = "t",
        toolName = "merge",
        input = "{}",
        output = emptyList(),
        approvalState = approval,
    )
}
