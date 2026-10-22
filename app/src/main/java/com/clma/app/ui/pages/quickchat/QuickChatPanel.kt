package com.clma.app.ui.pages.quickchat

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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.Wrench01
import com.clma.app.R
import com.clma.app.RouteActivity
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.datastore.getAssistantById
import com.clma.app.data.datastore.getSelectedASRProvider
import com.clma.app.service.ChatService
import com.clma.app.ui.components.message.MessagePartBlock
import com.clma.app.ui.components.message.ThinkingStep
import com.clma.app.ui.components.message.groupMessageParts
import com.clma.app.ui.context.LocalSettings
import com.clma.app.ui.components.richtext.MarkdownBlock
import com.clma.app.ui.components.ui.AssistantAvatar
import com.clma.app.ui.hooks.readStringPreference
import com.clma.app.ui.hooks.rememberCustomAsrState
import com.clma.app.ui.hooks.rememberCustomTtsState
import org.koin.compose.koinInject

private const val LAST_CONVERSATION_KEY = "lastConversationId"

/** Full panel height, as a fraction of the available screen height. */
private const val PANEL_HEIGHT_FRACTION = 0.72f

/** The edge bar sits this far down the panel — half-way, so the panel unfolds evenly around it. */
private const val BALL_ANCHOR_FRACTION = 0.50f

/**
 * The panel shrinks its message list when a low edge bar would push it off the bottom, but never
 * below this fraction of its full height — past that it just rests on the bottom edge instead.
 */
private const val PANEL_MIN_HEIGHT_FRACTION = 0.82f

/** How long the panel takes to unfold out of the edge bar / fold back into it. */
private const val PANEL_OPEN_MS = 260
private const val PANEL_CLOSE_MS = 200

/**
 * The panel scales up from this factor (towards 1) as it appears, with its transform origin pinned
 * to the edge bar, so it reads as growing out of the edge bar. Closing plays the same thing backwards.
 */
private const val PANEL_SCALE_FROM = 0.86f

/** Corner radius of the panel itself — soft enough to read as a floating pane, not a card. */
private val PANEL_SHAPE = RoundedCornerShape(28.dp)


