package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.DialogProperties
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.hazeGlass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRStatus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.resolveChatModel
import me.rerere.rikkahub.data.datastore.getQuickMessagesOfAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.service.MessageQueueState
import me.rerere.rikkahub.service.QueuedMessage
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionContext
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionItem
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionList
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionProvider
import me.rerere.rikkahub.ui.components.ui.AutoAIIconFill
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.rikkaGlassStyle
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.SoundEffectPlayer
import org.koin.compose.koinInject
import me.rerere.rikkahub.ui.pages.chat.VoicePhase
import me.rerere.rikkahub.ui.pages.chat.VoiceSessionState
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

@Composable
fun ChatInput(
    state: ChatInputState,
    loading: Boolean,
    settings: Settings,
    hazeState: HazeState,
    onUpdateAssistant: (Assistant) -> Unit,
    enableSearch: Boolean,
    conversationChatModelId: Uuid? = null,
    onUpdateChatModel: (Model) -> Unit,
    onUpdateModelReasoningLevel: (Model, ReasoningLevel) -> Unit,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onMoreClick: () -> Unit,
    onCancelClick: () -> Unit,
    onSendClick: () -> Unit,
    onLongSendClick: () -> Unit,
    modifier: Modifier = Modifier,
    completionProviders: List<ChatCompletionProvider> = emptyList(),
    messageQueue: MessageQueueState = MessageQueueState(),
    onRemoveQueuedMessage: (Uuid) -> Unit = {},
    onBeginEditQueuedMessage: (Uuid) -> QueuedMessage? = { null },
    onFinishEditQueuedMessage: (Uuid, List<UIMessagePart>?) -> Unit = { _, _ -> },
    onResumeMessageQueue: () -> Unit = {},
    onStartVoiceMode: (() -> Unit)? = null,
    voiceState: VoiceSessionState = VoiceSessionState(),
    onStopVoiceMode: () -> Unit = {},
) {
    val toaster = LocalToaster.current
    val assistant = settings.getCurrentAssistant()
    val effectiveChatModel = settings.resolveChatModel(conversationChatModelId, assistant.id)

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // A single horizontal bar: it stays a pill while it only carries a one-line draft, and relaxes
    // into the regular input box the moment it is focused or grows (attachments, multi-line text).
    var isFocused by remember { mutableStateOf(false) }
    var isFullScreen by remember { mutableStateOf(false) }

    fun sendMessage() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading && state.isEmpty()) onCancelClick() else onSendClick()
    }

    fun sendMessageWithoutAnswer() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading && state.isEmpty()) onCancelClick() else onLongSendClick()
    }

    val asr = LocalASRState.current
    val asrState by asr.state.collectAsState()
    val hapticFeedback = LocalHapticFeedback.current
    val soundEffectPlayer: SoundEffectPlayer = koinInject()
    LaunchedEffect(Unit) {
        soundEffectPlayer.preload(R.raw.asr_start, R.raw.asr_stop)
    }
    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)
    var asrBaseText by remember { mutableStateOf("") }
    LaunchedEffect(asrState.status) {
        when (asrState.status) {
            ASRStatus.Listening -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                soundEffectPlayer.play(R.raw.asr_start)
            }

            ASRStatus.Stopping -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                soundEffectPlayer.play(R.raw.asr_stop)
            }

            else -> {}
        }
    }
    LaunchedEffect(asrState.errorMessage) {
        asrState.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            toaster.show(message = message, type = ToastType.Error)
        }
    }

    val onAsrClick: () -> Unit = {
        when (asrState.status) {
            ASRStatus.Listening -> asr.stop()
            ASRStatus.Idle, ASRStatus.Error -> {
                if (!asrPermission.allRequiredPermissionsGranted) {
                    asrPermission.requestPermissions()
                } else {
                    asrBaseText = state.textContent.text.toString()
                    asr.start { transcript ->
                        val spacer =
                            if (asrBaseText.isBlank() || transcript.isBlank()) "" else " "
                        state.setMessageText(asrBaseText + spacer + transcript)
                    }
                }
            }

            ASRStatus.Connecting, ASRStatus.Stopping -> {}
        }
    }

    // Collapsed = a one-line pill. It opens into a taller multi-line box as soon as it is focused,
    // carries attachments, or holds a multi-line draft.
    val naturallyExpanded = isFocused ||
        state.messageContent.isNotEmpty() ||
        state.textContent.text.contains('\n')
    // Clean layout (DisplaySetting.cleanLayout, off by default) keeps the open two-row box all the
    // time: full-width field on top, tools underneath, "+" moved to the right next to send.
    val cleanLayout = settings.displaySetting.cleanLayout
    val expanded = cleanLayout || naturallyExpanded
    // Animate the corner along with the layout change below, so the pill does not snap between
    // its 28dp and 16dp radii while the bar is growing.
    val containerCorner by animateDpAsState(
        targetValue = if (expanded) 16.dp else 28.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "chatInputCorner",
    )
    val containerShape = RoundedCornerShape(containerCorner)

    Surface(
        color = Color.Transparent,
    ) {
        Column(
            modifier = modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MessageQueuePanel(
                state = messageQueue,
                onRemove = onRemoveQueuedMessage,
                onBeginEdit = onBeginEditQueuedMessage,
                onFinishEdit = onFinishEditQueuedMessage,
                onResume = onResumeMessageQueue,
            )
            // Fixed glass — the bar is the app's primary control surface, so it keeps the glass
            // material regardless of the blur preference (same recipe as the drawer's cards).
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(containerShape)
                    .hazeGlass(
                        input = HazeInput.Sources(hazeState),
                        style = rikkaGlassStyle(containerShape, blurRadius = 12.dp, depth = 0.4f),
                    ),
                shape = containerShape,
                tonalElevation = 0.dp,
                color = Color.Transparent,
            ) {
                Column(
                    // Extra inset so the round buttons stay clear of the capsule's edge; the
                    // middle (text) column gives up that space instead.
                    modifier = if (cleanLayout) {
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    } else {
                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    },
                    verticalArrangement = Arrangement.spacedBy(if (cleanLayout) 6.dp else 2.dp)
                ) {
                    if (voiceState.phase != VoicePhase.Off) {
                        VoiceModeRow(
                            state = voiceState,
                            onStop = onStopVoiceMode,
                            onRetry = { onStartVoiceMode?.invoke() },
                        )
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    if (state.messageContent.isNotEmpty()) {
                        MediaFileInputRow(state = state)
                    }

                    // One composition, two arrangements: as a pill the buttons flank the one-line
                    // field, and once the bar opens (focus / attachments / multi-line draft) the
                    // field takes the full width with the buttons on a row underneath it, the way
                    // the input was laid out before it became a pill.
                    //
                    // The two arrangements go through ONE custom layout instead of an if/else on
                    // purpose: an if/else moves the field to a different composition slot, and a
                    // moved field is re-created, which drops the focus that had just arrived - the
                    // bar flashed open and shut the moment it was tapped.
                    val moreButton: @Composable () -> Unit = {
                        // Attachments / tools / workspace panel (the bar's "drawer").
                        ActionIconButton(onClick = onMoreClick) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = stringResource(R.string.more_options)
                            )
                        }
                    }
                    ChatInputBar(
                        expanded = expanded,
                        modifier = Modifier.fillMaxWidth(),
                        left = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (!cleanLayout) moreButton()

                                // Model, with thinking depth on the same entry. The name is capped so a
                                // long id cannot push the field off the pill; the sheet shows the full one.
                                ModelReasoningChip(
                                    settings = settings,
                                    assistant = assistant,
                                    conversationChatModelId = conversationChatModelId,
                                    expanded = expanded,
                                    onUpdateChatModel = onUpdateChatModel,
                                    onUpdateModelReasoningLevel = onUpdateModelReasoningLevel,
                                )
                            }
                        },
                        center = {
                            TextInputRow(
                                state = state,
                                completionProviders = completionProviders,
                                onSendMessage = { sendMessage() },
                                onFocusChanged = { isFocused = it },
                                // The pill is one line. A wrapping placeholder makes the whole bar
                                // two rows tall, which is what "在想什么？" did once the model name
                                // sat beside it. Multiline returns as soon as the bar opens.
                                singleLine = !expanded,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        right = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                // Full screen only once the bar is open. The collapsed pill has no room,
                                // and a double-tap on the field would steal word selection.
                                if (naturallyExpanded) {
                                    ActionIconButton(onClick = { isFullScreen = true }) {
                                        Icon(
                                            imageVector = HugeIcons.Fullscreen,
                                            contentDescription = stringResource(R.string.text_area_fullscreen_edit),
                                        )
                                    }
                                }

                                SearchPickerButton(
                                    enableSearch = enableSearch,
                                    settings = settings,
                                    model = effectiveChatModel,
                                    onUpdateSearchMode = onUpdateSearchMode,
                                    onUpdateSearchService = onUpdateSearchService,
                                )

                                if (cleanLayout) moreButton()

                                // One slot, two faces: the send arrow while there is something to send, the
                                // microphone when the draft is empty and speech input is available. While
                                // recording the microphone stays (the transcript itself would otherwise
                                // flip the slot to "send" and strand the recording).
                                val voiceAvailable =
                                    !voiceState.isActive && (asrState.isAvailable || asrState.isRecording)
                                when {
                                    asrState.isRecording -> AsrButton(state = asrState, onClick = onAsrClick)

                                    loading && state.isEmpty() -> SendButton(
                                        loading = true,
                                        empty = true,
                                        onClick = { sendMessage() },
                                        onLongClick = { sendMessageWithoutAnswer() },
                                    )

                                    !state.isEmpty() -> SendButton(
                                        loading = loading,
                                        empty = false,
                                        onClick = { sendMessage() },
                                        onLongClick = { sendMessageWithoutAnswer() },
                                    )

                                    voiceAvailable -> AsrButton(state = asrState, onClick = onAsrClick)

                                    else -> SendButton(
                                        loading = false,
                                        empty = true,
                                        onClick = { sendMessage() },
                                        onLongClick = { sendMessageWithoutAnswer() },
                                    )
                                }
                            }
                        },
                    )

                    if (loading) {
                        KeepScreenOn()
                    }
                }
            }
        }
    }

    if (isFullScreen) {
        FullScreenEditor(state = state) {
            isFullScreen = false
        }
    }
}

