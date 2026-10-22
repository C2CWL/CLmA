package com.clma.app.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 消息导航「上一条 / 下一条」按真实消息跳转的判定。纯逻辑。 */
class ChatMessageJumperTest {

    private val threshold = 60

    @Test
    fun `previous goes to the start of the message being read`() {
        // 第 3 条已经往下读了一段 ⇒ 先回到第 3 条开头，而不是跳过它
        assertEquals(3, previousMessageTarget(firstIndex = 3, firstOffsetPx = 400, messageCount = 6, thresholdPx = threshold))
    }

    @Test
    fun `previous at a message start goes to the message before`() {
        assertEquals(2, previousMessageTarget(firstIndex = 3, firstOffsetPx = 10, messageCount = 6, thresholdPx = threshold))
    }

    @Test
    fun `previous never goes before the first message`() {
        assertEquals(0, previousMessageTarget(firstIndex = 0, firstOffsetPx = 0, messageCount = 6, thresholdPx = threshold))
    }

    @Test
    fun `previous from the trailing non-message items lands on the last message`() {
        // 列表末尾的加载行 / 底部占位不是消息
        assertEquals(5, previousMessageTarget(firstIndex = 7, firstOffsetPx = 0, messageCount = 6, thresholdPx = threshold))
    }

    @Test
    fun `previous with no messages does nothing`() {
        assertNull(previousMessageTarget(firstIndex = 0, firstOffsetPx = 0, messageCount = 0, thresholdPx = threshold))
    }

    @Test
    fun `next goes to the start of the following message`() {
        assertEquals(4, nextMessageTarget(firstIndex = 3, messageCount = 6))
    }

    @Test
    fun `next from the last message means go to the bottom`() {
        assertNull(nextMessageTarget(firstIndex = 5, messageCount = 6))
        assertNull(nextMessageTarget(firstIndex = 7, messageCount = 6))
    }
}
