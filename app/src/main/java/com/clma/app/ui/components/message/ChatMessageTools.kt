package com.clma.app.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.clma.ai.ui.ToolApprovalState
import com.clma.ai.ui.UIMessagePart
import com.clma.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Lock
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tools
import com.clma.app.ui.components.ai.McpServerTag
import com.clma.app.ui.components.ai.ToolDisplaySupporting
import com.clma.app.R
import com.clma.app.data.ai.tools.ToolApprovalPolicy
import com.clma.app.data.datastore.ToolCallStyle
import com.clma.app.data.secrets.AgentSecretsStore
import com.clma.app.ui.components.message.tools.ToolUIContext
import com.clma.app.ui.components.message.tools.ToolUIRegistry
import com.clma.app.ui.components.message.tools.ToolResultImage
import com.clma.app.ui.components.ui.ChainOfThoughtScope
import com.clma.app.ui.components.ui.DotLoading
import com.clma.app.ui.components.ui.LocalToolCallStyle
import com.clma.app.ui.context.LocalToaster
import com.clma.app.ui.modifier.shimmer
import com.clma.app.utils.JsonInstant
import com.clma.app.utils.jsonPrimitiveOrNull
import org.koin.compose.koinInject

// Per-tool icon/title/summary/preview rendering now lives in ToolUIRegistry
// (see message/tools/BuiltinToolUIs.kt). This file only keeps the cross-cutting
// approval-card UI and the interactive ask_user / request_secret flows, plus the
// small JSON helper they rely on.
private fun JsonElement?.getStringContent(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

private const val INTERACTIVE_CANCEL_REASON = "User cancelled"

@Composable
fun InteractiveToolCard(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String, scope: com.clma.app.service.ChatService.ApprovalScope, toolName: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
) {
    when (tool.toolName) {
        ToolApprovalPolicy.ASK_USER_TOOL_NAME -> AskUserToolCard(
            tool = tool,
            loading = loading,
            onToolApproval = onToolApproval,
            onToolAnswer = onToolAnswer,
        )
        ToolApprovalPolicy.REQUEST_SECRET_TOOL_NAME -> RequestSecretToolCard(
            tool = tool,
            loading = loading,
            onToolApproval = onToolApproval,
            onToolAnswer = onToolAnswer,
        )
    }
}


@Composable
fun ChainOfThoughtScope.ChatMessageServerToolStep(tool: UIMessagePart.ServerTool) {
    val loading = !tool.isFinished
    val capsule = LocalToolCallStyle.current == ToolCallStyle.CAPSULE
    ChainOfThoughtStep(
        icon = {
            if (loading) {
                DotLoading(size = if (capsule) 12.dp else 10.dp)
            } else {
                Icon(
                    imageVector = if (capsule) {
                        capsuleToolIcon(tool.toolName.ifBlank { "server_tool" })
                    } else {
                        HugeIcons.Tools
                    },
                    contentDescription = null,
                    modifier = Modifier.size(if (capsule) CapsuleToolIconSize else 16.dp),
                    tint = if (capsule) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        LocalContentColor.current.copy(alpha = 0.7f)
                    },
                )
            }
        },
        label = {
            Text(
                text = stringResource(R.string.chat_message_tool_call_generic, tool.toolName),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (capsule) FontWeight.Medium else null,
                color = if (capsule) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = if (capsule) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/**
 * Parses a tool's output text as JSON for [ToolUIContext.content]. Returns null both
 * when the tool hasn't executed yet and when the output isn't valid JSON (e.g. a
 * truncation notice - see `maybeTruncateToolOutput`), rather than collapsing a parse
 * failure into an empty [JsonObject]: that used to make bespoke card renderers (e.g.
 * search) read "no results" from a result that was never parsed, showing an empty
 * card instead of falling back to the raw-text preview (#93).
 */
internal fun parseToolOutputContent(tool: UIMessagePart.Tool): JsonElement? {
    if (!tool.isExecuted) return null
    return runCatching {
        JsonInstant.parseToJsonElement(
            tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        )
    }.getOrNull()
}

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    // Whether a generation is running anywhere in this conversation, independent of
    // `loading` (which is already narrowed to false for an executed tool and can't
    // answer that question - see the rerun-button gate below).
    generationActive: Boolean = loading,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String, scope: com.clma.app.service.ChatService.ApprovalScope, toolName: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onRerunTool: (suspend (toolCallId: String) -> com.clma.app.service.ChatService.RerunToolResult)? = null,
    // workspace_shell：后面的思考或正文已经开始。null 表示用户还没手动拨过展开状态。
    collapseInlineOutput: Boolean = false,
) {
    // ask_user / request_secret 是正文级卡片。正常路径由 MessagePartsBlock 直接画；
    // 这里留一层兜底，避免漏进思考链时表单消失。
    if (tool.isInteractiveInputTool()) {
        InteractiveToolCard(
            tool = tool,
            loading = loading,
            onToolApproval = onToolApproval,
            onToolAnswer = onToolAnswer,
        )
        return
    }

    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val context = remember(tool, loading) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = parseToolOutputContent(tool),
            loading = loading,
        )
    }

    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    // 用户点过展开/收起之后以用户的为准；否则 shell 跟着 collapseInlineOutput 走，
    // 其它工具保持旧行为（默认展开）。历史消息第一次组合时后面已经有正文，直接以收起开始，
    // 不会先闪开再合上。正在生成时这一步先以展开进入组合，等后面的 part 出现才收，
    // 父卡片的 animateContentSize 把收起播出来。
    // 胶囊样式反过来：内联摘要默认收起，审批中 / 已拒绝 / 图片 / WebView 需要看见，强制展开。
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val isPending = tool.isPending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    // Text parts nested in tool.output (e.g. run_js's webview payload) that carry
    // rikkahub.webview metadata never reach the top-level Text rendering branch in
    // ChatMessage.kt, since tool output is always folded into this step. Surface them
    // here so skill webview cards (virtual-piano, interactive-map, text-spinner, …) render.
    val webviewParts = tool.output.filterIsInstance<UIMessagePart.Text>()
        .filter { it.hasSkillWebviewMeta() }
    val toolCallStyle = LocalToolCallStyle.current
    val capsule = toolCallStyle == ToolCallStyle.CAPSULE
    val capsuleForceOpen = isPending || isDenied || images.isNotEmpty() || webviewParts.isNotEmpty()
    val expanded = userExpanded ?: when {
        capsule && capsuleForceOpen -> true
        capsule -> false
        else -> !collapseInlineOutput
    }

    // Summary detection is delegated to the registered renderer; image output, webview
    // cards, and denial reasons are common to all tools.
    val hasExtraContent = renderer.hasSummary(context) || isDenied || images.isNotEmpty() || webviewParts.isNotEmpty()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { userExpanded = it },
        icon = {
            if (loading) {
                DotLoading(
                    size = if (capsule) 12.dp else 10.dp
                )
            } else {
                Icon(
                    imageVector = toolCallStyle.stepIcon(tool.toolName, renderer.icon(context)),
                    contentDescription = null,
                    modifier = Modifier.size(if (capsule) CapsuleToolIconSize else 16.dp),
                    tint = if (capsule) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        LocalContentColor.current.copy(alpha = 0.7f)
                    }
                )
            }
        },
        label = {
            Text(
                text = toolCallStyle.stepTitle(
                    toolName = tool.toolName,
                    classicTitle = renderer.title(context),
                    arguments = context.arguments,
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (capsule) FontWeight.Medium else null,
                color = if (capsule) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = if (capsule) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = if (isPending && onToolApproval != null) {
            {
                // Per-row in-flight flag to debounce double-taps. Without this, two rapid
                // clicks on Approve fire handleToolApproval twice — the second cancel()s
                // the first's resume coroutine mid-flight, wastes the in-flight gen step,
                // and can race the persisted state mutation. Keyed on toolCallId so a
                // recomposition for a different tool doesn't carry the flag.
                var inFlight by remember(tool.toolCallId) { mutableStateOf(false) }
                // Relayed MCP tools: the server's own description (untranslated), looked up
                // from the live tool list; display-only.
                val mcpDescription: String? = if (tool.toolName.startsWith("mcp__")) {
                    val mcpManager = koinInject<com.clma.app.data.ai.mcp.McpManager>()
                    remember(tool.toolName) {
                        runCatching {
                            mcpManager.getAllAvailableTools().firstOrNull { (serverId, serverName, mcpTool) ->
                                com.clma.app.data.ai.mcp.buildMcpToolName(serverId, serverName, mcpTool.name) == tool.toolName
                            }?.third?.description
                        }.getOrNull()
                    }
                } else {
                    null
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // What is being approved: MCP server tag, description, raw id.
                    com.clma.app.data.ai.tools.ToolDisplayNames.mcpParts(tool.toolName)?.let {
                        McpServerTag(it.serverName)
                    }
                    ToolDisplaySupporting(toolName = tool.toolName, mcpDescription = mcpDescription)
                    // schedule_job is the one approval that AUTHORISES future autonomous
                    // execution, not just one tool. Surface the consequence here so the
                    // user knows what they're approving — every tool the cron prompt
                    // invokes will run without prompts. (HARDLINE blocks still apply.)
                    if (tool.toolName == "schedule_job") {
                        Text(
                            text = stringResource(R.string.chat_message_tool_schedule_job_warning),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        // Surface mode-specific detail so the user sees WHAT will run.
                        val jobInput = tool.inputAsJson()
                        val mode = jobInput.getStringContent("mode")
                        if (mode == "direct") {
                            val actions = runCatching {
                                (jobInput as? JsonObject)?.get("actions")
                                    ?.jsonArray
                            }.getOrNull()
                            if (actions != null) {
                                Column {
                                    actions.forEachIndexed { i, el ->
                                        val obj = el as? JsonObject
                                        val toolName = obj?.get("tool")?.jsonPrimitive?.contentOrNull ?: "?"
                                        val args = obj?.get("args")?.toString().orEmpty()
                                        val truncatedArgs = if (args.length > 120) args.take(120) + "…" else args
                                        Text(
                                            text = "  ${i + 1}. $toolName $truncatedArgs",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        } else if (mode == "llm") {
                            val prompt = jobInput.getStringContent("prompt").orEmpty()
                            if (prompt.isNotEmpty()) {
                                val truncatedPrompt = if (prompt.length > 200) prompt.take(200) + "…" else prompt
                                Text(
                                    text = truncatedPrompt,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // MCP control tools: render args via the redacting helper so headers
                    // like Authorization / X-Api-Key never appear plainly in the approval
                    // card. Generic args display would leak them (audit finding).
                    if (tool.toolName.startsWith("mcp_")) {
                        val mcpRendered = runCatching {
                            (tool.inputAsJson() as? JsonObject)?.let {
                                com.clma.app.data.ai.mcp.control.McpApprovalRenderer
                                    .render(tool.toolName, it)
                            }
                        }.getOrNull()
                        if (!mcpRendered.isNullOrBlank()) {
                            Text(
                                text = mcpRendered,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Workflow_* mutators: human-readable approval body ("Create workflow X /
                    // When: WiFi connects to HomeWiFi / Do: 1. ssh_exec_saved(host=home) / …").
                    // Action arg values whose key is in the secret-redaction list are masked.
                    if (com.clma.app.workflow.tools.WorkflowApprovalRenderer.isWorkflowTool(tool.toolName)
                        && tool.toolName !in setOf("workflow_list", "workflow_get")) {
                        val workflowRendered = runCatching {
                            com.clma.app.workflow.tools.WorkflowApprovalRenderer
                                .renderPlain(tool.toolName, tool.input.ifBlank { "{}" })
                        }.getOrNull()
                        if (!workflowRendered.isNullOrBlank()) {
                            Text(
                                text = workflowRendered,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Three-button row: Allow (once) / Allow for this chat / Deny. Order
                    // matches the Telegram inline-keyboard layout so the user sees the same
                    // mental model on both surfaces. There is no persistent "always" button:
                    // the blacklist (Settings → Tool approvals) is where a tool stops asking.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ApprovalButton(
                            label = stringResource(R.string.chat_message_tool_approve_once),
                            icon = HugeIcons.Tick01,
                            enabled = !inFlight,
                            onClick = {
                                if (!inFlight) {
                                    inFlight = true
                                    onToolApproval(
                                        tool.toolCallId, true, "",
                                        com.clma.app.service.ChatService.ApprovalScope.Once,
                                        tool.toolName,
                                    )
                                }
                            },
                        )
                        ApprovalButton(
                            label = stringResource(R.string.chat_message_tool_allow_chat),
                            emoji = "💬",
                            enabled = !inFlight,
                            onClick = {
                                if (!inFlight) {
                                    inFlight = true
                                    onToolApproval(
                                        tool.toolCallId, true, "",
                                        com.clma.app.service.ChatService.ApprovalScope.ChatScope,
                                        tool.toolName,
                                    )
                                }
                            },
                        )
                        ApprovalButton(
                            label = stringResource(R.string.chat_message_tool_deny),
                            icon = HugeIcons.Cancel01,
                            enabled = !inFlight,
                            onClick = { if (!inFlight) showDenyDialog = true },
                        )
                    }
                }
            }
        } else {
            null
        },
        // Always clickable: upstream now lets the details sheet open before the tool
        // finishes, which also covers the #93 case (a parse failure leaves
        // context.content null even though the tool executed and has raw text worth
        // showing in the preview sheet).
        onClick = { showResult = true },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    renderer.Summary(context)
                    webviewParts.forEach { webviewPart ->
                        SkillWebviewCardOrNull(part = webviewPart)
                    }
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images, key = { it.url }) { image ->
                                ToolResultImage(
                                    url = image.url,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(
                    tool.toolCallId, false, reason,
                    com.clma.app.service.ChatService.ApprovalScope.Once,
                    tool.toolName,
                )
            }
        )
    }

    if (showResult) {
        ModalBottomSheet(
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            ),
            onDismissRequest = { showResult = false },
            content = {
                Column {
                    renderer.Preview(
                        context = context,
                        onDismissRequest = { showResult = false },
                    )
                    // Only offer a re-run once the first attempt has produced output, and
                    // never while a generation is in flight for this message - rerunTool
                    // itself refuses that case, but hiding the button keeps a click from
                    // spinning up just to be told no.
                    if (tool.isExecuted && !generationActive && onRerunTool != null) {
                        val rerunScope = rememberCoroutineScope()
                        val toaster = LocalToaster.current
                        // "context" in this scope is the ToolUIContext passed to renderer.Preview
                        // above, not an android Context - resolve the failure toast's android
                        // Context separately.
                        val androidContext = androidx.compose.ui.platform.LocalContext.current
                        var rerunInFlight by remember(tool.toolCallId) { mutableStateOf(false) }
                        // The sonner toast host lives in the activity window, below the sheet's
                        // own dialog window, so a toast fired while this sheet is open is never
                        // seen. Surface the failure in-sheet too (toast still fires for the
                        // moment right after dismissal).
                        var rerunError by remember(tool.toolCallId) { mutableStateOf<String?>(null) }
                        // navigationBarsPadding on this wrapper (not just the button) keeps the
                        // whole action row - button and error text - out of the gesture-nav inset
                        // band on edge-to-edge devices; without it the row rendered flush against
                        // the bottom edge and taps landing there never reached the button.
                        Column(modifier = Modifier.navigationBarsPadding()) {
                            rerunError?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = {
                                        if (rerunInFlight) return@TextButton
                                        rerunInFlight = true
                                        rerunError = null
                                        rerunScope.launch {
                                            val result = onRerunTool(tool.toolCallId)
                                            rerunInFlight = false
                                            if (result is com.clma.app.service.ChatService.RerunToolResult.Failure) {
                                                val message = androidContext.getString(
                                                    R.string.chat_message_tool_rerun_failed,
                                                    result.message,
                                                )
                                                rerunError = message
                                                toaster.show(
                                                    message = message,
                                                    type = ToastType.Error,
                                                )
                                            }
                                        }
                                    },
                                    enabled = !rerunInFlight,
                                ) {
                                    if (rerunInFlight) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = HugeIcons.Refresh03,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.chat_message_tool_rerun),
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskUserToolCard(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String, scope: com.clma.app.service.ChatService.ApprovalScope, toolName: String) -> Unit)?,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.isPending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val questions = remember(tool.input) { parseAskUserQuestions(tool.inputAsJson()) }
    val answers = remember(tool.toolCallId) { mutableStateMapOf<String, String>() }
    val multiAnswers = remember(tool.toolCallId) { mutableStateMapOf<String, Set<String>>() }
    var inFlight by remember(tool.toolCallId) { mutableStateOf(false) }

    if (isDenied) {
        InteractiveSettledRow(
            icon = HugeIcons.Cancel01,
            text = stringResource(R.string.chat_interactive_card_cancelled),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (isAnswered) {
        val summary = askUserAnswerSummary(tool, questions)
        InteractiveSettledRow(
            icon = HugeIcons.Tick01,
            text = if (summary.isBlank()) {
                stringResource(R.string.chat_interactive_card_answered_plain)
            } else {
                stringResource(R.string.chat_interactive_card_answered, summary)
            },
            tint = MaterialTheme.colorScheme.primary,
        )
        return
    }

    val title = when {
        questions.size > 1 -> stringResource(R.string.chat_message_tool_ask_questions, questions.size)
        questions.size == 1 -> questions.first().question.ifBlank {
            stringResource(R.string.chat_interactive_card_ask_title)
        }
        else -> stringResource(R.string.chat_interactive_card_ask_title)
    }
    val showForm = isPending && onToolAnswer != null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        tonalElevation = 1.dp,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading && questions.isEmpty()) {
                    DotLoading(size = 12.dp)
                } else {
                    Icon(
                        imageVector = HugeIcons.BubbleChatQuestion,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .weight(1f)
                        .shimmer(isLoading = loading && questions.isEmpty()),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            questions.forEach { q ->
                val repeatQuestion = questions.size > 1 && q.question.isNotBlank()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (repeatQuestion) {
                        Text(
                            text = q.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (showForm) {
                        if (q.options.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                q.options.forEach { option ->
                                    val selectedOptions = multiAnswers[q.id] ?: emptySet()
                                    FilterChip(
                                        selected = if (q.selectionType == "multi") {
                                            option in selectedOptions
                                        } else {
                                            answers[q.id] == option
                                        },
                                        onClick = {
                                            if (q.selectionType == "multi") {
                                                multiAnswers[q.id] = if (option in selectedOptions) {
                                                    selectedOptions - option
                                                } else {
                                                    selectedOptions + option
                                                }
                                            } else {
                                                answers[q.id] = option
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = option,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = answers[q.id] ?: "",
                            onValueChange = { answers[q.id] = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall,
                            singleLine = false,
                            minLines = 1,
                            maxLines = 3,
                        )
                    }
                }
            }

            if (showForm) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onToolApproval != null) {
                        TextButton(
                            onClick = {
                                if (inFlight) return@TextButton
                                inFlight = true
                                onToolApproval(
                                    tool.toolCallId,
                                    false,
                                    INTERACTIVE_CANCEL_REASON,
                                    com.clma.app.service.ChatService.ApprovalScope.Once,
                                    tool.toolName,
                                )
                            },
                            enabled = !inFlight,
                        ) {
                            Text(stringResource(R.string.chat_interactive_card_cancel))
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            if (inFlight) return@FilledTonalButton
                            inFlight = true
                            val answerPayload = buildJsonObject {
                                put("answers", buildJsonObject {
                                    questions.forEach { q ->
                                        when (q.selectionType) {
                                            "multi" -> put(q.id, JsonPrimitive(
                                                (multiAnswers[q.id].orEmpty().toList() +
                                                    listOfNotNull(answers[q.id]?.takeIf { it.isNotBlank() }))
                                                    .joinToString(", ")
                                            ))
                                            else -> put(q.id, JsonPrimitive(answers[q.id] ?: ""))
                                        }
                                    }
                                })
                            }
                            onToolAnswer(tool.toolCallId, answerPayload.toString())
                        },
                        enabled = !inFlight && questions.isNotEmpty() && questions.all { q ->
                            when (q.selectionType) {
                                "multi" -> !multiAnswers[q.id].isNullOrEmpty() || !answers[q.id].isNullOrBlank()
                                else -> !answers[q.id].isNullOrBlank()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.chat_message_tool_submit),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.chat_interactive_card_waiting),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = "text", // "text" | "single" | "multi"
)

private fun parseAskUserQuestions(arguments: kotlinx.serialization.json.JsonElement): List<AskUserQuestion> =
    runCatching {
        arguments.jsonObject["questions"]?.jsonArray?.map { q ->
            val obj = q.jsonObject
            AskUserQuestion(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                options = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text",
            )
        } ?: emptyList()
    }.getOrElse { emptyList() }

private fun askUserAnswerSummary(tool: UIMessagePart.Tool, questions: List<AskUserQuestion>): String {
    val answered = tool.approvalState as? ToolApprovalState.Answered ?: return ""
    val answers = runCatching {
        JsonInstant.parseToJsonElement(answered.answer)
            .jsonObject["answers"]
            ?.jsonObject
    }.getOrNull()
    val parts = if (answers != null && questions.isNotEmpty()) {
        questions.mapNotNull { q ->
            answers[q.id]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }
    } else {
        emptyList()
    }
    if (parts.isNotEmpty()) return parts.joinToString(" · ").replace('\n', ' ').trim()
    val raw = answered.answer.trim()
    if (raw.startsWith("{") || raw.startsWith("[")) return ""
    return raw.replace('\n', ' ')
}

/**
 * Secure input card for request_secret.
 *
 * Body-level card, not a chain-of-thought step. The plaintext is written straight into
 * [AgentSecretsStore] and never appears in [ToolApprovalState.Answered] / chat history /
 * the LLM cloud. Only `{stored,name,kind}` is returned as the tool answer.
 * Cancel reuses the deny path and does not ask for a reason.
 */
@Composable
private fun RequestSecretToolCard(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String, scope: com.clma.app.service.ChatService.ApprovalScope, toolName: String) -> Unit)?,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.isPending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val arguments = tool.inputAsJson().jsonObject
    val name = arguments["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val prompt = arguments["prompt"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.chat_message_tool_request_secret_default_prompt, name.ifBlank { "…" })

    if (isDenied) {
        InteractiveSettledRow(
            icon = HugeIcons.Cancel01,
            text = stringResource(R.string.chat_interactive_card_cancelled),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (isAnswered) {
        val storedName = runCatching {
            JsonInstant.parseToJsonElement((tool.approvalState as ToolApprovalState.Answered).answer)
                .jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: name
        InteractiveSettledRow(
            icon = HugeIcons.Tick01,
            text = stringResource(R.string.chat_message_tool_request_secret_stored, storedName.ifBlank { name }),
            tint = MaterialTheme.colorScheme.primary,
        )
        return
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        tonalElevation = 1.dp,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    DotLoading(size = 12.dp)
                } else {
                    Icon(
                        imageVector = HugeIcons.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = stringResource(R.string.chat_message_tool_request_secret_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .shimmer(isLoading = loading),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (isPending && onToolAnswer != null) {
                RequestSecretForm(
                    tool = tool,
                    name = name,
                    kind = arguments["kind"]?.jsonPrimitive?.contentOrNull ?: "password",
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                )
            } else {
                Text(
                    text = stringResource(R.string.chat_interactive_card_waiting),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RequestSecretForm(
    tool: UIMessagePart.Tool,
    name: String,
    kind: String,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String, scope: com.clma.app.service.ChatService.ApprovalScope, toolName: String) -> Unit)?,
    onToolAnswer: (toolCallId: String, answer: String) -> Unit,
) {
    val secretsStore: AgentSecretsStore = koinInject()
    val isPrivateKey = kind == "private_key"
    var value by remember(tool.toolCallId) { mutableStateOf("") }
    var confirm by remember(tool.toolCallId) { mutableStateOf("") }
    var showPlain by remember(tool.toolCallId) { mutableStateOf(false) }
    var errorText by remember(tool.toolCallId) { mutableStateOf<String?>(null) }
    var inFlight by remember(tool.toolCallId) { mutableStateOf(false) }
    val nameValid = remember(name) {
        name.isNotBlank() && Regex("""[A-Za-z0-9_]{1,64}""").matches(name)
    }
    val existsAlready = remember(tool.toolCallId, name) {
        nameValid && secretsStore.has(name)
    }

    Text(
        text = stringResource(R.string.chat_message_tool_request_secret_name_label, name.ifBlank { "—" }) +
            if (existsAlready) {
                " · " + stringResource(R.string.chat_message_tool_request_secret_overwrite)
            } else {
                ""
            },
        style = MaterialTheme.typography.labelSmall,
        color = if (existsAlready) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    Text(
        text = stringResource(R.string.chat_message_tool_request_secret_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            errorText = null
        },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = !isPrivateKey,
        minLines = if (isPrivateKey) 4 else 1,
        maxLines = if (isPrivateKey) 12 else 1,
        visualTransformation = if (showPlain || isPrivateKey) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        label = {
            Text(stringResource(R.string.chat_message_tool_request_secret_value_label))
        },
    )
    if (!isPrivateKey) {
        OutlinedTextField(
            value = confirm,
            onValueChange = {
                confirm = it
                errorText = null
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            visualTransformation = if (showPlain) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            label = {
                Text(stringResource(R.string.chat_message_tool_request_secret_confirm_label))
            },
        )
    }
    TextButton(onClick = { showPlain = !showPlain }) {
        Text(
            text = stringResource(
                if (showPlain) {
                    R.string.chat_message_tool_request_secret_hide
                } else {
                    R.string.chat_message_tool_request_secret_show
                },
            ),
        )
    }
    errorText?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onToolApproval != null) {
            TextButton(
                onClick = {
                    if (inFlight) return@TextButton
                    inFlight = true
                    value = ""
                    confirm = ""
                    onToolApproval(
                        tool.toolCallId,
                        false,
                        INTERACTIVE_CANCEL_REASON,
                        com.clma.app.service.ChatService.ApprovalScope.Once,
                        tool.toolName,
                    )
                },
                enabled = !inFlight,
            ) {
                Text(stringResource(R.string.chat_interactive_card_cancel))
            }
        }
        FilledTonalButton(
            onClick = {
                if (inFlight) return@FilledTonalButton
                when {
                    !nameValid -> errorText = "Invalid vault name"
                    value.isEmpty() -> errorText = "Value required"
                    !isPrivateKey && value != confirm -> errorText = "Values do not match"
                    else -> {
                        inFlight = true
                        val ok = secretsStore.set(name, value)
                        value = ""
                        confirm = ""
                        if (!ok) {
                            inFlight = false
                            errorText = "Failed to store secret"
                            return@FilledTonalButton
                        }
                        val answerPayload = buildJsonObject {
                            put("stored", true)
                            put("name", name)
                            put("kind", kind)
                        }
                        onToolAnswer(tool.toolCallId, answerPayload.toString())
                    }
                }
            },
            enabled = !inFlight && nameValid && value.isNotEmpty() && (isPrivateKey || confirm.isNotEmpty()),
        ) {
            Icon(
                imageVector = HugeIcons.Tick01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.chat_message_tool_request_secret_store),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun InteractiveSettledRow(
    icon: ImageVector,
    text: String,
    tint: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/** Compact labelled approval button (icon or emoji + text) for the pending tool card. */
@Composable
private fun ApprovalButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    emoji: String? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(30.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
        } else if (emoji != null) {
            Text(emoji, style = MaterialTheme.typography.labelSmall)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
