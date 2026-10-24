package com.clma.app.ui.components.message

import android.os.Build
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.datetime.toJavaLocalDateTime
import com.clma.ai.core.MessageRole
import com.clma.ai.provider.Model
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.ai.ui.isEmptyUIMessage
import kotlinx.datetime.LocalDateTime
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.FavouriteCircle
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.WebDesign01
import com.clma.app.R
import com.clma.app.data.model.MessageNode
import com.clma.app.ui.components.ui.FrostedPlateSpec
import com.clma.app.ui.components.ui.RikkaConfirmDialog
import com.clma.app.ui.components.ui.frostedPlate
import com.clma.app.ui.context.LocalSettings
import com.clma.app.ui.context.LocalTTSState
import com.clma.app.utils.copyMessageToClipboard
import com.clma.app.utils.extractQuotedContentAsText
import com.clma.app.utils.removeBracketedContent
import com.clma.app.utils.toLocalString
import com.clma.app.utils.toMessageTimeString
import java.util.Locale

@Composable
fun ColumnScope.ChatMessageActionButtons(
    message: UIMessage,
    node: MessageNode,
    onUpdate: (MessageNode) -> Unit,
    onRegenerate: () -> Unit,
    onOpenActionSheet: () -> Unit,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    photoShown: Boolean = false,
    hazeState: HazeState? = null,
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    var isPendingDelete by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(isPendingDelete) {
        if (isPendingDelete) {
            delay(3000) // 3秒后自动取消
            isPendingDelete = false
        }
    }

    // Over a photo wallpaper the row sits on the usage line's plate, in onSurface ink.
    val style = messageActionsStyle(photoShown, sdkInt = Build.VERSION.SDK_INT, hasHazeState = hazeState != null)
    val ink = if (style.onSurfaceInk) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val plateShape = RoundedCornerShape(16.dp)
    val frost = style.frost
    val plateAlpha = style.plateAlpha
    val plateModifier = when {
        frost != null && hazeState != null -> Modifier.frostedPlate(
            hazeState = hazeState,
            shape = plateShape,
            spec = FrostedPlateSpec(tintAlpha = frost.tintAlpha, blurRadiusDp = frost.blurRadiusDp),
            selection = WallpaperOnlySelection,
        )

        plateAlpha != null -> Modifier
            .clip(plateShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = plateAlpha))

        else -> Modifier
    }

    // 助手消息操作栏靠左、时间贴右端；用户消息整组靠右，时间跟在操作栏右边。
    val userMessage = message.role == MessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    if (userMessage) Spacer(Modifier.weight(1f))
    FlowRow(
        modifier = plateModifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val actionIconColor = ink.copy(alpha = style.iconAlpha)

        Icon(
            imageVector = HugeIcons.Copy01,
            contentDescription = stringResource(R.string.copy),
            modifier = Modifier
                .clip(CircleShape)
                .clickable { context.copyMessageToClipboard(message) }
                .padding(8.dp)
                .size(16.dp),
            tint = actionIconColor
        )

        Icon(
            imageVector = HugeIcons.Refresh03,
            contentDescription = stringResource(R.string.regenerate),
            modifier = Modifier
                .clip(CircleShape)
                .clickable {
                    if (message.role == MessageRole.USER) {
                        showRegenerateConfirm = true
                    } else {
                        onRegenerate()
                    }
                }
                .padding(8.dp)
                .size(16.dp),
            tint = actionIconColor
        )

        if (message.role == MessageRole.ASSISTANT) {
            val tts = LocalTTSState.current
            val settings = LocalSettings.current
            val isSpeaking by tts.isSpeaking.collectAsStateWithLifecycle()
            val isAvailable by tts.isAvailable.collectAsStateWithLifecycle()
            Icon(
                imageVector = if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                contentDescription = stringResource(R.string.tts),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        enabled = isAvailable,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (!isSpeaking) {
                                val text = message.toText()
                                var textToSpeak = text
                                if (settings.displaySetting.ttsOnlyReadQuoted) {
                                    textToSpeak = textToSpeak.extractQuotedContentAsText() ?: textToSpeak
                                }
                                if (settings.displaySetting.ttsOnlyReadOutsideBrackets) {
                                    textToSpeak = textToSpeak.removeBracketedContent() ?: textToSpeak
                                }
                                tts.speak(textToSpeak)
                            } else {
                                tts.stop()
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = if (isAvailable) actionIconColor else actionIconColor.copy(alpha = 0.38f)
            )

            // Translation button
            if (onTranslate != null) {
                Icon(
                    imageVector = HugeIcons.Translate,
                    contentDescription = stringResource(R.string.translate),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClick = {
                                showTranslateDialog = true
                            }
                        )
                        .padding(8.dp)
                        .size(16.dp),
                    tint = actionIconColor
                )
            }
        }

        Icon(
            imageVector = HugeIcons.MoreVertical,
            contentDescription = stringResource(R.string.more_options),
            modifier = Modifier
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    onClick = {
                        onOpenActionSheet()
                    }
                )
                .padding(8.dp)
                .size(16.dp),
            tint = actionIconColor
        )

        ChatMessageBranchSelector(
            node = node,
            color = actionIconColor,
            onUpdate = onUpdate,
        )
    }

    if (!userMessage) Spacer(Modifier.weight(1f))

    // 时间戳贴在操作栏右端，与操作栏共用同一层胶囊底板
    if (settings.displaySetting.showDateTimeInMessage && !message.parts.isEmptyUIMessage()) {
        val timePlateModifier = when {
            frost != null && hazeState != null -> Modifier.frostedPlate(
                hazeState = hazeState,
                shape = plateShape,
                spec = FrostedPlateSpec(tintAlpha = frost.tintAlpha, blurRadiusDp = frost.blurRadiusDp),
                selection = WallpaperOnlySelection,
            )

            plateAlpha != null -> Modifier
                .clip(plateShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = plateAlpha))

            else -> Modifier
                .clip(plateShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        }
        Text(
            text = message.displayTimestamp(preferEndTime = settings.displaySetting.showMessageEndTime)
                .toJavaLocalDateTime()
                .toMessageTimeString(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = style.timeAlpha),
            maxLines = 1,
            modifier = timePlateModifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
    }

    // Translation dialog
    if (showTranslateDialog && onTranslate != null) {
        LanguageSelectionDialog(
            onLanguageSelected = { language ->
                showTranslateDialog = false
                onTranslate(message, language)
            },
            onClearTranslation = {
                showTranslateDialog = false
                onClearTranslation(message)
            },
            onDismissRequest = {
                showTranslateDialog = false
            },
        )
    }

    // Regenerate confirmation dialog
    RikkaConfirmDialog(
        show = showRegenerateConfirm,
        title = stringResource(R.string.regenerate),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showRegenerateConfirm = false
            onRegenerate()
        },
        onDismiss = { showRegenerateConfirm = false },
        text = { Text(stringResource(R.string.regenerate_confirm_message)) }
    )
}

