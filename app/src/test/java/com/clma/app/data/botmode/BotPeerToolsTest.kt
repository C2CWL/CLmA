package com.clma.app.data.botmode

import com.clma.ai.core.MessageRole
import com.clma.ai.ui.ToolApprovalState
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.utils.JsonInstant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class BotPeerToolsTest {
    @Test fun `blank deferred reply falls back to last non-blank assistant text`() {
        val toolOnly = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "t",
                    toolName = "search_web",
                    input = "{}",
                    output = emptyList(),
                    approvalState = ToolApprovalState.Auto,
                ),
            ),
        )
        val withText = UIMessage.assistant("stored peer answer")
        assertEquals("stored peer answer", peerTurnAnswerText("", listOf(withText, toolOnly)))
        assertEquals("live", peerTurnAnswerText("live", listOf(withText)))
        assertEquals("", peerTurnAnswerText(null, listOf(toolOnly)))
        assertEquals("", peerTurnAnswerText("   ", emptyList()))
    }

    @Test fun `peer policy is deny by default including MCP shell and generic recursion`() {
        listOf("get_time_info", "search_web", "scrape_web", "web_extract").forEach {
            assertTrue(BotPeerToolPolicy.allows(it))
        }
        BotPeerTools.NAMES.forEach { assertTrue(BotPeerToolPolicy.allows(it)) }
        listOf("mcp__GitHub__create_issue", "ssh_exec_saved", "termux_run_command",
            "subagent_dispatch", "mission_dispatch", "tool_open", "workspace_shell", "web_fetch",
            "memory_write", "request_secret", "ask_user", "unknown_future_tool").forEach {
            assertFalse(it, BotPeerToolPolicy.allows(it))
        }
        val id = Uuid.random()
        BotPeerToolPolicy.mark(id)
        try { assertTrue(BotPeerToolPolicy.isPeer(id)) }
        finally { BotPeerToolPolicy.clear(id) }
        assertFalse(BotPeerToolPolicy.isPeer(id))
    }

    @Test fun `real tool dispatch rechecks authority and returns correlated task and thread`() = runTest {
        val store = object : PeerStore {
            var state = PeerSnapshot()
            override fun load() = state
            override fun save(snapshot: PeerSnapshot) { state = snapshot }
        }
        val host = object : PeerHost {
            override suspend fun isBot(id: String) = id in setOf("source", "target")
            override suspend fun createConversation(botId: String, requester: String) = "child"
            override suspend fun turn(thread: PeerThread, task: PeerTask) = "peer answer"
            override suspend fun stop(conversationId: String) = Unit
        }
        val runtime = BotPeerRuntime(this, store, host)
        var eligible = true
        val tools = BotPeerTools.build(runtime, "owner", "source", { eligible }, false, { "turn" },
            { buildJsonObject { put("bot", "target") } }).associateBy { it.name }
        val args = buildJsonObject { put("bot_id", "target"); put("brief", "question") }
        val output = (tools.getValue("delegate_bot").execute(args).single() as UIMessagePart.Text).text
        val receipt = JsonInstant.parseToJsonElement(output).jsonObject
        assertEquals("queued", receipt.getValue("outcome").jsonPrimitive.content)
        val id = receipt.getValue("task_id").jsonPrimitive.content
        val get = buildJsonObject { put("task_id", id); put("wait", true) }
        val result = (tools.getValue("peer_task_get").execute(get).single() as UIMessagePart.Text).text
        val task = JsonInstant.decodeFromString(PeerTask.serializer(), result)
        assertEquals(id, task.id)
        assertEquals(receipt.getValue("thread_id").jsonPrimitive.content, task.threadId)
        assertEquals(PeerStatus.COMPLETED, task.status)
        assertEquals("peer answer", task.result)
        eligible = false
        val denied = (tools.getValue("ask_bot").execute(args).single() as UIMessagePart.Text).text
        assertEquals("peer_caller_not_authorized",
            JsonInstant.parseToJsonElement(denied).jsonObject.getValue("error").jsonPrimitive.content)
        assertEquals(1, store.state.tasks.size)
    }
}