private val modelDateSuffix = Regex("""[-_](\d{8}|\d{4}-\d{2}-\d{2})$""")

/** Drop a trailing build date (`-20250929`, `-2024-08-06`). Never invents a nickname. */
internal fun compactModelLabel(name: String): String =
    name.replace(modelDateSuffix, "").ifBlank { name }

/**
 * One entry for the chat model and, when the model can reason, its thinking depth.
 * Collapsed, only the icon: the name was what wrapped a five-character hint onto two lines.
 * The name comes back once the bar opens and the field has its own row. The sheet is the model
 * list with the depth slider on top.
 */
@Composable
private fun ModelReasoningChip(
    settings: Settings,
    assistant: Assistant,
    conversationChatModelId: Uuid? = null,
    expanded: Boolean,
    onUpdateChatModel: (Model) -> Unit,
    onUpdateModelReasoningLevel: (Model, ReasoningLevel) -> Unit,
) {
    val model = settings.resolveChatModel(conversationChatModelId, assistant.id)
    val effectiveModelId = model?.id
    val showReasoning = model?.abilities?.contains(ModelAbility.REASONING) == true
    val level = model?.reasoningLevel ?: assistant.reasoningLevel
    val modelListState = rememberModelListState(
        modelId = effectiveModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val fullName = model?.displayName?.takeIf { it.isNotBlank() }
        ?: model?.modelId?.takeIf { it.isNotBlank() }
    val label = fullName?.let(::compactModelLabel)?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.model_list_select_model)

    // height() is the outer constraint, so Material's 48dp minimum touch target cannot grow the pill.
    Surface(
        onClick = { modelListState.open() },
        modifier = Modifier.height(30.dp),
        shape = CircleShape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (model != null) {
                    AutoAIIconFill(
                        name = model.modelId.ifBlank { model.displayName },
                        modifier = Modifier.size(20.dp),
                        color = Color.Transparent,
                        iconPadding = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.Brain02,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showReasoning && level.isEnabled) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.primary,
                        ) {
                            ReasoningIcon(level = level, modifier = Modifier.size(10.dp))
                        }
                    }
                }
            }
            // Name only on the open bar. On the pill it stole the hint's line.
            if (expanded) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 128.dp),
                )
            }
        }
    }

    ModelListSheet(
        state = modelListState,
        onSelect = onUpdateChatModel,
        header = {
            AgentControlCard(
                reasoningLevel = level,
                showReasoning = showReasoning,
                onUpdateReasoningLevel = {
                    if (model != null) {
                        onUpdateModelReasoningLevel(model, it)
                    }
                },
            )
        },
    )
}

