package com.clma.app.ui.pages.chat

import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.clma.app.ui.components.ui.rikkaGlassStyle
import com.clma.app.ui.components.ui.frostedPlate
import com.clma.app.ui.components.ui.frostedPlateSpec
import com.clma.app.ui.components.message.WallpaperOnlySelection
import com.clma.app.ui.theme.LocalDarkMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.clma.ai.provider.BuiltInTools
import com.clma.ai.provider.Model
import com.clma.ai.provider.ProviderSetting
import com.clma.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import com.clma.app.R
import com.clma.app.costguards.TokenBudgetTracker
import com.clma.app.data.usage.TurnFooter
import com.clma.app.data.datastore.ChatLayout
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.effectiveChatLayout
import com.clma.app.data.datastore.findProvider
import com.clma.app.data.datastore.getCurrentAssistant
import com.clma.app.data.datastore.getSelectedASRProvider
import com.clma.app.data.datastore.resolveChatModel
import com.clma.app.data.files.FilesManager
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.Conversation
import com.clma.app.data.repository.WorkspaceRepository
import com.clma.app.service.ChatError
import com.clma.app.ui.components.ai.ChatAttachmentPickerActions
import com.clma.app.Screen
import com.clma.app.ui.components.ai.ChatInput
import com.clma.app.ui.components.ai.FilesPicker
import com.clma.app.ui.components.ai.SearchMode
import com.clma.app.ui.components.ai.completion.WorkspaceCompletionProvider
import com.clma.app.ui.components.ai.rememberChatAttachmentPickerActions
import com.clma.app.ui.context.LocalNavController
import com.clma.app.ui.context.LocalToaster
import com.clma.app.ui.context.Navigator
import com.clma.app.ui.hooks.ChatInputState
import com.clma.app.subagent.SubAgentRegistry
import com.clma.app.subagent.SubAgentRun
import com.clma.app.subagent.SubAgentStatus
import com.clma.app.utils.base64Decode
import com.clma.app.utils.classifyChatAttachment
import com.clma.app.utils.guessMimeFromFileName
import com.clma.app.utils.navigateToChatPage
import com.clma.app.utils.toMessagePart
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    // U4 — the sub-agent registry is not collected anywhere in the UI today, so a parent turn that
    // dispatched background work had no live signal at all. Fold it into the status line the chat
    // already shows instead of adding a second surface.
    val subAgentRegistry: SubAgentRegistry = koinInject()
    val subAgentRuns by subAgentRegistry.runs.collectAsStateWithLifecycle()
    val subAgentStatus = subAgentStatusLine(subAgentRuns)
    val mergedStatus = listOfNotNull(processingStatus, subAgentStatus)
        .joinToString(separator = "  ·  ")
        .ifBlank { null }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    // 与抽屉共享同一个 activity 级 ChatDrawerVM，返回键才能先退出多选而不是关掉抽屉。
    val activity = LocalContext.current as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)
    val multiSelectActive by drawerVm.multiSelectActive.collectAsStateWithLifecycle()
    // Shared Haze state between the chat page and the drawer: the page marks its background +
    // message list as the blur source (hazeSource), so the drawer's glass panel refracts the
    // page sitting behind it instead of showing an opaque block.
    val hazeState = rememberHazeState()
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 抽屉打开时：多选中先退出多选；否则关闭抽屉。
    // 手机分支在 ModalNavigationDrawer 之后还有一个更晚的返回键，两处必须同一规则。
    BackHandler(enabled = drawerState.isOpen && multiSelectActive) {
        drawerVm.requestExitMultiSelect()
    }
    BackHandler(enabled = drawerState.isOpen && !multiSelectActive) {
        scope.launch {
            drawerState.close()
        }
    }

    // Clear input focus so popup transitions cannot reopen the keyboard.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }

    @Suppress("DEPRECATION")  // LocalWindowInfo replaces this in a future Compose bump
    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val startVoiceMode = rememberVoiceModeStarter(vm, setting)

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            // Pair each source URI with its copied local file. createChatFilesByContents may
            // skip failures, so zip by successful copies in order rather than assuming 1:1.
            val localFiles = filesManager.createChatFilesByContents(files)
            val parts = buildList {
                // Best-effort: walk sources and locals in parallel when counts match; otherwise
                // classify each local file from its on-disk name.
                if (localFiles.size == files.size) {
                    localFiles.forEachIndexed { index, local ->
                        val source = files[index]
                        val fileName = filesManager.getFileNameFromUri(source)
                            ?: local.lastPathSegment
                            ?: "file"
                        val mime = filesManager.getFileMimeType(source)
                            ?: guessMimeFromFileName(fileName)
                            ?: "application/octet-stream"
                        add(
                            classifyChatAttachment(fileName, mime).toMessagePart(
                                localUri = local.toString(),
                                fileName = fileName,
                                mime = mime,
                            )
                        )
                    }
                } else {
                    localFiles.forEach { local ->
                        val fileName = local.lastPathSegment ?: "file"
                        val mime = guessMimeFromFileName(fileName)
                            ?: "application/octet-stream"
                        add(
                            classifyChatAttachment(fileName, mime).toMessagePart(
                                localUri = local.toString(),
                                fileName = fileName,
                                mime = mime,
                            )
                        )
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                        hazeState = hazeState,
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = mergedStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    hazeState = hazeState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                        hazeState = hazeState,
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = mergedStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    hazeState = hazeState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            BackHandler(enabled = drawerState.isOpen && multiSelectActive) {
                drawerVm.requestExitMultiSelect()
            }
            BackHandler(enabled = drawerState.isOpen && !multiSelectActive) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

/**
 * U4 — a one-line live summary of the sub-agent runs in flight, folded into the chat's existing
 * status line. Shows how many are running and how long the oldest has been going, and re-composes
 * once a second so the elapsed time keeps moving — the moving clock is the "still alive" signal
 * that was missing while a parent turn waited on background work. Returns null when none are
 * active, so the status line is exactly as before on an install that never dispatches.
 */
@Composable
private fun subAgentStatusLine(runs: Map<String, SubAgentRun>): String? {
    val active = runs.values.filter {
        it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING
    }
    if (active.isEmpty()) return null
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active.size) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val oldestStartMs = active.minOf { it.startedAtMs }
    val elapsedSeconds = ((nowMs - oldestStartMs) / 1_000L).coerceAtLeast(0L)
    return stringResource(
        R.string.chat_page_subagents_running,
        active.size,
        formatElapsed(elapsedSeconds),
    )
}

private fun formatElapsed(seconds: Long): String {
    val minutes = seconds / 60
    val remaining = seconds % 60
    return "%d:%02d".format(minutes, remaining)
}

@Composable
private fun ChatPageContent(
    onStartVoiceMode: () -> Unit,
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    hazeState: HazeState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val turnFooters by vm.turnFooters.collectAsStateWithLifecycle()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val assistant = setting.getCurrentAssistant()
    val compressAndContinueLabel = stringResource(R.string.chat_suggestion_compress_continue)
    var showFilesSheet by remember { mutableStateOf(false) }
    val attachmentPickerActions = rememberChatAttachmentPickerActions(
        inputState = inputState,
        setting = setting,
        onAttachmentAdded = { showFilesSheet = false },
    )
    val allowAudioVideoAttachments =
        currentChatModel?.findProvider(setting.providers) is ProviderSetting.Google

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    // Model and search live on the input bar (model chip, search icon). The handlers stay here.
    val onUpdateSearchMode: (SearchMode) -> Unit = { mode ->
        val current = setting.getCurrentAssistant()
        val model = currentChatModel
        vm.updateSettings(
            setting.copy(
                assistants = setting.assistants.map { assistant ->
                    if (assistant.id == current.id) {
                        assistant.copy(enableWebSearch = mode == SearchMode.LOCAL)
                    } else {
                        assistant
                    }
                },
                providers = if (model == null) {
                    setting.providers
                } else {
                    setting.providers.map { provider ->
                        provider.editModel(
                            model.copy(
                                tools = if (mode == SearchMode.BUILT_IN) {
                                    model.tools + BuiltInTools.Search
                                } else {
                                    model.tools - BuiltInTools.Search
                                }
                            )
                        )
                    }
                },
            )
        )
    }
    val onUpdateChatModel: (Model) -> Unit = { model ->
        vm.setConversationChatModel(model)
    }
    val onUpdateSearchService: (Int) -> Unit = { index ->
        vm.updateSettings(setting.copy(searchServiceSelected = index))
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(
            setting = setting,
            // Keyed so the usage line under replies can blur the wallpaper alone.
            modifier = Modifier.hazeSource(hazeState, key = CHAT_WALLPAPER_HAZE_KEY),
        )
        Scaffold(
            // The title bar is drawn inside the content instead (see the overlay below): Scaffold
            // places its content over the top bar, and the conversation has to scroll UNDER the
            // title and fade out behind it.
            topBar = {},
            bottomBar = {
                val messageQueue by vm.messageQueue.collectAsStateWithLifecycle()
                val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
                ChatInput(
                    onStartVoiceMode = onStartVoiceMode,
                    voiceState = voiceState,
                    onStopVoiceMode = vm.voiceSession::stop,
                    state = inputState,
                    messageQueue = messageQueue,
                    onRemoveQueuedMessage = vm::removeQueuedMessage,
                    onBeginEditQueuedMessage = vm::beginEditQueuedMessage,
                    onFinishEditQueuedMessage = vm::finishEditQueuedMessage,
                    onResumeMessageQueue = vm::resumeMessageQueue,
                    loading = loadingJob != null,
                    settings = setting,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    onSendClick = {
                        // Settings.dummy() is still standing in for the real settings: nothing
                        // resolves yet, so do nothing (the draft stays) rather than misreport.
                        if (setting.init) return@ChatInput
                        if (currentChatModel == null) {
                            toaster.show(
                                context.getString(R.string.chat_select_model_first),
                                type = ToastType.Error,
                            )
                            // Dead-end fix: instead of only refusing, take the user to the
                            // first-run checklist so they can see everything that still needs
                            // setting up — and land on the "add a model" step.
                            navController.navigate(Screen.GettingStarted)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            scope.launch {
                                delay(100.milliseconds)
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onInterjectClick = if (setting.displaySetting.enableInterject) {
                        {
                            // 生成中插话：并入当前回合，而不是排队等本轮结束。
                            // 竞态下（生成刚好结束）保留草稿并提示，不静默吞掉输入。
                            if (vm.interjectMessage(inputState.getContents())) {
                                inputState.clearInput()
                                scope.launch {
                                    delay(100.milliseconds)
                                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                                }
                            } else {
                                toaster.show(context.getString(R.string.chat_interject_unavailable))
                            }
                        }
                    } else null,
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    enableSearch = enableWebSearch,
                    conversationChatModelId = conversation.chatModelId,
                    conversationId = conversation.id,
                    onUpdateChatModel = onUpdateChatModel,
                    onUpdateModelReasoningLevel = { model, level ->
                        vm.setModelReasoningLevel(model, level)
                    },
                    onUpdateSearchMode = onUpdateSearchMode,
                    onUpdateSearchService = onUpdateSearchService,
                    onMoreClick = {
                        showFilesSheet = true
                    },
                )
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            // The title bar rides above the message list, so its measured height is what the list
            // has to stay clear of at rest; the rest of the inset becomes content padding, which is
            // what lets messages scroll underneath the bar.
            var topBarHeightPx by remember { mutableStateOf(0) }
            val topBarHeight = with(LocalDensity.current) { topBarHeightPx.toDp() }
            val layoutDirection = LocalLayoutDirection.current
            val listInset = PaddingValues(
                start = innerPadding.calculateLeftPadding(layoutDirection),
                top = if (topBarHeightPx > 0) {
                    topBarHeight
                } else {
                    innerPadding.calculateTopPadding() + 64.dp
                },
                end = innerPadding.calculateRightPadding(layoutDirection),
                bottom = innerPadding.calculateBottomPadding(),
            )

            Box(modifier = Modifier.fillMaxSize()) {
                ChatList(
                    innerPadding = listInset,
                    conversation = conversation,
                    turnFooters = turnFooters,
                    state = chatListState,
                    loading = loadingJob != null,
                    processingStatus = processingStatus,
                    previewMode = previewMode,
                    settings = setting,
                    hazeState = hazeState,
                    errors = errors,
                    onDismissError = onDismissError,
                    onClearAllErrors = onClearAllErrors,
                    onRegenerate = {
                        vm.regenerateAtMessage(it)
                    },
                    onEdit = {
                        inputState.editingMessage = it.id
                        inputState.setContents(it.parts)
                    },
                    onForkMessage = {
                        scope.launch {
                            val fork = vm.forkMessage(message = it)
                            navigateToChatPage(navController, chatId = fork.id)
                        }
                    },
                    onDelete = {
                        // Deletion is persisted by ChatService and must not be blocked by a
                        // stale UI job reference (especially after an offline/disconnected turn).
                        vm.deleteMessage(it)
                    },
                    onUpdateMessage = { newNode ->
                        vm.updateConversation(
                            conversation.copy(
                                messageNodes = conversation.messageNodes.map { node ->
                                    if (node.id == newNode.id) {
                                        newNode
                                    } else {
                                        node
                                    }
                                }
                            ))
                        vm.saveConversationAsync()
                    },
                    onClickSuggestion = { suggestion ->
                        if (suggestion == compressAndContinueLabel) {
                            // Not a message: compress the history, then resume the turn.
                            vm.compressAndContinue()
                        } else {
                            inputState.editingMessage = null
                            inputState.setMessageText(suggestion)
                        }
                    },
                    onTranslate = { message, locale ->
                        vm.translateMessage(message, locale)
                    },
                    onClearTranslation = { message ->
                        vm.clearTranslationField(message.id)
                    },
                    onJumpToMessage = { index ->
                        previewMode = false
                        scope.launch {
                            chatListState.requestScrollToItem(index)
                        }
                    },
                    onToolApproval = { toolCallId, approved, reason, scope, toolName ->
                        vm.handleToolApproval(toolCallId, approved, reason, scope, toolName)
                    },
                    onToolAnswer = { toolCallId, answer ->
                        vm.handleToolAnswer(toolCallId, answer)
                    },
                    onRerunTool = { toolCallId ->
                        vm.rerunTool(toolCallId)
                    },
                    onToggleFavorite = { node ->
                        vm.toggleMessageFavorite(node)
                    },
                    onConversationSystemPromptChange = { newPrompt ->
                        vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                        vm.saveConversationAsync()
                    },
                )
                val darkTheme = LocalDarkMode.current
                // Fade scrims over the list: content dissolves as it slides under the top bar
                // (top dissolve) and down into the floating input bar (bottom dissolve), while
                // the liquid glass controls refract whatever passes behind them.
                val photoShown = chatPhotoShown(
                    effectiveBackground(
                        darkTheme = darkTheme,
                        background = assistant.background,
                        darkBackground = assistant.darkBackground,
                        lightBackground = assistant.lightBackground,
                        mode = assistant.backgroundMode,
                    ),
                    assistant.useGradientBackground,
                )
                val topFade = titleBarFade(
                    darkTheme = darkTheme,
                    photoShown = photoShown,
                )
                val bottomFade = bottomBarFade(darkTheme = darkTheme, photoShown = photoShown)
                val fadeColor = MaterialTheme.colorScheme.background
                // CLEAN 与 CLAUDE 都走扁平顶栏 + 面板，不需要顶部溶解。
                val chatLayout = setting.displaySetting.effectiveChatLayout
                val cleanLayout = chatLayout != ChatLayout.ORIGINAL

                // Top dissolve: extends below the title bar so incoming/outgoing messages
                // fade in/out smoothly instead of clipping abruptly at the bar's bottom edge.
                // The clean layout has a real panel up there (CleanTopBarPanel) with an edge and a
                // fill of its own, so it needs no dissolve — and the dissolve was the white haze
                // that made the top look wrong over a photo.
                val topFadeExtension = 40.dp
                val topFadeHeight = if (topBarHeightPx > 0) {
                    topBarHeight + topFadeExtension
                } else {
                    innerPadding.calculateTopPadding() + 64.dp + topFadeExtension
                }
                if (!cleanLayout) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(topFadeHeight)
                            .background(
                                Brush.verticalGradient(
                                    0f to fadeColor.copy(alpha = topFade.topAlpha),
                                    (if (topBarHeightPx > 0) (topBarHeight / topFadeHeight).coerceIn(0.3f, 0.7f) else 0.5f) to
                                        fadeColor.copy(alpha = topFade.midAlpha),
                                    1f to Color.Transparent,
                                )
                            )
                    )
                }

                // Bottom dissolve: smooth fade-out as messages slide down toward the bottom bar.
                val bottomBarHeight = innerPadding.calculateBottomPadding()
                val bottomFadeExtension = 48.dp
                val bottomFadeHeight = bottomBarHeight + bottomFadeExtension
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(bottomFadeHeight)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                (bottomFadeExtension / bottomFadeHeight).coerceIn(0.2f, 0.6f) to
                                    fadeColor.copy(alpha = bottomFade.midAlpha),
                                1f to fadeColor.copy(alpha = bottomFade.bottomAlpha),
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .onSizeChanged { topBarHeightPx = it.height },
                ) {
                    if (chatLayout != ChatLayout.ORIGINAL) {
                        CleanTopBarPanel(settings = setting, hazeState = hazeState) {
                            CleanTopBar(
                                settings = setting,
                                conversation = conversation,
                                turnFooters = turnFooters,
                                drawerState = drawerState,
                                bigScreen = bigScreen,
                                previewMode = previewMode,
                                onClickMenu = { previewMode = !previewMode },
                                onNewChat = { navigateToChatPage(navController) },
                            )
                        }
                    } else TopBar(
                        settings = setting,
                        conversation = conversation,
                        turnFooters = turnFooters,
                        bigScreen = bigScreen,
                        drawerState = drawerState,
                        hazeState = hazeState,
                        previewMode = previewMode,
                        onNewChat = {
                            navigateToChatPage(navController)
                        },
                        onClickMenu = {
                            previewMode = !previewMode
                        },
                    )
                }
            }
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                vm = vm,
                attachmentPickerActions = attachmentPickerActions,
                onStartVoiceMode = onStartVoiceMode,
                onDismiss = { showFilesSheet = false },
            )
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    attachmentPickerActions: ChatAttachmentPickerActions,
    onStartVoiceMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showInjectionSheet = false
        showCompressDialog = false
        onDismiss()
    }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            mcpManager = vm.mcpManager,
            onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
            },
            onUpdateAssistant = {
                vm.updateSettings(
                    setting.copy(
                        assistants = setting.assistants.map { assistant ->
                            if (assistant.id == it.id) {
                                it
                            } else {
                                assistant
                            }
                        }
                    )
                )
            },
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showInjectionSheet = showInjectionSheet,
            onShowInjectionSheetChange = { showInjectionSheet = it },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = attachmentPickerActions.onTakePicture,
            onPickImage = attachmentPickerActions.onPickImage,
            onPickVideo = attachmentPickerActions.onPickVideo,
            onPickAudio = attachmentPickerActions.onPickAudio,
            onPickFile = attachmentPickerActions.onPickFile,
            onStartVoiceMode = if (
                setting.getSelectedASRProvider()?.supportsServerVadVoiceMode == true &&
                voiceState.phase == VoicePhase.Off
            ) {
                {
                    dismissAll()
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onStartVoiceMode()
                }
            } else null,
        )
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    turnFooters: Map<String, TurnFooter>,
    drawerState: DrawerState,
    hazeState: HazeState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    // 与抽屉的 Rename 菜单项共享同一个重命名对话框状态，见 ChatDrawerVM.conversationToRename
    val activity = LocalContext.current as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                GlassTopBarButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    },
                    hazeState = hazeState,
                ) {
                    Icon(HugeIcons.Menu03, stringResource(R.string.menu))
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            val assistant = settings.getCurrentAssistant()
            val titleOnPhoto = looseTextNeedsPhotoPlate(
                darkTheme = LocalDarkMode.current,
                photoShown = chatPhotoShown(
                    effectiveBackground(
                        darkTheme = LocalDarkMode.current,
                        background = assistant.background,
                        darkBackground = assistant.darkBackground,
                        lightBackground = assistant.lightBackground,
                        mode = assistant.backgroundMode,
                    ),
                    assistant.useGradientBackground,
                ),
            )
            // API 31+: a frosted pill, the same full rounding as the glass buttons beside it.
            // Older devices keep the flat 0.86 plate.
            val titleFrost = frostedPlateSpec(titleOnPhoto, Build.VERSION.SDK_INT)
            val titleShape = if (titleFrost != null) RoundedCornerShape(percent = 50) else RoundedCornerShape(12.dp)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        drawerVm.requestRenameConversation(conversation)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                modifier = if (titleFrost != null) {
                    Modifier.frostedPlate(hazeState, titleShape, titleFrost)
                } else {
                    Modifier
                },
                shape = titleShape,
                color = if (titleOnPhoto && titleFrost == null) {
                    MaterialTheme.colorScheme.surface.copy(alpha = PHOTO_TITLE_PLATE_ALPHA)
                } else {
                    Color.Transparent
                },
            ) {
                Column(
                    modifier = when {
                        titleFrost != null -> Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                        titleOnPhoto -> Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                        else -> Modifier
                    },
                ) {
                    val model = settings.resolveChatModel(conversation.chatModelId, conversation.assistantId)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null) {
                        // Aggregate conversation token consumption across all turns and tool calls.
                        // UsageLedger (turnFooters) is the ground truth (includes all tool loop steps);
                        // fallback to TokenBudgetTracker for legacy or imported conversations without ledger rows.
                        val usedTokens = remember(turnFooters, conversation) {
                            conversationUsedTokens(turnFooters, conversation)
                        }
                        // One Text ellipsizes from the end, so a long assistant or model name
                        // clips the count ("0 t..."). Same split as CleanTopBar: only the name may
                        // ellipsize. Custom measure, not weight, so the frosted pill still hugs the
                        // text instead of stretching to the title slot.
                        TopBarSubtitleLine(
                            assistantName = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                            modelName = model.displayName,
                            usedTokens = usedTokens,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            ),
                        )
                    }
                }
            }
        },
        actions = {
            GlassTopBarButton(
                onClick = {
                    onClickMenu()
                },
                hazeState = hazeState,
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, stringResource(R.string.accessibility_chat_options))
            }

            GlassTopBarButton(
                onClick = {
                    onNewChat()
                },
                hazeState = hazeState,
            ) {
                Icon(HugeIcons.MessageAdd01, stringResource(R.string.chat_page_new_message))
            }
        },
    )
}