// Panel tint/alpha per theme and the round button chrome live in QuickChatPanelStyle.kt.

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
 * The panel is not pinned to the bottom: it is placed so the edge bar that opened it lands at
 * [BALL_ANCHOR_FRACTION] of the panel's height, mirrored horizontally when the bar is on the
 * left so the controls sit under the user's thumb. When the edge bar is too low for the full panel to
 * fit, the message area gives up space (at most 25%) before the panel rests on the bottom edge.
 *
 * The panel unfolds out of the edge bar and folds back into it: it scales up around a transform origin
 * pinned to the bar's own spot on its edge (so the animation reads as the bar becoming the
 * panel), and every dismiss — tap outside, close button, back, opening the full chat, the
 * screen-action shortcut — plays that fold first and only then calls [onClose] to finish.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun QuickChatPanel(
    mirror: Boolean,
    ballCenterY: Int,
    blurredBackdrop: Boolean,
    onUnfoldStart: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 0 = folded away into the edge bar, 1 = fully unfolded. The panel grows out of the bar's spot
    // on the way in and folds back into it before the activity finishes; the bar itself just hides
    // while the panel is up and shows again afterwards.
    val unfold = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Only now is the panel really about to appear, so this is the moment the edge bar should be
        // told to fold itself away — timing the hand-over from the activity's onCreate instead
        // would have the bar vanish into an empty screen on a slow start.
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
            asrState.start { input = it }
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

    // --- messages + speech ------------------------------------------------------------------
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

    // --- controls ---------------------------------------------------------------------------
    // Every control is its own lambda and mirroring is an explicit re-order of whole clusters
    // (never an RTL layout direction, which would also right-align the text field). An edge bar parked
    // on the left edge therefore puts the header buttons, the composer's mic/send and the two
    // shortcut icons all on the left; a right-edge bar mirrors the lot.
    val controlSize = 40.dp
    val panelStyle = rememberQuickChatPanelStyle(blurredBackdrop)
    val panelShape = PANEL_SHAPE
    val controlIcon = 20.dp
    val newButton: @Composable () -> Unit = {
        PanelRoundButton(
            style = panelStyle,
            onClick = {
                asrState.stop()
                input = ""
                conversationId = Uuid.random()
            },
            modifier = Modifier.size(controlSize),
        ) {
            Icon(
                imageVector = HugeIcons.PlusSign,
                contentDescription = stringResource(R.string.quick_chat_new),
                modifier = Modifier.size(controlIcon),
            )
        }
    }
    val openFullButton: @Composable () -> Unit = {
        PanelRoundButton(
            style = panelStyle,
            onClick = {
                val intent = Intent(context, RouteActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("conversationId", conversationId.toString())
                }
                // Fold away first: switching to the main task would otherwise cover the panel and
                // the fold would never be seen.
                requestClose { context.startActivity(intent) }
            },
            modifier = Modifier.size(controlSize),
        ) {
            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = stringResource(R.string.quick_chat_open_full),
                modifier = Modifier.size(controlIcon),
            )
        }
    }
    val closeButton: @Composable () -> Unit = {
        PanelRoundButton(
            onClick = { requestClose() },
            style = panelStyle,
            modifier = Modifier.size(controlSize),
        ) {
            Icon(
                imageVector = HugeIcons.Cancel01,
                contentDescription = stringResource(R.string.quick_chat_close),
                modifier = Modifier.size(controlIcon),
            )
        }
    }
    val voiceButton: @Composable () -> Unit = {
        PanelRoundButton(
            style = panelStyle,
            onClick = { if (asr.isRecording) asrState.stop() else startVoice() },
            modifier = Modifier.size(controlSize),
        ) {
            Icon(
                imageVector = if (asr.isRecording) HugeIcons.Stop else HugeIcons.Mic01,
                contentDescription = stringResource(R.string.quick_chat_voice),
                modifier = Modifier.size(controlIcon),
                tint = if (asr.isRecording) MaterialTheme.colorScheme.error
                else LocalContentColor.current,
            )
        }
    }
    val sendButton: @Composable () -> Unit = {
        if (isGenerating) {
            PanelRoundButton(
                style = panelStyle,
                onClick = { scope.launch { chatService.stopGeneration(conversationId) } },
                modifier = Modifier.size(controlSize),
            ) {
                Icon(
                    imageVector = HugeIcons.Stop,
                    contentDescription = stringResource(R.string.quick_chat_stop),
                    modifier = Modifier.size(controlIcon),
                )
            }
        } else {
            PanelRoundButton(
                onClick = { submit(input, false) },
                style = panelStyle,
                enabled = input.isNotBlank(),
                accent = true,
                modifier = Modifier.size(controlSize),
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUp01,
                    contentDescription = stringResource(R.string.quick_chat_send),
                    modifier = Modifier.size(controlIcon),
                )
            }
        }
    }
    // The two shortcuts that used to own a full-width row of their own. As compact icons inside
    // the composer cluster they cost no vertical space at all, and they still mirror with it.
    val screenButton: @Composable () -> Unit = {
        PanelRoundButton(
            onClick = { submit(screenPrompt, closeAfterSend = true) },
            style = panelStyle,
            accent = true,
            modifier = Modifier.size(controlSize),
        ) {
            Icon(
                imageVector = HugeIcons.LookTop,
                contentDescription = stringResource(R.string.quick_chat_look_screen),
                modifier = Modifier.size(controlIcon),
            )
        }
    }
    val readAloudButton: @Composable () -> Unit = {
        PanelRoundButton(
            onClick = { toggleReadAloud() },
            style = panelStyle,
            enabled = ttsSpeaking || latestAssistantText.isNotBlank(),
            modifier = Modifier.size(controlSize),
        ) {
            Icon(
                imageVector = if (ttsSpeaking) HugeIcons.Stop else HugeIcons.VolumeHigh,
                contentDescription = stringResource(
                    if (ttsSpeaking) R.string.quick_chat_tts_stop else R.string.quick_chat_tts_read
                ),
                modifier = Modifier.size(controlIcon),
            )
        }
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
        // Place the panel so the edge bar lands at BALL_ANCHOR_FRACTION of its height:
        // top = ballY - anchor * height, and we need top + height <= maxHeight.
        val ballY = with(density) { ballCenterY.toDp() }
        val heightForBall = (maxHeight - ballY) / (1f - BALL_ANCHOR_FRACTION)
        val panelHeight = heightForBall.coerceIn(minHeight, fullHeight)
        val panelTop = (ballY - panelHeight * BALL_ANCHOR_FRACTION)
            .coerceAtMost(maxHeight - panelHeight)
            .coerceAtLeast(0.dp)

        // Reserve the status-bar height only when the panel's own top edge actually reaches up
        // under it. Anchored to a lower edge bar the panel sits well clear of the status bar, and
        // blindly applying the inset there left a dead band across the top of the panel.
        val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        val topInset = (statusBarHeight - panelTop).coerceAtLeast(0.dp)

        // Where the edge bar sits inside the panel, as a fraction of its height: the point the panel
        // unfolds out of. Normally BALL_ANCHOR_FRACTION; a bar low enough to push the panel onto
        // the bottom edge drags the origin down with it.
        val ballAnchorY = ((ballY - panelTop) / panelHeight).coerceIn(0f, 1f)
        val unfoldOrigin = TransformOrigin(if (mirror) 0f else 1f, ballAnchorY)

        // Cap a bubble at a fraction of the panel instead of always filling 92% of it: a one-word
        // reply should read as a small pill, not a stretched block with empty space either side.
        val bubbleMaxWidth = (maxWidth - 32.dp) * 0.9f

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
                // Lift the panel off the app behind it. A soft shadow does that on its own, so the
                // surface itself can stay translucent and let the (blurred) backdrop through.
                .shadow(
                    elevation = 24.dp,
                    shape = panelShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.22f),
                    spotColor = Color.Black.copy(alpha = 0.28f),
                )
                // Unfold out of the edge bar: scale up from the bar's own spot while fading in.
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
            shape = panelShape,
            // Translucent on purpose: QuickChatActivity's window requests a background blur, so
            // what shows through here is a softened copy of the app underneath — the panel reads
            // as a pane of frosted glass hovering over it instead of an opaque slab of colour.
            color = panelStyle.panel,
            border = BorderStroke(width = 1.dp, color = panelStyle.hairline),
            tonalElevation = 0.dp,
        ) {
            val assistant = settings.getAssistantById(conversation.assistantId)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = topInset + 8.dp,
                        bottom = 8.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // ---- Header ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (mirror) {
                        closeButton()
                        openFullButton()
                        newButton()
                    }
                    if (assistant != null) {
                        AssistantAvatar(
                            assistant = assistant,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
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
                HorizontalDivider(thickness = 1.dp, color = panelStyle.hairline)

                // ---- Messages ----
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.quick_chat_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 24.dp),
                            )
                        }
                    }
                    items(messages, key = { it.id.toString() }) { message ->
                        MessageBlock(message, bubbleMaxWidth)
                    }
                }

                if (asr.isRecording) {
                    StatusRow(text = stringResource(R.string.quick_chat_listening))
                } else if (isGenerating) {
                    StatusRow(text = processingStatus.orEmpty())
                }

                // ---- Composer ----
                // Two shortcuts and mic/send sit in a tight cluster on the edge bar's side.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (mirror) {
                        screenButton()
                        readAloudButton()
                        voiceButton()
                        sendButton()
                    }
                    InputCapsule(
                        value = input,
                        onValueChange = { input = it },
                        onSend = { submit(input, false) },
                        hint = stringResource(R.string.quick_chat_input_hint),
                        style = panelStyle,
                        modifier = Modifier.weight(1f),
                    )
                    if (!mirror) {
                        sendButton()
                        voiceButton()
                        readAloudButton()
                        screenButton()
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
 * The composer's text entry: a flat capsule instead of a bordered [OutlinedTextField], so the row
 * stays ~40dp tall and the panel keeps that height for messages. Same `maxLines` behaviour and the
 * same Send-on-IME-action wiring; only the chrome changed.
 */
@Composable
private fun InputCapsule(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    hint: String,
    style: QuickChatPanelStyle,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = 40.dp),
        shape = RoundedCornerShape(20.dp),
        // A raised, slightly lighter capsule with the panel's hairline, so the input reads as its
        // own card on the glass instead of a darker hole in it.
        color = style.input,
        border = BorderStroke(width = 1.dp, color = style.hairline),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
        }
    }
}