/**
 * Places the bar's three slots ([left], [center], [right]) either on one line - the buttons
 * flanking the field - or on two storeys with the field on top and the buttons underneath.
 *
 * This is a [Layout] rather than two branches of an `if`: switching branches would put the text
 * field in a different composition slot, re-creating it and losing the focus that just arrived,
 * so tapping the bar made it flash open and immediately close again.
 */
@Composable
private fun ChatInputBar(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    left: @Composable () -> Unit,
    center: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    // The two arrangements differ in exactly two things: how wide the field is, and which row the
    // button pair sits on. Animating between them means interpolating just those, and every child
    // stays in the same composition slot, so the focus that triggered the switch survives it (an
    // AnimatedContent / AnimatedVisibility wrapper would recreate the field and drop the focus —
    // the bug this layout exists to avoid).
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "chatInputBarExpanded",
    )
    Layout(modifier = modifier, content = { left(); center(); right() }) { measurables, constraints ->
        val gap = 4.dp.roundToPx()
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, maxWidth = width)
        val leftPlaceable = measurables[0].measure(loose)
        val rightPlaceable = measurables[2].measure(loose)

        val p = progress.coerceIn(0f, 1f)
        val collapsedCenterWidth =
            (width - leftPlaceable.width - rightPlaceable.width - gap * 2).coerceAtLeast(0)
        val centerWidth = lerp(collapsedCenterWidth.toFloat(), width.toFloat(), p).roundToInt()
        val centerPlaceable = measurables[1].measure(
            constraints.copy(minWidth = centerWidth, maxWidth = centerWidth)
        )

        val actionHeight = maxOf(leftPlaceable.height, rightPlaceable.height)
        val collapsedHeight =
            maxOf(leftPlaceable.height, centerPlaceable.height, rightPlaceable.height)
        val expandedHeight = centerPlaceable.height + gap + actionHeight
        val height = lerp(collapsedHeight.toFloat(), expandedHeight.toFloat(), p).roundToInt()

        layout(width, height) {
            // Collapsed: one row, everything centred on it. Expanded: the field owns the top row
            // and the pair drops to the row below, each centred on that row's height.
            val centerY = lerp((collapsedHeight - centerPlaceable.height) / 2f, 0f, p)
            val leftY = lerp(
                (collapsedHeight - leftPlaceable.height) / 2f,
                (centerPlaceable.height + gap + (actionHeight - leftPlaceable.height) / 2f).toFloat(),
                p,
            )
            val rightY = lerp(
                (collapsedHeight - rightPlaceable.height) / 2f,
                (centerPlaceable.height + gap + (actionHeight - rightPlaceable.height) / 2f).toFloat(),
                p,
            )
            val centerX = lerp((leftPlaceable.width + gap).toFloat(), 0f, p)
            centerPlaceable.place(centerX.roundToInt(), centerY.roundToInt())
            leftPlaceable.place(0, leftY.roundToInt())
            rightPlaceable.place(width - rightPlaceable.width, rightY.roundToInt())
        }
    }
}