/**
 * The title bar keeps its transparent background (the conversation shows through); only the three
 * buttons get a glass disc each, so they stay legible over whatever scrolls behind them.
 */
@Composable
private fun GlassTopBarButton(
    onClick: () -> Unit,
    hazeState: HazeState,
    content: @Composable () -> Unit,
) {
    val glassStyle = rikkaGlassStyle(CircleShape, blurRadius = 12.dp, depth = 0.4f)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(40.dp)
            .clip(CircleShape)
            .hazeGlass(
                input = HazeInput.Sources(hazeState, selection = WallpaperOnlySelection),
                style = glassStyle,
            ),
        shape = CircleShape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/**
 * Regular-bar subtitle: "assistant / model" plus " / N tokens".
 *
 * A single [Text] with [TextOverflow.Ellipsis] clips the end, so a long assistant or model
 * name turns the count into "0 t...". The token suffix is measured first and always placed;
 * only the name gets the remaining width. The layout reports the sum of the two, not the
 * incoming max width, so the frosted title pill keeps hugging the text. A weighted
 * Row would stretch that pill to the title slot.
 */
@Composable
private fun TopBarSubtitleLine(
    assistantName: String,
    modelName: String,
    usedTokens: Long,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            Text(
                text = topBarSubtitleName(assistantName, modelName),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                color = color,
                style = style,
            )
            Text(
                text = topBarTokenSuffix(usedTokens),
                maxLines = 1,
                softWrap = false,
                // Last resort only: the suffix is given the full width first, so this clips
                // solely when " / N tokens" itself is wider than the title slot.
                overflow = TextOverflow.Ellipsis,
                color = color,
                style = style,
            )
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val token = measurables[1].measure(loose.copy(maxWidth = constraints.maxWidth))
        val nameMax = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - token.width).coerceAtLeast(0)
        } else {
            constraints.maxWidth
        }
        val name = measurables[0].measure(loose.copy(maxWidth = nameMax))
        val width = (name.width + token.width).coerceAtLeast(constraints.minWidth).let { raw ->
            if (constraints.hasBoundedWidth) raw.coerceAtMost(constraints.maxWidth) else raw
        }
        val height = maxOf(name.height, token.height).coerceAtLeast(constraints.minHeight).let { raw ->
            if (constraints.hasBoundedHeight) raw.coerceAtMost(constraints.maxHeight) else raw
        }
        layout(width, height) {
            name.placeRelative(0, (height - name.height) / 2)
            token.placeRelative(name.width, (height - token.height) / 2)
        }
    }
}
