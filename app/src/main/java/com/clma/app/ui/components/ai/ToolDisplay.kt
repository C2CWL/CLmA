package com.clma.app.ui.components.ai

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.ai.tools.ToolDisplayNames

/*
 * Display rule for tool names (see ToolDisplayNames): human name as the headline, a one-line
 * description, and the raw id in small grey monospace underneath. MCP tools show a server tag,
 * the server's own tool name, and the server's description marked "(provided by the server)".
 * Unknown ids fall back to the raw id with no description.
 */

/** Human name of [toolName]; for `mcp__*` the server's own tool name; else the raw id. */
@Composable
fun toolDisplayName(toolName: String): String {
    ToolDisplayNames.resFor(toolName)?.let { return stringResource(it.name) }
    ToolDisplayNames.mcpParts(toolName)?.let { return it.toolName }
    return toolName
}

/** One-line description, or null when the tool has none (unknown / MCP). */
@Composable
fun toolDisplayDescription(toolName: String): String? =
    ToolDisplayNames.resFor(toolName)?.let { stringResource(it.description) }

/** "名称（id）" for dialogs; just the id when there is no display name. MCP: "server · tool（id）". */
@Composable
fun toolNameWithId(toolName: String): String {
    val res = ToolDisplayNames.resFor(toolName)
    val mcp = ToolDisplayNames.mcpParts(toolName)
    val name = when {
        res != null -> stringResource(res.name)
        mcp != null -> "${mcp.serverName} · ${mcp.toolName}"
        else -> return toolName
    }
    return stringResource(R.string.tool_display_name_with_id, name, toolName)
}

/** Non-Compose variant for services (Telegram): display name, or null when unknown. */
fun Context.toolDisplayNameOrNull(toolName: String): String? =
    ToolDisplayNames.resFor(toolName)?.let { getString(it.name) }

/** Non-Compose variant for services (Telegram): description, or null when unknown. */
fun Context.toolDisplayDescriptionOrNull(toolName: String): String? =
    ToolDisplayNames.resFor(toolName)?.let { getString(it.description) }

/**
 * For search: id → "name description" for every known tool, resolved once per locale so the
 * settings list can match Chinese text as well as the raw id.
 */
@Composable
fun rememberToolDisplaySearchText(): Map<String, String> {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        ToolDisplayNames.knownIds.associateWith { id ->
            "${context.toolDisplayNameOrNull(id).orEmpty()} ${context.toolDisplayDescriptionOrNull(id).orEmpty()}"
        }
    }
}

/** Small grey monospace raw tool id. */
@Composable
fun ToolRawId(toolName: String, modifier: Modifier = Modifier) {
    Text(
        text = toolName,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.outline,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Small tonal tag with an MCP server's name. */
@Composable
fun McpServerTag(serverName: String) {
    Text(
        text = "MCP · $serverName",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * Headline: [McpServerTag] (MCP only), the display name, then an optional [tag] (e.g. the red
 * high-risk chip). [mcpServerName] overrides the server name parsed from the id.
 */
@Composable
fun ToolDisplayHeadline(
    toolName: String,
    modifier: Modifier = Modifier,
    mcpServerName: String? = null,
    tag: (@Composable () -> Unit)? = null,
) {
    val server = mcpServerName ?: ToolDisplayNames.mcpParts(toolName)?.serverName
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (server != null && toolName.startsWith("mcp__")) McpServerTag(server)
        Text(
            text = toolDisplayName(toolName),
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        tag?.invoke()
    }
}

/**
 * Description line + raw id. For MCP tools pass the server's [mcpDescription]; it is shown
 * with a "(provided by the server)" marker because it is not ours and not translated.
 */
@Composable
fun ToolDisplaySupporting(
    toolName: String,
    modifier: Modifier = Modifier,
    mcpDescription: String? = null,
) {
    val description = toolDisplayDescription(toolName)
        ?: mcpDescription?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val firstLine = it.lineSequence().first().let { line -> if (line.length > 120) line.take(120) + "…" else line }
            firstLine + stringResource(R.string.tool_display_mcp_server_provided)
        }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ToolRawId(toolName)
    }
}
