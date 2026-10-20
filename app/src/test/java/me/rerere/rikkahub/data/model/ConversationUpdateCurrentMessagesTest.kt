package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 流式热路径回归：StreamChunkHandler 只 copy 最后一条消息，前面的 UIMessage 引用不变。
 * updateCurrentMessages 必须复用这些未变的 node，否则每个 token 都会重建整条历史，
 * 让所有可见 item 的参数都变、整页跟着重组（长回复 / 多会话并发出字时掉帧）。
 */
class ConversationUpdateCurrentMessagesTest {

    private fun conversation(vararg messages: UIMessage): Conversation =
        Conversation(
            assistantId = Uuid.random(),
            messageNodes = messages.map { it.toMessageNode() },
        )

    @Test
    fun `identical messages return the same conversation instance`() {
        val user = UIMessage.user("hi")
        val assistant = UIMessage.assistant("hello")
        val conversation = conversation(user, assistant)

        val updated = conversation.updateCurrentMessages(listOf(user, assistant))

        // 没有任何节点变化时必须返回同一个实例：StateFlow 用引用相等短路，不触发重组。
        assertSame(conversation, updated)
    }

    @Test
    fun `unchanged history nodes keep their identity when only the tail streams`() {
        val user = UIMessage.user("hi")
        val assistant = UIMessage.assistant("hello")
        val conversation = conversation(user, assistant)

        val streamed = assistant.copy(parts = listOf(UIMessagePart.Text("hello world")))
        val updated = conversation.updateCurrentMessages(listOf(user, streamed))

        // 历史节点按引用复用，是 LazyColumn 跳过前面 item 重组的前提。
        assertSame(conversation.messageNodes[0], updated.messageNodes[0])
        assertSame(streamed, updated.messageNodes[1].currentMessage)
    }

    @Test
    fun `a new message keeps every existing node by reference`() {
        val user = UIMessage.user("hi")
        val assistant = UIMessage.assistant("hello")
        val conversation = conversation(user, assistant)

        val next = UIMessage.assistant("again")
        val updated = conversation.updateCurrentMessages(listOf(user, assistant, next))

        assertEquals(3, updated.messageNodes.size)
        assertSame(conversation.messageNodes[0], updated.messageNodes[0])
        assertSame(conversation.messageNodes[1], updated.messageNodes[1])
        assertSame(next, updated.messageNodes[2].currentMessage)
    }
}