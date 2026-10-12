package me.rerere.rikkahub.ui.pages.quickchat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.FloatingBallService
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import me.rerere.rikkahub.ui.components.ui.AssistantAvatar
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.rememberCustomAsrState
import me.rerere.rikkahub.ui.hooks.rememberCustomTtsState
import org.koin.compose.koinInject

private const val LAST_CONVERSATION_KEY = "lastConversationId"

/** Full panel height, as a fraction of the available screen height. */
private const val PANEL_HEIGHT_FRACTION = 0.62f

/** The ball sits this far down the panel — half-way, so the panel unfolds evenly around it. */
private const val BALL_ANCHOR_FRACTION = 0.50f

/**
 * The panel shrinks its message list when a low ball would push it off the bottom, but never
 * below this fraction of its full height — past that it just rests on the bottom edge instead.
 */
private const val PANEL_MIN_HEIGHT_FRACTION = 0.75f

/** How long the panel takes to unfold out of the ball / fold back into it. */
private const val PANEL_OPEN_MS = 260
private const val PANEL_CLOSE_MS = 200

/**
 * The panel scales up from this factor (towards 1) as it appears, with its transform origin pinned
 * to the ball, so it reads as growing out of the ball. Closing plays the same thing backwards.
 */
private const val PANEL_SCALE_FROM = 0.86f

/**
 * Reuse the conversation the user was last in; fall back to a fresh one on first use. The main
 * chat page writes `lastConversationId` (ChatVM) so this stays in sync with normal use.
 */
@OptIn(ExperimentalUuidApi::class)
private fun initialConversationId(context: Context): Uuid {
    val saved = context.readStringPreference(LAST_CONVERSATION_KEY)
    return saved?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: Uuid.random()
}

