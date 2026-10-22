package com.clma.app.data.ai.tools

/**
 * Pure model behind the in-chat "本对话授权" sheet: which tools the user can pre-allow for one
 * conversation, grouped the way the sheet shows them. Kept free of Android / Compose so the
 * grouping and the "allow all" selection are unit-testable on a bare JVM.
 *
 * Three groups, in this order, each tool appearing at most once:
 *  - [Kind.FLOOR] — default-ask privilege-escalation tools ([ToolApprovalDefaults.NO_ALWAYS_ALLOW])
 *    that are on the assistant's tool surface;
 *  - [Kind.MCP] — relayed `mcp__*` tools of the assistant's enabled MCP servers;
 *  - [Kind.BLACKLIST] — other surface tools the user put on the blacklist.
 *
 * Enabling a FLOOR or MCP row needs a light confirmation in the UI ([needsConfirm]); a blacklisted
 * row does not, and revoking never does.
 */
object ChatToolPresets {

    enum class Kind { FLOOR, MCP, BLACKLIST }

    /** One sheet row. [serverName] is set only for [Kind.MCP]. */
    data class Row(
        val toolName: String,
        val kind: Kind,
        val serverName: String? = null,
    )

    /** One relayed MCP tool: the server's display name and the full `mcp__…` tool name. */
    data class McpEntry(val serverName: String, val toolName: String)

    /** Human-input tools never ask for a capability grant, so they never get a row. */
    private val NEVER_LISTED = setOf(
        ToolApprovalPolicy.ASK_USER_TOOL_NAME,
        ToolApprovalPolicy.REQUEST_SECRET_TOOL_NAME,
    )

    fun rows(
        surfaceToolNames: List<String>,
        mcpTools: List<McpEntry>,
        blacklist: Set<String>,
        hardFloor: Set<String> = ToolApprovalDefaults.NO_ALWAYS_ALLOW,
    ): List<Row> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Row>()
        val local = surfaceToolNames.filter { it.isNotBlank() && it !in NEVER_LISTED }
        local.filter { it in hardFloor }.forEach { if (seen.add(it)) out += Row(it, Kind.FLOOR) }
        mcpTools.filter { it.toolName.startsWith(ToolApprovalDefaults.MCP_TOOL_PREFIX) }
            .forEach { if (seen.add(it.toolName)) out += Row(it.toolName, Kind.MCP, it.serverName) }
        local.filter { it in blacklist }.forEach { if (seen.add(it)) out += Row(it, Kind.BLACKLIST) }
        return out
    }

    /** True when enabling [row] should show the light risk confirmation first. */
    fun needsConfirm(row: Row): Boolean = row.kind != Kind.BLACKLIST

    /** The rows "allow all" would newly grant: everything listed that is not granted yet. */
    fun pendingForAllowAll(rows: List<Row>, granted: Collection<String>): List<Row> {
        val g = granted.toSet()
        return rows.filter { it.toolName !in g }
    }
}