@Composable
private fun SendButton(
    loading: Boolean,
    empty: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showStop = loading && empty
    val containerColor = when {
        showStop -> MaterialTheme.colorScheme.errorContainer
        empty -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.primary
    }
    val contentColor = when {
        showStop -> MaterialTheme.colorScheme.onErrorContainer
        empty -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.onPrimary
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(36.dp)
            .testTag("chat_send_button")
            .clip(CircleShape)
            .combinedClickable(
                enabled = showStop || !empty,
                onClick = onClick,
                onLongClick = onLongClick,
            )
    ) {
        Surface(
            modifier = Modifier.size(26.dp),
            shape = CircleShape,
            color = containerColor,
            content = {},
        )
        Icon(
            imageVector = if (showStop) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
            contentDescription = stringResource(if (showStop) R.string.stop else R.string.send),
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun ActionIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        tonalElevation = 0.dp,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

/** Short prompts cycled through for the empty bar, picked at random on every composition. */
private val chatInputHints = listOf(
    R.string.chat_input_hint_1,
    R.string.chat_input_hint_2,
    R.string.chat_input_hint_3,
    R.string.chat_input_hint_4,
    R.string.chat_input_hint_5,
)

@Composable
private fun rememberChatInputHint(): Int = remember { chatInputHints.random() }

@Composable
private fun TextInputRow(
    state: ChatInputState,
    completionProviders: List<ChatCompletionProvider>,
    onSendMessage: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
    singleLine: Boolean = false,
) {
    val settings = LocalSettings.current
    val assistant = settings.getCurrentAssistant()
    val quickMessages = remember(settings.quickMessages, assistant.quickMessageIds) {
        settings.getQuickMessagesOfAssistant(assistant)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (state.isEditing()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.editing))
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel_edit),
                        modifier = Modifier.clickable { state.clearInput() }
                    )
                }
            }
        }

        var isFocused by remember { mutableStateOf(false) }
        var completionList by remember { mutableStateOf<ChatCompletionList?>(null) }
        // The draft only fades at the top/bottom edges once it actually wraps onto several lines —
        // a single line keeps full contrast.
        val multilineDraft = state.textContent.text.contains('\n')
        val receiveContentListener = rememberPasteLongTextAsFileListener(state)

        LaunchedEffect(completionProviders, isFocused) {
            if (!isFocused || completionProviders.isEmpty()) {
                completionList = null
                return@LaunchedEffect
            }

            snapshotFlow {
                ChatCompletionContext(
                    text = state.textContent.text.toString(),
                    selection = state.textContent.selection,
                )
            }.collectLatest { context ->
                val lists = completionProviders.mapNotNull { provider ->
                    try {
                        provider.complete(context)
                            ?.takeIf { it.items.isNotEmpty() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                val primary = lists.firstOrNull()
                completionList = primary?.let { list ->
                    val mergedItems = lists
                        .filter { it.replacementRange == list.replacementRange }
                        .flatMap { it.items }
                        .distinctBy { it.label to it.insertText }
                        .sortedWith(
                            compareByDescending<ChatCompletionItem> { it.sortScore }
                                .thenBy { it.label.length }
                                .thenBy { it.label.lowercase() }
                        )
                        .take(8)
                    list.copy(items = mergedItems)
                }
            }
        }

        completionList?.takeIf { it.items.isNotEmpty() }?.let { list ->
            CompletionPopup(
                completionList = list,
                onItemClick = { item ->
                    state.applyCompletion(list.replacementRange, item)
                    completionList = null
                },
            )
        }

        val hint = rememberChatInputHint()

        TextField(
            state = state.textContent,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_input")
                .then(
                    if (multilineDraft) Modifier
                        // An offscreen layer keeps the DstIn below from punching through whatever
                        // was already drawn behind the field.
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            // Fade the first/last visible line into the bar so a long draft reads as
                            // scrollable rather than clipped.
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.14f to Color.Black,
                                    0.86f to Color.Black,
                                    1f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        } else Modifier
                )
                .contentReceiver(receiveContentListener)
                .onFocusChanged {
                    isFocused = it.isFocused
                    onFocusChanged(it.isFocused)
                },
            shape = MaterialTheme.shapes.largeIncreased,
            placeholder = {
                Text(stringResource(hint))
            },
            lineLimits = if (singleLine) {
                TextFieldLineLimits.SingleLine
            } else {
                TextFieldLineLimits.MultiLine(maxHeightInLines = 5)
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                // Only show the Send action when there is something to send; otherwise let the
                // IME use its default (newline) so the user can start a new line.
                imeAction = if (settings.displaySetting.sendOnEnter && !state.isEmpty()) ImeAction.Send else ImeAction.Default
            ),
            // null when sendOnEnter is off — a non-null handler (even an empty one) tells the IME
            // the Enter key is an IME action, which blocks newline insertion in multi-line mode.
            onKeyboardAction = if (settings.displaySetting.sendOnEnter) {
                {
                    if (!state.isEmpty()) {
                        onSendMessage()
                    }
                }
            } else null,
            colors = TextFieldDefaults.colors().copy(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            leadingIcon = if (quickMessages.isNotEmpty()) {
                {
                    QuickMessageButton(quickMessages = quickMessages, state = state)
                }
            } else null,
        )
    }
}

// Note: keyboard clipboard-chip insertions go through commitText, which bypasses
// Compose's content receiver; only long-press Paste and Ctrl+V trigger this listener.
@Composable
private fun rememberPasteLongTextAsFileListener(state: ChatInputState): ReceiveContentListener {
    val settings = LocalSettings.current
    val filesManager: FilesManager = koinInject()
    return remember(
        settings.displaySetting.pasteLongTextAsFile, settings.displaySetting.pasteLongTextThreshold
    ) {
        ReceiveContentListener { transferableContent ->
            when {
                transferableContent.hasMediaType(MediaType.Image) -> {
                    transferableContent.consume { item ->
                        val uri = item.uri
                        if (uri != null) {
                            state.addImages(
                                filesManager.createChatFilesByContents(
                                    listOf(uri)
                                )
                            )
                        }
                        uri != null
                    }
                }

                settings.displaySetting.pasteLongTextAsFile && transferableContent.hasMediaType(MediaType.Text) -> {
                    transferableContent.consume { item ->
                        val text = item.text?.toString()
                        if (text != null && text.length > settings.displaySetting.pasteLongTextThreshold) {
                            val document = filesManager.createChatTextFile(text)
                            state.addFiles(listOf(document))
                            true
                        } else {
                            false
                        }
                    }
                }

                else -> transferableContent
            }
        }
    }
}

@Composable
private fun CompletionPopup(
    completionList: ChatCompletionList,
    onItemClick: (ChatCompletionItem) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            items(
                items = completionList.items,
                key = { item -> "${item.label}:${item.insertText}" },
            ) { item ->
                Surface(
                    onClick = { onItemClick(item) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item.icon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.detail?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ChatInputState.applyCompletion(
    replacementRange: TextRange,
    item: ChatCompletionItem,
) {
    val textLength = textContent.text.length
    val start = replacementRange.min.coerceIn(0, textLength)
    val end = replacementRange.max.coerceIn(start, textLength)
    textContent.edit {
        replace(start, end, item.insertText)
        selection = TextRange(start + item.insertText.length)
    }
}

@Composable
private fun QuickMessageButton(
    quickMessages: List<QuickMessage>,
    state: ChatInputState,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            expanded = !expanded
        }) {
        Icon(HugeIcons.Zap, null)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 200.dp, max = 360.dp)
        ) {
            quickMessages.forEach { quickMessage ->
                Surface(
                    onClick = {
                        state.appendText(quickMessage.content)
                        expanded = false
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = quickMessage.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenEditor(
    state: ChatInputState, onDone: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = {
            onDone()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                onDone()
                            }) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                    val receiveContentListener = rememberPasteLongTextAsFileListener(state)
                    val hint = rememberChatInputHint()

                    TextField(
                        state = state.textContent,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .fillMaxSize()
                            .contentReceiver(receiveContentListener),
                        shape = RoundedCornerShape(32.dp),
                        placeholder = {
                            Text(stringResource(hint))
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}
