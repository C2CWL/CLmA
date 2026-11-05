package com.clma.app.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clma.ai.core.MessageRole
import com.clma.ai.provider.Model
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.isEmptyUIMessage
import com.clma.app.R
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.Avatar
import com.clma.app.ui.components.ui.AssistantAvatar
import com.clma.app.ui.components.ui.AutoAIIcon
import com.clma.app.ui.components.ui.UIAvatar
import com.clma.app.ui.context.LocalSettings

@Composable
fun ChatMessageUserAvatar(
    message: UIMessage,
    avatar: Avatar,
    nickname: String,
    modifier: Modifier = Modifier,
    // 现代布局：时间放进头像行（名字后面），头像小一号。
    time: String? = null,
    avatarSize: Dp = 28.dp,
) {
    val settings = LocalSettings.current
    if (message.role == MessageRole.USER && !message.parts.isEmptyUIMessage() && !settings.displaySetting.showUserAvatar && time != null) {
        Row(modifier = modifier, horizontalArrangement = Arrangement.End) {
            ChatMessageHeaderTime(time)
        }
    } else if (message.role == MessageRole.USER && !message.parts.isEmptyUIMessage() && settings.displaySetting.showUserAvatar) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = nickname.ifEmpty { stringResource(R.string.user_default_name) },
                style = MaterialTheme.typography.labelLargeEmphasized,
                maxLines = 1,
            )
            if (time != null) ChatMessageHeaderTime(time)
            UIAvatar(
                name = nickname,
                modifier = Modifier.size(avatarSize),
                value = avatar,
                loading = false,
            )
        }
    }
}

@Composable
fun ChatMessageAssistantAvatar(
    message: UIMessage,
    loading: Boolean,
    model: Model?,
    assistant: Assistant?,
    modifier: Modifier = Modifier,
    time: String? = null,
    avatarSize: Dp = 28.dp,
) {
    val settings = LocalSettings.current
    val showIcon = settings.displaySetting.showModelIcon
    val useAssistantAvatar = assistant?.useAssistantAvatar == true
    if (message.role == MessageRole.ASSISTANT && (model != null || useAssistantAvatar)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
        ) {
            if (useAssistantAvatar) {
                if (showIcon) {
                    AssistantAvatar(
                        assistant = assistant,
                        name = assistant.name,
                        modifier = Modifier.size(avatarSize),
                        loading = loading,
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (settings.displaySetting.showModelName) {
                        Text(
                            text = assistant.name.ifEmpty { stringResource(R.string.assistant_page_default_assistant) },
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            maxLines = 1,
                        )
                    }
                    if (time != null) ChatMessageHeaderTime(time)
                }
            } else if (model != null) {
                if (showIcon) {
                    AutoAIIcon(
                        name = model.modelId,
                        modifier = Modifier.size(avatarSize),
                        loading = loading
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (settings.displaySetting.showModelName) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            maxLines = if (time != null) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (time != null) ChatMessageHeaderTime(time)
                }
            }
        }
    }
}

/** Small grey time next to the name in the modern layout's message header. */
@Composable
private fun ChatMessageHeaderTime(time: String) {
    Text(
        text = time,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        maxLines = 1,
    )
}
