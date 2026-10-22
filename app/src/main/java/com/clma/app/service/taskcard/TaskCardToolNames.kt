package com.clma.app.service.taskcard

import android.content.Context
import com.clma.app.data.ai.tools.ToolDisplayNames
import com.clma.app.ui.components.ai.toolDisplayNameOrNull

internal object TaskCardToolNames {
    /** Localized tool name (e.g. 网页搜索); the MCP server's own tool name; else a cleaned raw id. */
    fun displayName(context: Context, raw: String): String =
        context.toolDisplayNameOrNull(raw)
            ?: ToolDisplayNames.mcpParts(raw)?.toolName
            ?: TaskCardStatusResolver.displayToolName(raw)
}