/**
 * One message, rendered the way the in-app chat renders it: the parts are split into ordered
 * blocks (see [groupMessageParts]), so each model call's text becomes its own segment instead of
 * one merged wall of text, and thinking/tool runs collapse into a single quiet chip.
 *
 * Assistant prose goes through [MarkdownBlock] at a larger size than the surrounding chrome —
 * headings, lists, code and tables survive the trip into the panel, and a long answer is legible
 * without opening the full app. User text stays a plain bubble: nobody writes Markdown to
 * themselves in a one-line pop-up.
 */
@Composable
private fun MessageBlock(message: UIMessage, bubbleMaxWidth: Dp, modern: Boolean = false) {
    val isUser = message.role == MessageRole.USER
    val foldProcessNarration = LocalSettings.current.displaySetting.foldProcessNarration
    // Key on the parts list itself: a key of `size + last-part hash` misses in-place edits to any
    // part that is not the tail (see ChatMessage), which renders a stale snapshot that only
    // catches up when the tail happens to change.
    val blocks = remember(message.parts, foldProcessNarration) {
        message.parts.groupMessageParts(foldProcessNarration = foldProcessNarration)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (modern) 8.dp else 6.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        blocks.forEach { block ->
            when (block) {
                is MessagePartBlock.ThinkingBlock -> ThinkingChip(block.steps)

                is MessagePartBlock.ContentBlock -> when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        val text = part.text.trim()
                        if (text.isNotEmpty()) {
                            if (isUser) {
                                UserBubble(text, bubbleMaxWidth, modern = modern)
                            } else {
                                AssistantText(text, modern = modern, maxWidth = bubbleMaxWidth)
                            }
                        }
                    }

                    else -> SegmentChip(stringResource(R.string.quick_chat_segment_attachment))
                }
            }
        }
    }
}

