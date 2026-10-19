package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
 * and, like the regular bar, a small grey subtitle under it reads "assistant / model / N tokens"
 * (same token aggregation as the regular bar, see [conversationUsedTokens]). Only the
 * "assistant / model" part may ellipsize; the token count is its own text and always shows,
 * compacted per locale ([cleanTopBarTokenText]). Taps do the same as the regular bar: the title
 * renames, the buttons open the drawer, toggle the preview list and start a new chat. The
 * buttons are a size smaller than the regular bar's ([CLEAN_TOP_BAR_BUTTON_DP] touch,
 * [CLEAN_TOP_BAR_ICON_DP] icon), leaving the title more room.
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
                CleanTopBarButtonSize {
                    IconButton(
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier.size(CLEAN_TOP_BAR_BUTTON_DP.dp),
                    ) {
                        Icon(
                            HugeIcons.Menu03,
                            stringResource(R.string.menu),
                            modifier = Modifier.size(CLEAN_TOP_BAR_ICON_DP.dp),
                        )
                    }
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
                    val locale = LocalConfiguration.current.locales[0]
                    val style = MaterialTheme.typography.labelSmall
                    val color = MaterialTheme.colorScheme.onSurfaceVariant
                    val assistantName = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "$assistantName / ${model.displayName}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = style,
                            color = color,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = cleanTopBarTokenText(
                                usedTokens = usedTokens,
                                chinese = isChineseLocale(locale),
                                traditional = isTraditionalChineseLocale(locale),
                            ),
                            maxLines = 1,
                            softWrap = false,
                            style = style,
                            color = color,
                        )
                    }
                }
            }
        },
        actions = {
            CleanTopBarButtonSize {
                IconButton(onClick = onClickMenu, modifier = Modifier.size(CLEAN_TOP_BAR_BUTTON_DP.dp)) {
                    Icon(
                        if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet,
                        stringResource(R.string.accessibility_chat_options),
                        modifier = Modifier.size(CLEAN_TOP_BAR_ICON_DP.dp),
                    )
                }
                IconButton(onClick = onNewChat, modifier = Modifier.size(CLEAN_TOP_BAR_BUTTON_DP.dp)) {
                    Icon(
                        HugeIcons.MessageAdd01,
                        stringResource(R.string.chat_page_new_message),
                        modifier = Modifier.size(CLEAN_TOP_BAR_ICON_DP.dp),
                    )
                }
            }
        },
    )
}

/** Touch target and icon size of the clean-layout top bar buttons (regular bar: 48dp / 24dp). */
internal const val CLEAN_TOP_BAR_BUTTON_DP = 40
internal const val CLEAN_TOP_BAR_ICON_DP = 20

/** Lets the clean bar's buttons be [CLEAN_TOP_BAR_BUTTON_DP] instead of the 48dp minimum. */
@Composable
private fun CleanTopBarButtonSize(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides CLEAN_TOP_BAR_BUTTON_DP.dp,
        content = content,
    )
}
