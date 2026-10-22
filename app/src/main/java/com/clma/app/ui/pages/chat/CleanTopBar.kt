package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.hazeGlass
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatLayout
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.effectiveChatLayout
import me.rerere.rikkahub.data.datastore.resolveChatModel
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.usage.TurnFooter
import me.rerere.rikkahub.ui.components.ui.cleanHeaderShape
import me.rerere.rikkahub.ui.components.ui.cleanPanelFill
import me.rerere.rikkahub.ui.components.ui.cleanPanelOutline
import me.rerere.rikkahub.ui.components.ui.rikkaGlassStyle
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.androidx.compose.koinViewModel

/**
 * "Clean layout" top bar (DisplaySetting.cleanLayout, off by default).
 *
 * Flat: no title plate, no glass discs behind the buttons. The conversation title is one line
 * and, like the regular bar, a small grey subtitle under it reads "model / N tokens"
 * (same token aggregation as the regular bar, see [conversationUsedTokens]). Only the
 * model name may ellipsize; the token count is its own text and always shows,
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
        // Shorter than the 64dp default so the panel takes less of the photo. The status-bar inset
        // is applied by the bar itself and is not part of this height, so a punch-hole stays clear.
        expandedHeight = settings.displaySetting.cleanLayoutTopHeight.dp,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.displayName,
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

/**
 * Panel behind [CleanTopBar] (DisplaySetting.cleanLayout).
 *
 * The clean bar used to float on a full-width fade that had no edge of its own, which read as a
 * white haze laid over the photo. It is a panel now: flush with the screen edges, only the bottom
 * corners rounded, and a fill the user can dial (DisplaySetting.cleanLayoutTopOpacity). Same
 * material as the composer card below — glass when DisplaySetting.cleanLayoutGlass is on (the
 * glass carries its own rim, so no hairline is added), a flat fill with a hairline otherwise — so
 * the two ends of the screen read as one pair. The fade is gone: content scrolling under a panel
 * needs no dissolve, and the dissolve is exactly what looked wrong.
 */
@Composable
internal fun CleanTopBarPanel(
    settings: Settings,
    hazeState: HazeState,
    content: @Composable () -> Unit,
) {
    val darkTheme = LocalDarkMode.current
    // CLAUDE (暖阳) keeps a solid paper header; glass is CLEAN-only.
    val glass = settings.displaySetting.effectiveChatLayout == ChatLayout.CLEAN &&
        settings.displaySetting.cleanLayoutGlass
    val opacity = settings.displaySetting.cleanLayoutTopOpacity
    val shape = cleanHeaderShape()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (glass) {
                    Modifier.hazeGlass(
                        input = HazeInput.Sources(hazeState),
                        style = rikkaGlassStyle(
                            shape,
                            blurRadius = 12.dp,
                            depth = 0.4f,
                            tintStrength = opacity,
                        ),
                    )
                } else {
                    Modifier
                }
            ),
        shape = shape,
        tonalElevation = 0.dp,
        color = if (glass) Color.Transparent else cleanPanelFill(opacity),
        border = if (glass) null else BorderStroke(1.dp, cleanPanelOutline(darkTheme)),
        content = content,
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