/**
 * 独立的消息时间戳：画在操作栏之外，不受操作栏显隐约束。用户消息（`finishedAt` 恒为
 * null）和插话打断的前半（不出操作栏）也能显示时间。受「显示消息时间」开关控制。
 */
@Composable
fun ChatMessageTimestamp(
    message: UIMessage,
    photoShown: Boolean = false,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    if (!settings.displaySetting.showDateTimeInMessage) return
    val style = messageActionsStyle(photoShown, sdkInt = Build.VERSION.SDK_INT, hasHazeState = hazeState != null)
    // The timestamp is standalone text: always the strong ink, never the faded secondary one.
    val ink = MaterialTheme.colorScheme.onSurface
    // Streaming: createdAt ≈ first-token time. After finish/stop: finishedAt when the
    // "prefer end time" switch is on (default), else keep the old always-createdAt look.
    val displayAt = message.displayTimestamp(
        preferEndTime = settings.displaySetting.showMessageEndTime,
    )
    Text(
        text = displayAt.toJavaLocalDateTime().toMessageTimeString(),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
        color = ink.copy(alpha = style.timeAlpha),
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
fun ChatMessageActionsSheet(
    message: UIMessage,
    model: Model?,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onFork: () -> Unit,
    onSelectAndCopy: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onWebViewPreview: () -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Select and Copy
            Card(
                onClick = {
                    onDismissRequest()
                    onSelectAndCopy()
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.TextSelection,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.select_and_copy),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // WebView Preview (only show if message has text content)
            val hasTextContent = message.parts.filterIsInstance<UIMessagePart.Text>()
                .any { it.text.isNotBlank() }

            if (hasTextContent) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onWebViewPreview()
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = HugeIcons.WebDesign01,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.render_with_webview),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Edit
            Card(
                onClick = {
                    onDismissRequest()
                    onEdit()
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.Edit01,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.edit),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Share
            Card(
                onClick = {
                    onDismissRequest()
                    onShare()
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.Share04,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.share),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Create a Fork
            Card(
                onClick = {
                    onDismissRequest()
                    onFork()
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.GitFork,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.create_fork),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            if (onToggleFavorite != null) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onToggleFavorite()
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = HugeIcons.FavouriteCircle,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(
                                if (isFavorite) R.string.chat_message_remove_favorite
                                else R.string.chat_message_add_favorite
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Delete
            Card(
                onClick = {
                    onDismissRequest()
                    onDelete()
                },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.delete),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Message Info — sheet always lists createdAt; when a finish/interrupt stamp exists,
            // show it too so the detail view keeps both ends of the turn.
            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                Text(message.createdAt.toJavaLocalDateTime().toLocalString())
                message.finishedAt?.let { finished ->
                    Text(finished.toJavaLocalDateTime().toLocalString())
                }
                if (model != null) {
                    Text(model.displayName)
                }
            }
        }
    }
}

/**
 * Compact message timestamp for the action row.
 *
 * - While streaming (`finishedAt == null`): always [UIMessage.createdAt] (first-token time).
 * - After finish/stop: [UIMessage.finishedAt] when [preferEndTime] is on, else [UIMessage.createdAt].
 */
private fun UIMessage.displayTimestamp(preferEndTime: Boolean): LocalDateTime =
    if (preferEndTime) finishedAt ?: createdAt else createdAt
