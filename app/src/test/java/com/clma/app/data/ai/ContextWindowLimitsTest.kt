package com.clma.app.data.ai

import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.limitContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩摘要(开头的合成消息) 与 消息条数上限 的交互。
 *
 * 这两处逻辑各自都有测试, 但彼此看不见对方, 所以 `[summary] + rawTail` 被按位置裁剪、
 * 摘要整段丢掉这件事一直没有被发现。
 */
class ContextWindowLimitsTest {

    private fun conversation(count: Int): List<UIMessage> = (0 until count).map { index ->
        if (index % 2 == 0) UIMessage.user("用户消息 $index") else UIMessage.assistant("助手回复 $index")
    }

    private fun summaryMessage(text: String = "[之前对话的摘要]") =
        UIMessage.user(text).copy(isSynthetic = true)

    @Test
    fun `limit disabled returns the list untouched`() {
        val messages = conversation(20)
        assertEquals(messages, messages.limitContextPreservingLeadingSynthetic(0))
        assertEquals(messages, messages.limitContextPreservingLeadingSynthetic(-1))
    }

    @Test
    fun `list within the limit returns the list untouched`() {
        val messages = conversation(5)
        assertEquals(messages, messages.limitContextPreservingLeadingSynthetic(5))
        assertEquals(messages, messages.limitContextPreservingLeadingSynthetic(10))
    }

    /** 没有前置合成消息时, 必须与原来的 limitContext 逐条一致, 不引入任何新行为。 */
    @Test
    fun `no synthetic prefix behaves exactly like limitContext`() {
        val messages = conversation(40)
        for (limit in 1..15) {
            assertEquals(
                "limit=$limit",
                messages.limitContext(limit),
                messages.limitContextPreservingLeadingSynthetic(limit),
            )
        }
    }

    @Test
    fun `leading summary survives the limit and the tail is still capped`() {
        val summary = summaryMessage()
        val messages = listOf(summary) + conversation(30)

        val result = messages.limitContextPreservingLeadingSynthetic(8)

        assertEquals(summary, result.first())
        assertTrue("result size ${result.size}", result.size <= 8)
        // 尾部保留的是原列表的后缀, 没有跳过或重排
        assertEquals(messages.takeLast(result.size - 1), result.drop(1))
    }

    @Test
    fun `all synthetic list is left alone`() {
        val messages = (0 until 30).map { summaryMessage("摘要 $it") }
        assertEquals(messages, messages.limitContextPreservingLeadingSynthetic(5))
    }

    /** limit 比前置合成消息还小时, 至少保留一条尾部消息, 否则请求没有可回答的内容。 */
    @Test
    fun `limit of one still leaves one tail message`() {
        val summary = summaryMessage()
        val messages = listOf(summary) + conversation(10)

        val result = messages.limitContextPreservingLeadingSynthetic(1)

        assertEquals(2, result.size)
        assertEquals(summary, result.first())
        assertEquals(messages.last(), result.last())
    }
}