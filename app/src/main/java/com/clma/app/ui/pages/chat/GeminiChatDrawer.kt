package com.clma.app.ui.pages.chat

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.resolveChatModel
import com.clma.app.data.model.Conversation
import com.clma.app.data.usage.TurnFooter
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.ViewOff
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun GeminiChatDrawer(
    enabled: Boolean,
    settings: Settings,
    conversation: Conversation,
    turnFooters: Map<String, TurnFooter>,
    previewMode: Boolean,
    onTogglePreview: () -> Unit,
    onNewChat: () -> Unit,
    onNewTemporaryChat: () -> Unit,
    content: @Composable (onOpen: () -> Unit) -> Unit,
) {
    if (!enabled) {
        content({})
        return
    }
    val state = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(conversation.id) { state.close() }
    BackHandler(enabled = state.isOpen) { scope.launch { state.close() } }
    val direction = LocalLayoutDirection.current
    val activity = LocalContext.current as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)
    val model = settings.resolveChatModel(conversation.chatModelId, conversation.assistantId)
    val usedTokens = remember(turnFooters, conversation) {
        conversationUsedTokens(turnFooters, conversation)
    }
    val locale = LocalConfiguration.current.locales[0]
    val closeAndRun: (() -> Unit) -> Unit = { action ->
        scope.launch {
            state.close()
            action()
        }
    }
    // Material's drawer opens at start; mirror only its container, not the page or text.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = state,
            gesturesEnabled = state.isOpen,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    ModalDrawerSheet {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text(
                                conversation.title.ifBlank { stringResource(if (conversation.isTemporary) R.string.chat_page_temporary_chat else R.string.chat_page_new_chat) },
                                style = MaterialTheme.typography.titleLarge,
                            )
                            model?.let {
                                Text(it.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                            }
                            Text(
                                cleanTopBarTokenText(usedTokens, isChineseLocale(locale), isTraditionalChineseLocale(locale)).removePrefix(" / "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                enabled = conversation.messageNodes.isNotEmpty(),
                                onClick = { closeAndRun { drawerVm.requestRenameConversation(conversation) } },
                                contentPadding = PaddingValues(vertical = 12.dp),
                            ) { Text(stringResource(R.string.chat_page_edit_title)) }
                            NavigationDrawerItem(
                                label = { Text(stringResource(R.string.code_block_preview)) },
                                selected = previewMode,
                                icon = { Icon(HugeIcons.LeftToRightListBullet, null) },
                                onClick = { closeAndRun(onTogglePreview) },
                            )
                            NavigationDrawerItem(
                                label = { Text(stringResource(R.string.chat_page_new_chat)) },
                                selected = false,
                                icon = { Icon(HugeIcons.MessageAdd01, null) },
                                onClick = { closeAndRun(onNewChat) },
                            )
                            NavigationDrawerItem(
                                label = { Text(stringResource(R.string.chat_page_temporary_chat)) },
                                selected = conversation.isTemporary,
                                icon = { Icon(HugeIcons.ViewOff, null) },
                                onClick = { closeAndRun(onNewTemporaryChat) },
                            )
                        }
                    }
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                content {
                    keyboard?.hide()
                    scope.launch { state.open() }
                }
            }
        }
    }
}
