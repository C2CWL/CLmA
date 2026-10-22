package com.clma.app.data.ai.tools

import com.clma.app.data.ai.tools.ChatToolPresets.Kind
import com.clma.app.data.ai.tools.ChatToolPresets.McpEntry
import com.clma.app.data.ai.tools.ChatToolPresets.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolPresetsTest {

    private val mcp = listOf(
        McpEntry("github", "mcp__aaaa1111_github__create_issue"),
        McpEntry("github", "mcp__aaaa1111_github__search"),
        McpEntry("fs", "mcp__bbbb2222_fs__read"),
    )

    @Test
    fun groupsFloorThenMcpThenBlacklist() {
        val rows = ChatToolPresets.rows(
            surfaceToolNames = listOf("get_time", "eval_javascript", "clipboard_write", "nfc_write_tag", "shell_exec"),
            mcpTools = mcp,
            blacklist = setOf("shell_exec", "clipboard_write", "not_on_surface"),
        )
        assertEquals(
            listOf(
                Row("eval_javascript", Kind.FLOOR),
                Row("nfc_write_tag", Kind.FLOOR),
                Row("mcp__aaaa1111_github__create_issue", Kind.MCP, "github"),
                Row("mcp__aaaa1111_github__search", Kind.MCP, "github"),
                Row("mcp__bbbb2222_fs__read", Kind.MCP, "fs"),
                Row("clipboard_write", Kind.BLACKLIST),
                Row("shell_exec", Kind.BLACKLIST),
            ),
            rows,
        )
    }

    @Test
    fun floorToolOnBlacklistIsListedOnceAsFloor() {
        val rows = ChatToolPresets.rows(listOf("mcp_add"), emptyList(), blacklist = setOf("mcp_add"))
        assertEquals(listOf(Row("mcp_add", Kind.FLOOR)), rows)
    }

    @Test
    fun floorToolsOffSurfaceAndHumanInputToolsAreNotListed() {
        val rows = ChatToolPresets.rows(
            surfaceToolNames = listOf("ask_user", "request_secret", "", "get_time"),
            mcpTools = listOf(McpEntry("x", "not_mcp_prefixed")),
            blacklist = setOf("ask_user", "request_secret", "keystore_decrypt"),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun duplicateNamesCollapse() {
        val rows = ChatToolPresets.rows(
            listOf("eval_javascript", "eval_javascript"),
            listOf(mcp[0], mcp[0]),
            emptySet(),
        )
        assertEquals(2, rows.size)
    }

    @Test
    fun onlyFloorAndMcpNeedConfirm() {
        assertTrue(ChatToolPresets.needsConfirm(Row("eval_javascript", Kind.FLOOR)))
        assertTrue(ChatToolPresets.needsConfirm(Row("mcp__a_b__c", Kind.MCP, "b")))
        assertFalse(ChatToolPresets.needsConfirm(Row("shell_exec", Kind.BLACKLIST)))
    }

    @Test
    fun allowAllSelectsOnlyUngrantedRows() {
        val rows = ChatToolPresets.rows(listOf("eval_javascript", "shell_exec"), mcp.take(1), setOf("shell_exec"))
        val pending = ChatToolPresets.pendingForAllowAll(rows, granted = listOf("eval_javascript"))
        assertEquals(
            listOf(
                Row("mcp__aaaa1111_github__create_issue", Kind.MCP, "github"),
                Row("shell_exec", Kind.BLACKLIST),
            ),
            pending,
        )
        assertTrue(ChatToolPresets.pendingForAllowAll(rows, rows.map { it.toolName }).isEmpty())
    }
}