/**
 * Assistant prose. Classic chrome lays Markdown straight on the glass; capsule chrome wraps it in
 * a soft bubble so replies match the user side and do not dissolve into the frosted panel.
 */
@Composable
private fun AssistantText(text: String, modern: Boolean = false, maxWidth: Dp = Dp.Unspecified) {
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = if (modern) 14.5.sp else 15.sp,
        lineHeight = if (modern) 21.sp else 22.sp,
    )
    if (!modern) {
        MarkdownBlock(
            content = text,
            modifier = Modifier.fillMaxWidth(),
            style = bodyStyle,
        )
        return
    }
    Surface(
        modifier = if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier,
        shape = RoundedCornerShape(
            topStart = 6.dp,
            topEnd = 18.dp,
            bottomStart = 18.dp,
            bottomEnd = 18.dp,
        ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
    ) {
        MarkdownBlock(
            content = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = bodyStyle,
        )
    }
}

@Composable
private fun UserBubble(text: String, maxWidth: Dp, modern: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        // Wrap the content rather than always filling the row: a short message stays a small pill
        // and only a genuinely long one grows to the cap.
        modifier = Modifier.widthIn(max = maxWidth),
        shape = if (modern) {
            RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 6.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp,
            )
        } else {
            RoundedCornerShape(18.dp)
        },
        color = if (modern) scheme.primary else scheme.primaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = if (modern) 14.5.sp else 15.sp,
                lineHeight = if (modern) 20.sp else 21.sp,
            ),
            color = if (modern) scheme.onPrimary else scheme.onPrimaryContainer,
            modifier = Modifier.padding(
                horizontal = if (modern) 12.dp else 14.dp,
                vertical = if (modern) 8.dp else 9.dp,
            ),
        )
    }
}

/**
 * A quiet, non-bubble line standing in for a thinking/tool run — tap to look inside. The old chip
 * was a dead end: it said "Tools (3)" and there was no way to see which three, or what the model
 * was thinking, without leaving for the full chat page.
 */
@Composable
private fun ThinkingChip(steps: List<ThinkingStep>) {
    var expanded by remember { mutableStateOf(false) }
    val tools = steps.mapNotNull {
        when (it) {
            is ThinkingStep.ToolStep -> it.tool.toolName
            is ThinkingStep.ServerToolStep -> it.tool.toolName
            is ThinkingStep.ReasoningStep -> null
            is ThinkingStep.NarrationStep -> null
        }
    }
    val reasonings = steps.filterIsInstance<ThinkingStep.ReasoningStep>()
        .map { it.reasoning.reasoning.trim() }
        .filter { it.isNotEmpty() }
    val narrations = steps.filterIsInstance<ThinkingStep.NarrationStep>()
        .map { it.text.text.trim() }
        .filter { it.isNotEmpty() }
    val hasTools = tools.isNotEmpty()
    val label = when {
        hasTools -> stringResource(R.string.quick_chat_segment_tools, tools.size)
        reasonings.isNotEmpty() -> stringResource(R.string.quick_chat_segment_thinking)
        narrations.isNotEmpty() -> stringResource(R.string.quick_chat_segment_thinking)
        else -> return
    }
    val detail = buildString {
        reasonings.forEach { append(it).append("\n\n") }
        narrations.forEach { append(it).append("\n\n") }
        tools.forEach { append("\u2022 ").append(it).append('\n') }
    }.trim()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (hasTools) HugeIcons.Wrench01 else HugeIcons.AiBrain01,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded && detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 14,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** A quiet, non-bubble line standing in for a non-text attachment. */
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
