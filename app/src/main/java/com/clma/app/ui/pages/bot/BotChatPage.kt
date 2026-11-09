package com.clma.app.ui.pages.bot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.data.botmode.BotTaskStepStatus
import com.clma.app.data.botmode.botTaskChipSummary
import com.clma.app.data.botmode.buildBotTaskSteps
import com.clma.app.data.datastore.getCurrentAssistant
import com.clma.app.subagent.SubAgentRegistry
import com.clma.app.ui.components.ui.AssistantAvatar
import com.clma.app.ui.context.LocalNavController
import com.clma.app.ui.pages.chat.ChatPageContent
import com.clma.app.ui.pages.chat.ChatVM
import com.clma.app.ui.pages.chat.botmode.BotModelPickerButton
import com.clma.app.ui.pages.chat.botmode.BotTaskProgressSheet
import com.clma.app.ui.pages.chat.rememberVoiceModeStarter
import com.clma.app.ui.pages.chat.subAgentStatusLine
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/** How long the task chip stays after a run finishes before it folds away. */
private const val TaskChipLingerMs = 2_500L

/**
 * One bot's long conversation, separate from the classic chat page: back / avatar / name /
 * model (tap to switch) / info in the bar, a task chip under it that takes its own space, inline
 * approval cards in the flow. Sending, streaming and attachments are the shared chat surface.
 */
@Composable
fun BotChatPage(botId: Uuid, conversationId: Uuid) {
    val vm: ChatVM = koinViewModel(parameters = { parametersOf(conversationId.toString(), false) })
    val nav = LocalNavController.current

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversationForUi.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val subAgentRegistry: SubAgentRegistry = koinInject()
    val subAgentRuns by subAgentRegistry.runs.collectAsStateWithLifecycle()
    val subAgentStatus = subAgentStatusLine(subAgentRuns, conversation.id)
    val mergedStatus = listOfNotNull(processingStatus, subAgentStatus).joinToString("  ·  ").ifBlank { null }

    // The conversation decides the bot; fall back to the route's bot while it loads.
    val bot = setting.assistants.find { it.id == conversation.assistantId }
        ?: setting.assistants.find { it.id == botId }
        ?: setting.getCurrentAssistant()
    val botName = bot.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }

    val drawerState = rememberDrawerState(DrawerValue.Closed) // no drawer in Bot chat
    val hazeState = rememberHazeState()
    val chatListState = rememberLazyListState()
    val startVoiceMode = rememberVoiceModeStarter(vm, setting)
    LaunchedEffect(conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            vm.chatListInitialized = true
        }
    }

    // Task progress
    val isGenerating = loadingJob != null
    var showTasks by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    LaunchedEffect(isGenerating) { if (isGenerating) paused = false }
    val generatingLabel = stringResource(R.string.bot_mode_task_generating)
    val idleLabel = stringResource(R.string.bot_mode_task_idle)
    val steps = remember(conversation.messageNodes, conversation.id, mergedStatus, subAgentRuns, isGenerating) {
        buildBotTaskSteps(conversation, mergedStatus, subAgentRuns.values, isGenerating, generatingLabel, idleLabel)
    }
    val waitingApproval = steps.any { it.id.startsWith("tool:") && it.status == BotTaskStepStatus.PENDING }
    val active = isGenerating || waitingApproval
    // Shown while working, kept briefly after it finishes, then folded away.
    var chipVisible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            chipVisible = true
        } else if (chipVisible) {
            delay(TaskChipLingerMs)
            chipVisible = false
        }
    }
    val summary = botTaskChipSummary(steps, isGenerating)

    val openConversation: (Uuid) -> Unit = { id -> nav.navigate(Screen.BotChat(botId.toString(), id.toString())) }
    val topBar: @Composable () -> Unit = {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                    }
                    AssistantAvatar(assistant = bot, name = botName, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = botName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        BotModelPickerButton(
                            modelId = bot.chatModelId,
                            modelLabel = currentChatModel?.displayName,
                            providers = setting.providers,
                            onSelect = { model -> vm.setChatModel(bot, model) },
                        ) { label, open ->
                            Row(
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = open),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(
                                    Lucide.ChevronDown,
                                    contentDescription = stringResource(R.string.bot_chat_switch_model),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    IconButton(onClick = { nav.navigate(Screen.BotInfo(botId = bot.id.toString(), conversationId = conversation.id.toString())) }) {
                        Icon(Lucide.Info, contentDescription = stringResource(R.string.bot_chat_info))
                    }
                }
                AnimatedVisibility(
                    visible = chipVisible && summary != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    summary?.let {
                        BotTaskChip(
                            current = it.current,
                            total = it.total,
                            title = it.title,
                            done = !active && it.allDone,
                            paused = paused,
                            onClick = { showTasks = true },
                        )
                    }
                }
            }
        }
    }

    ChatPageContent(
        onStartVoiceMode = startVoiceMode,
        inputState = vm.inputState,
        loadingJob = loadingJob,
        processingStatus = mergedStatus,
        setting = setting,
        bigScreen = false,
        conversation = conversation,
        drawerState = drawerState,
        hazeState = hazeState,
        navController = nav,
        vm = vm,
        chatListState = chatListState,
        enableWebSearch = enableWebSearch,
        currentChatModel = currentChatModel,
        errors = errors,
        onDismissError = { vm.dismissError(it) },
        onClearAllErrors = { vm.clearAllErrors() },
        botChrome = BotChatChrome(topBar = topBar, onOpenConversation = openConversation),
    )

    BotTaskProgressSheet(
        visible = showTasks,
        botName = botName,
        steps = steps,
        paused = paused,
        isGenerating = isGenerating,
        onDismiss = { showTasks = false },
        onPauseToggle = {
            if (paused) {
                paused = false
            } else {
                if (isGenerating) vm.stopGeneration()
                paused = true
            }
        },
        onTakeOver = {
            if (isGenerating) vm.stopGeneration()
            paused = true
            showTasks = false
        },
        onOpenVirtualDisplayManage = {
            showTasks = false
            nav.navigate(Screen.VirtualDisplays)
        },
    )
}

/** Small progress chip under the bar; it occupies its own row, never overlapping the messages. */
@Composable
private fun BotTaskChip(
    current: Int,
    total: Int,
    title: String,
    done: Boolean,
    paused: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!done && !paused) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = when {
                        paused -> stringResource(R.string.bot_chat_task_paused)
                        done -> stringResource(R.string.bot_chat_task_done)
                        else -> stringResource(R.string.bot_chat_task_running, current, total, title)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.bot_chat_task_details),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.size(6.dp))
            LinearProgressIndicator(
                progress = { if (done) 1f else ((current - 1).coerceAtLeast(0) / total.toFloat().coerceAtLeast(1f)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
