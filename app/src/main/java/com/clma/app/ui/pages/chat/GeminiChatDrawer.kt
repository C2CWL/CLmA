package com.clma.app.ui.pages.chat

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.resolveChatModel
import com.clma.app.data.model.Conversation
import com.clma.app.data.usage.TurnFooter
import com.clma.app.ui.components.ui.AppDropdownMenu
import com.clma.app.ui.components.ui.AppMenuEntry
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.History
import com.composables.icons.lucide.List
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.X
import kotlinx.coroutines.launch
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
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val panelWidth = (configuration.screenWidthDp * 0.85f).coerceAtMost(360f).dp
    var showTitleMenu by remember(conversation.id, state.isOpen) { mutableStateOf(false) }
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
                    ModalDrawerSheet(
                        modifier = Modifier.width(panelWidth),
                        drawerShape = AbsoluteRoundedCornerShape(topLeft = 24.dp, bottomLeft = 24.dp),
                        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    stringResource(R.string.chat_page_panel_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                IconButton(onClick = { scope.launch { state.close() } }) {
                                    Icon(Lucide.X, stringResource(R.string.update_card_close), Modifier.size(24.dp))
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    conversation.title.ifBlank {
                                        stringResource(
                                            if (conversation.isTemporary) R.string.chat_page_temporary_chat
                                            else R.string.chat_page_new_chat,
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Box {
                                    IconButton(onClick = { showTitleMenu = true }) {
                                        Icon(Lucide.EllipsisVertical, stringResource(R.string.accessibility_chat_options), Modifier.size(24.dp))
                                    }
                                    AppDropdownMenu(
                                        expanded = showTitleMenu,
                                        onDismissRequest = { showTitleMenu = false },
                                        items = listOf(
                                            AppMenuEntry(
                                                text = stringResource(R.string.chat_page_edit_title),
                                                icon = Lucide.SquarePen,
                                                enabled = conversation.messageNodes.isNotEmpty(),
                                                onClick = {
                                                    closeAndRun { drawerVm.requestRenameConversation(conversation) }
                                                },
                                            ),
                                        ),
                                    )
                                }
                            }
                            model?.let {
                                Text(
                                    it.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                stringResource(
                                    R.string.chat_page_panel_usage,
                                    cleanTopBarTokenText(
                                        usedTokens, isChineseLocale(locale), isTraditionalChineseLocale(locale),
                                    ).removePrefix(" / "),
                                ),
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (conversation.isTemporary) {
                                Text(
                                    stringResource(R.string.chat_page_temporary_chat_desc),
                                    modifier = Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(24.dp))
                            FilledTonalButton(
                                onClick = { closeAndRun(onNewChat) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                            ) {
                                Icon(Lucide.Plus, null, Modifier.size(24.dp))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.chat_page_new_chat), style = MaterialTheme.typography.labelLarge)
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 20.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            NavigationDrawerItem(
                                label = {
                                    Text(
                                        stringResource(
                                            if (previewMode) R.string.chat_conversation_settings_exit_preview
                                            else R.string.chat_conversation_settings_message_outline,
                                        ),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                },
                                selected = previewMode,
                                icon = { Icon(Lucide.List, null, Modifier.size(24.dp)) },
                                onClick = { closeAndRun(onTogglePreview) },
                            )
                            Spacer(Modifier.height(4.dp))
                            NavigationDrawerItem(
                                label = {
                                    Text(stringResource(R.string.chat_page_new_temporary_chat), style = MaterialTheme.typography.bodyLarge)
                                },
                                selected = false,
                                icon = { Icon(Lucide.History, null, Modifier.size(24.dp)) },
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
