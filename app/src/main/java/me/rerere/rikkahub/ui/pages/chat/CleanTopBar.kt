package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.resolveChatModel
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.usage.TurnFooter
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel

/**
 * "Clean layout" top bar (DisplaySetting.cleanLayout, off by default).
 *
 * Flat: no title plate, no glass discs behind the buttons. The conversation title is one line
 * and, like the regular bar, a small grey subtitle under it reads "assistant / model / N token"
 * (same token aggregation as the regular bar, see [conversationUsedTokens]). Taps do the same as the
 * regular bar: the title renames, the buttons open the drawer, toggle the preview list and
 * start a new chat.
 */
@Composable
internal fun CleanTopBar(
    settings: Settings,
    conversation: Conversation,
    turnFooters: Map<String, TurnFooter>,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    // Same rename dialog state as the regular bar and the drawer's Rename item.
    val activity = LocalContext.current as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)
    val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(HugeIcons.Menu03, stringResource(R.string.menu))
                }
            }
        },
        title = {
            val assistant = settings.getCurrentAssistant()
            val model = settings.resolveChatModel(conversation.chatModelId, conversation.assistantId)
            Column(
                modifier = Modifier
                    .clickable {
                        if (conversation.messageNodes.isNotEmpty()) {
                            drawerVm.requestRenameConversation(conversation)
                        } else {
                            toaster.show(editTitleWarning, type = ToastType.Warning)
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (model != null) {
                    val usedTokens = remember(turnFooters, conversation) {
                        conversationUsedTokens(turnFooters, conversation)
                    }
                    Text(
                        text = topBarSubtitle(
                            assistantName = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                            modelName = model.displayName,
                            usedTokens = usedTokens,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onClickMenu) {
                Icon(
                    if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet,
                    stringResource(R.string.accessibility_chat_options),
                )
            }
            IconButton(onClick = onNewChat) {
                Icon(HugeIcons.MessageAdd01, stringResource(R.string.chat_page_new_message))
            }
        },
    )
}
