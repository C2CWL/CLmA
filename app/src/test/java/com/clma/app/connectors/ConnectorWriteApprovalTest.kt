package com.clma.app.connectors

import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.tools.ToolExecutionRetryPolicy
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorWriteApprovalTest {
    private val conversation = UUID.randomUUID().toString()
    private val call = UUID.randomUUID().toString()
    private val args = buildJsonObject { put("body", "Reviewed text"); put("account", "alice") }
    private val changed = buildJsonObject { put("body", "Unreviewed text"); put("account", "alice") }

    private fun guardedTool(name: String) = Tool(
        name = name,
        description = "Consumes the real execution-context grant",
        execute = {
            check(ConnectorWriteApproval.isGranted(name, it)) { "executed without a grant" }
            listOf(UIMessagePart.Text("written"))
        },
    )

    private fun assertDenied(output: List<UIMessagePart>) {
        assertTrue((output.single() as UIMessagePart.Text).text.contains("connector_explicit_approval_required"))
    }

    @Test
    fun `persisted approved state is not a capability`() = runBlocking {
        ConnectorWriteApproval.WRITE_TOOL_NAMES.forEach { name ->
            assertDenied(ConnectorWriteApproval.execute(guardedTool(name), args, conversation, call, true, false))
            assertFalse(ConnectorWriteApproval.isGranted(name, args))
        }
    }

    @Test
    fun `a reviewed call executes once and cannot replay`() = runBlocking {
        ConnectorWriteApproval.WRITE_TOOL_NAMES.forEach { name ->
            val tool = guardedTool(name)
            ConnectorWriteApproval.approveOnce(conversation, call, name, args)
            val output = ConnectorWriteApproval.execute(tool, args, conversation, call, true, false)
            assertEquals("written", (output.single() as UIMessagePart.Text).text)
            assertFalse(ConnectorWriteApproval.isGranted(name, args))
            assertDenied(ConnectorWriteApproval.execute(tool, args, conversation, call, true, false))
        }
    }

    @Test
    fun `changing arguments or tool consumes but never broadens consent`() = runBlocking {
        val tool = guardedTool("github_issue_create")
        ConnectorWriteApproval.approveOnce(conversation, call, tool.name, args)
        assertDenied(ConnectorWriteApproval.execute(tool, changed, conversation, call, true, false))
        assertDenied(ConnectorWriteApproval.execute(tool, args, conversation, call, true, false))
        ConnectorWriteApproval.approveOnce(conversation, call, tool.name, args)
        assertDenied(ConnectorWriteApproval.execute(guardedTool("github_comment_create"), args, conversation, call, true, false))
        assertDenied(ConnectorWriteApproval.execute(tool, args, conversation, call, true, false))
    }

    @Test
    fun `consent is bound to its conversation and call id`() = runBlocking {
        val tool = guardedTool("github_issue_create")
        ConnectorWriteApproval.approveOnce(conversation, call, tool.name, args)
        assertDenied(ConnectorWriteApproval.execute(tool, args, "other-$conversation", call, true, false))
        assertDenied(ConnectorWriteApproval.execute(tool, args, conversation, "other-$call", true, false))
        assertDenied(ConnectorWriteApproval.execute(tool, args, null, call, true, false))
        assertEquals("written", (ConnectorWriteApproval.execute(tool, args, conversation, call, true, false).single() as UIMessagePart.Text).text)
    }

    @Test
    fun `auto and headless execution cannot spend interactive consent`() = runBlocking {
        val tool = guardedTool("github_issue_create")
        listOf(false to false, true to true).forEach { (approved, headless) ->
            ConnectorWriteApproval.approveOnce(conversation, call, tool.name, args)
            assertDenied(ConnectorWriteApproval.execute(tool, args, conversation, call, approved, headless))
            assertDenied(ConnectorWriteApproval.execute(tool, args, conversation, call, true, false))
        }
    }

    @Test
    fun `a grant matches exact arguments and is single use inside the execution context`() = runBlocking {
        val name = "github_issue_create"
        val tool = Tool(name = name, description = "Grant boundary", execute = {
            assertFalse(ConnectorWriteApproval.isGranted("github_comment_create", it))
            assertFalse(ConnectorWriteApproval.isGranted(name, changed))
            assertTrue(ConnectorWriteApproval.isGranted(name, it))
            assertFalse(ConnectorWriteApproval.isGranted(name, it))
            emptyList()
        })
        ConnectorWriteApproval.approveOnce(conversation, call, name, args)
        ConnectorWriteApproval.execute(tool, args, conversation, call, true, false)
        Unit
    }

    @Test
    fun `concurrent children cannot consume the same grant twice`() = runBlocking {
        val name = "github_issue_create"
        val tool = Tool(name = name, description = "Concurrent grant boundary", execute = { input ->
            val winners = coroutineScope {
                (1..16).map { async(Dispatchers.Default) { ConnectorWriteApproval.isGranted(name, input) } }.awaitAll()
            }
            assertEquals(1, winners.count { it })
            emptyList()
        })
        ConnectorWriteApproval.approveOnce(conversation, call, name, args)
        ConnectorWriteApproval.execute(tool, args, conversation, call, true, false)
        Unit
    }

    @Test
    fun `ordinary tools still execute without consent and writes are never retryable`() = runBlocking {
        val tool = Tool(name = "github_repository", description = "Read", execute = { listOf(UIMessagePart.Text("read")) })
        assertEquals("read", (ConnectorWriteApproval.execute(tool, args, null, call, false, true).single() as UIMessagePart.Text).text)
        ConnectorWriteApproval.WRITE_TOOL_NAMES.forEach {
            assertFalse(ToolExecutionRetryPolicy.isIdempotentReadOnly(it, args))
        }
    }
}