/**
 * The panel itself: latest messages of the active conversation, a quick-action for screen
 * automation, and a text/voice composer.
 *
 * Sending posts straight through [ChatService.sendMessage] — the same path as the in-app chat,
 * so approvals, cost guards and token accounting apply unchanged. The screen-action button sends
 * and dismisses the panel (fold-back first, then finish) so the agent can see and drive the app
 * underneath; a plain send keeps the panel open so the reply streams in view.
 *
 * The panel is not pinned to the bottom: it is placed so the ball that opened it lands at
 * [BALL_ANCHOR_FRACTION] of the panel's height, mirrored horizontally when the ball is on the
 * left so the controls sit under the user's thumb. When the ball is too low for the full panel to
 * fit, the message area gives up space (at most 25%) before the panel rests on the bottom edge.
 *
 * The panel unfolds out of the ball and folds back into it: it scales up around a transform origin
 * pinned to the ball's own spot on its edge (so the animation reads as the ball becoming the
 * panel), and every dismiss — tap outside, close button, back, opening the full chat, the
 * screen-action shortcut — plays that fold first and only then calls [onClose] to finish.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun QuickChatPanel(
    holdToTalk: Boolean,
    mirror: Boolean,
    ballCenterY: Int,
    onUnfoldStart: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 0 = folded away into the ball, 1 = fully unfolded. The panel grows out of the ball on the
    // way in and folds back into it before the activity finishes, so the ball can pop back out of
    // the same spot (FloatingBallService plays that half of the morph).
    val unfold = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Only now is the panel really about to appear, so this is the moment the ball should be
        // told to fold itself away — timing the hand-over from the activity's onCreate instead
        // would have the ball vanish into an empty screen on a slow start.
        onUnfoldStart()
        unfold.animateTo(1f, tween(PANEL_OPEN_MS, easing = FastOutSlowInEasing))
    }

    /** Plays the fold-back, then finishes. Every dismiss path goes through here exactly once. */
    fun requestClose(after: () -> Unit = {}) {
        if (closing) return
        closing = true
        scope.launch {
            unfold.animateTo(0f, tween(PANEL_CLOSE_MS, easing = FastOutLinearInEasing))
            // Hand over only once we have folded away, so the switch is not stealing the show.
            after()
            onClose()
        }
    }
    BackHandler { requestClose() }

    val chatService = koinInject<ChatService>()
    val settingsStore = koinInject<SettingsStore>()

    var conversationId by remember { mutableStateOf(initialConversationId(context)) }
    val conversation by chatService
        .getConversationFlow(conversationId)
        .collectAsStateWithLifecycle()
    val processingStatus by chatService
        .getProcessingStatusFlow(conversationId)
        .collectAsStateWithLifecycle()
    val isGenerating = processingStatus != null

    LaunchedEffect(conversationId) {
        chatService.initializeConversation(conversationId)
    }

    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val hasAsrProvider = settings.getSelectedASRProvider() != null

    var input by remember { mutableStateOf("") }
    val asrState = rememberCustomAsrState()
    val asr by asrState.state.collectAsStateWithLifecycle()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Hold-to-talk from the ball: the permission dialog can outlive the gesture, so only
            // start recording if the ball is still held.
            if (!holdToTalk || FloatingBallService.voiceHoldActive.value) {
                asrState.start { input = it }
            }
        } else {
            Toast.makeText(context, R.string.quick_chat_mic_permission, Toast.LENGTH_SHORT).show()
        }
    }

    fun startVoice() {
        if (!hasAsrProvider) {
            Toast.makeText(context, R.string.quick_chat_no_asr, Toast.LENGTH_SHORT).show()
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            asrState.start { input = it }
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Hold-to-talk from the ball (its long-press): record while the ball is held and stop the
    // instant it is released. Reading the service's flag directly — same process — means a release
    // that lands before this panel has even finished coming up is not missed, which an intent
    // hand-off could not guarantee.
    val voiceHeld by FloatingBallService.voiceHoldActive.collectAsStateWithLifecycle()
    if (holdToTalk) {
        LaunchedEffect(voiceHeld) {
            if (voiceHeld) {
                if (!asr.isRecording) startVoice()
            } else if (asr.isRecording) {
                asrState.stop()
            }
        }
    }

    val screenPrompt = stringResource(R.string.quick_chat_look_screen_prompt)
    val fallbackTitle = stringResource(R.string.quick_chat_title)

    fun submit(text: String, closeAfterSend: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        asrState.stop()
        chatService.sendMessage(conversationId, listOf(UIMessagePart.Text(trimmed)))
        input = ""
        if (closeAfterSend) requestClose()
    }

    // The interactive controls, split into individual buttons so mirroring is an explicit,
    // predictable re-order (no RTL layout direction, which would also right-align the text field).
    val newButton: @Composable () -> Unit = {
        IconButton(onClick = {
            asrState.stop()
            input = ""
            conversationId = Uuid.random()
        }) {
            Icon(HugeIcons.PlusSign, stringResource(R.string.quick_chat_new))
        }
    }
    val openFullButton: @Composable () -> Unit = {
        IconButton(onClick = {
            val intent = Intent(context, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversationId", conversationId.toString())
            }
            // Fold away first: switching to the main task would otherwise cover the panel and
            // the fold would never be seen.
            requestClose { context.startActivity(intent) }
        }) {
            Icon(HugeIcons.ArrowRight01, stringResource(R.string.quick_chat_open_full))
        }
    }
    val closeButton: @Composable () -> Unit = {
        IconButton(onClick = { requestClose() }) {
            Icon(HugeIcons.Cancel01, stringResource(R.string.quick_chat_close))
        }
    }
    val micButton: @Composable () -> Unit = {
        IconButton(onClick = {
            if (asr.isRecording) asrState.stop() else startVoice()
        }) {
            Icon(HugeIcons.Mic01, stringResource(R.string.quick_chat_voice))
        }
    }
    val sendButton: @Composable () -> Unit = {
        if (isGenerating) {
            IconButton(onClick = {
                scope.launch { chatService.stopGeneration(conversationId) }
            }) {
                Icon(HugeIcons.Stop, stringResource(R.string.quick_chat_stop))
            }
        } else {
            FilledIconButton(
                onClick = { submit(input, false) },
                enabled = input.isNotBlank(),
            ) {
                Icon(HugeIcons.ArrowUp01, stringResource(R.string.quick_chat_send))
            }
        }
    }

    val messages = conversation.currentMessages.filter {
        it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT
    }
    val listState = rememberLazyListState()
    LaunchedEffect(conversationId, messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    // A streaming reply grows in place without the message count changing, so follow it too —
    // instantly, so the view does not drift behind the text as it arrives.
    val streamTail = messages.lastOrNull()?.parts?.lastOrNull()?.hashCode() ?: 0
    LaunchedEffect(streamTail) {
        if (isGenerating && messages.isNotEmpty()) {
            runCatching { listState.scrollToItem(messages.lastIndex) }
        }
    }

    // Read the latest assistant reply aloud through the app's configured TTS provider.
    val tts = rememberCustomTtsState()
    val ttsSpeaking by tts.isSpeaking.collectAsStateWithLifecycle()
    val ttsAvailable by tts.isAvailable.collectAsStateWithLifecycle()
    val latestAssistantText = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.toText()?.trim().orEmpty()
    fun toggleReadAloud() {
        // isSpeaking read straight off the StateFlow so a click always sees the live value.
        if (tts.isSpeaking.value) {
            tts.stop()
            return
        }
        if (!ttsAvailable) {
            Toast.makeText(context, R.string.quick_chat_no_tts, Toast.LENGTH_SHORT).show()
            return
        }
        if (latestAssistantText.isBlank()) {
            Toast.makeText(context, R.string.quick_chat_nothing_to_read, Toast.LENGTH_SHORT).show()
            return
        }
        tts.speak(latestAssistantText)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        val density = LocalDensity.current
        val fullHeight = maxHeight * PANEL_HEIGHT_FRACTION
        val minHeight = fullHeight * PANEL_MIN_HEIGHT_FRACTION
        // Place the panel so the ball lands at BALL_ANCHOR_FRACTION of its height:
        // top = ballY - anchor * height, and we need top + height <= maxHeight.
        val ballY = with(density) { ballCenterY.toDp() }
        val heightForBall = (maxHeight - ballY) / (1f - BALL_ANCHOR_FRACTION)
        val panelHeight = heightForBall.coerceIn(minHeight, fullHeight)
        val panelTop = (ballY - panelHeight * BALL_ANCHOR_FRACTION)
            .coerceAtMost(maxHeight - panelHeight)
            .coerceAtLeast(0.dp)

        // Reserve the status-bar height only when the panel's own top edge actually reaches up
        // under it. Anchored to a lower ball the panel sits well clear of the status bar, and
        // blindly applying the inset there left a dead band across the top of the panel.
        val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        val topInset = (statusBarHeight - panelTop).coerceAtLeast(0.dp)

        // Where the ball sits inside the panel, as a fraction of its height: the point the panel
        // unfolds out of. Normally BALL_ANCHOR_FRACTION; a ball low enough to push the panel onto
        // the bottom edge drags the origin down with it.
        val ballAnchorY = ((ballY - panelTop) / panelHeight).coerceIn(0f, 1f)
        val unfoldOrigin = TransformOrigin(if (mirror) 0f else 1f, ballAnchorY)

        // Tap anywhere off the panel to dismiss it; the app behind shows through the gap.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { requestClose() }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(0, with(density) { panelTop.roundToPx() }) }
                .fillMaxWidth()
                .height(panelHeight)
                // Unfold out of the ball: scale up from the ball's own spot while fading in.
                // Closing runs the same thing backwards (unfold animates back to 0).
                .graphicsLayer {
                    val scale = PANEL_SCALE_FROM + (1f - PANEL_SCALE_FROM) * unfold.value
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = unfoldOrigin
                    alpha = unfold.value
                }
                // Swallow taps on the panel's own surface (padding, empty history) so they do
                // not fall through to the dismiss layer behind it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = topInset + 12.dp,
                        bottom = 12.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mirror) {
                        closeButton()
                        openFullButton()
                        newButton()
                    }
                    val assistant = settings.getAssistantById(conversation.assistantId)
                    if (assistant != null) {
                        AssistantAvatar(
                            assistant = assistant,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            // The assistant leads the header — never an uploaded avatar on its own
                            // (AssistantAvatar falls back to its model's brand icon) — with the
                            // conversation title dropping to a quieter second line, so a glance
                            // tells you who you are talking to.
                            text = assistant?.name?.ifBlank { null } ?: fallbackTitle,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = conversation.title.ifBlank { null }
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!mirror) {
                        newButton()
                        openFullButton()
                        closeButton()
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.quick_chat_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(messages, key = { it.id.toString() }) { message ->
                        MessageSegments(message)
                    }
                }

                if (asr.isRecording) {
                    StatusRow(text = stringResource(R.string.quick_chat_listening))
                } else if (isGenerating) {
                    StatusRow(text = processingStatus.orEmpty())
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (mirror) {
                        QuickActionButton(
                            text = stringResource(
                                if (ttsSpeaking) R.string.quick_chat_tts_stop
                                else R.string.quick_chat_tts_read
                            ),
                            icon = if (ttsSpeaking) HugeIcons.Stop else HugeIcons.VolumeHigh,
                            onClick = { toggleReadAloud() },
                            modifier = Modifier.weight(1f),
                        )
                        QuickActionButton(
                            text = stringResource(R.string.quick_chat_look_screen),
                            icon = HugeIcons.LookTop,
                            onClick = { submit(screenPrompt, closeAfterSend = true) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        QuickActionButton(
                            text = stringResource(R.string.quick_chat_look_screen),
                            icon = HugeIcons.LookTop,
                            onClick = { submit(screenPrompt, closeAfterSend = true) },
                            modifier = Modifier.weight(1f),
                        )
                        QuickActionButton(
                            text = stringResource(
                                if (ttsSpeaking) R.string.quick_chat_tts_stop
                                else R.string.quick_chat_tts_read
                            ),
                            icon = if (ttsSpeaking) HugeIcons.Stop else HugeIcons.VolumeHigh,
                            onClick = { toggleReadAloud() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Row(verticalAlignment = Alignment.Bottom) {
                    if (mirror) {
                        sendButton()
                        micButton()
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.quick_chat_input_hint)) },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit(input, false) }),
                    )
                    if (!mirror) {
                        micButton()
                        sendButton()
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * One message, rendered the way the in-app chat renders it: the parts are split into ordered
 * blocks (see [groupMessageParts]), so each model call's text becomes its own segment instead of
 * one merged wall of text, and thinking/tool runs collapse into a single quiet chip.
 */
@Composable
private fun MessageSegments(message: UIMessage) {
    val isUser = message.role == MessageRole.USER
    // Key on the parts list itself: a key of `size + last-part hash` misses in-place edits to any
    // part that is not the tail (see ChatMessage), which renders a stale snapshot that only
    // catches up when the tail happens to change.
    val blocks = remember(message.parts) { message.parts.groupMessageParts() }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        blocks.forEach { block ->
            when (block) {
                is MessagePartBlock.ThinkingBlock -> {
                    val label = thinkingLabel(block)
                    if (label != null) SegmentChip(label)
                }

                is MessagePartBlock.ContentBlock -> when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        val text = part.text.trim()
                        if (text.isNotEmpty()) SegmentBubble(text, isUser)
                    }

                    else -> SegmentChip(stringResource(R.string.quick_chat_segment_attachment))
                }
            }
        }
    }
}

@Composable
private fun thinkingLabel(block: MessagePartBlock.ThinkingBlock): String? {
    val tools = block.steps.count {
        it is ThinkingStep.ToolStep || it is ThinkingStep.ServerToolStep
    }
    return when {
        tools > 0 -> stringResource(R.string.quick_chat_segment_tools, tools)
        block.steps.any { it is ThinkingStep.ReasoningStep } ->
            stringResource(R.string.quick_chat_segment_thinking)

        else -> null
    }
}

@Composable
private fun SegmentBubble(text: String, isUser: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(0.92f),
        shape = RoundedCornerShape(16.dp),
        color = if (isUser) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** A quiet, non-bubble line standing in for thinking/tool steps or a non-text attachment. */
@Composable
private fun SegmentChip(label: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun QuickActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(onClick = onClick, modifier = modifier) {
        Icon(icon, null)
        Text(
            text = text,
            modifier = Modifier.padding(start = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
