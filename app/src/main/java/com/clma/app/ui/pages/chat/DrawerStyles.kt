package com.clma.app.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.findModelById
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.Avatar
import com.clma.app.ui.components.ui.AppDropdownMenu
import com.clma.app.ui.components.ui.AppMenuEntry
import com.clma.app.ui.components.ui.AssistantAvatar
import com.clma.app.ui.components.ui.UIAvatar
import com.clma.app.ui.context.Navigator
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.ChartColumn
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MonitorCog
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plug
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.UserRoundPen
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.Image as LucideImage
import com.composables.icons.lucide.Settings as LucideSettings

/*
 * 侧边栏的两种样式（设置 → 聊天布局 →「侧边栏样式」）。
 *
 * - 助手卡片（DrawerStyle.CARD，默认）：顶部一张渐变卡片放当前助手（头像、名字、模型、助手设置），
 *   下面一排可横向滑动的其他助手头像；再下面是「新聊天」主按钮 + 搜索、分段文件夹、会话列表；
 *   底部是 收藏 / 工具 / 更多 三个小块，以及放用户头像、昵称和设置按钮的「我」卡片。
 * - 助手导轨（DrawerStyle.RAIL）：左边 64dp 竖向可滚动的助手导轨，底部挂 收藏 / 工具 / 更多 / 设置
 *   和用户头像；右边面板是当前助手标题、带「新聊天」按钮的搜索框、分段文件夹、会话列表和昵称行。
 *
 * 这里只放外观；会话列表、文件夹、多选、切换助手等逻辑都还在 ChatDrawerContent 里，两种样式共用。
 */

private val HeroShape = RoundedCornerShape(14.dp)
private val ButtonShape = RoundedCornerShape(12.dp)
private val SmallShape = RoundedCornerShape(8.dp)
private val ChipShape = RoundedCornerShape(9.dp)
private val RailButtonShape = RoundedCornerShape(11.dp)
private val OnlineGreen = Color(0xFF34C759)

/** 比面板深一档的浅底色，按钮、轨道、搜索框都用它。 */
@Composable
internal fun drawerTonalColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)

/** 主题色的淡底，设置按钮这类「强调但不抢眼」的入口用。 */
@Composable
internal fun drawerAccentSoftColor(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)

/** 抬起来的一层：分段控件选中项、「我」卡片。半透明，压在磨砂面板上也不会变成白块。 */
@Composable
internal fun drawerRaisedColor(): Color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.78f)

/** 当前助手实际使用的模型名（助手没单独设就用全局默认模型）；找不到就返回 null，不显示。 */
internal fun Settings.drawerModelName(assistant: Assistant): String? =
    findModelById(assistant.chatModelId, chatModelId)?.let { model ->
        model.displayName.ifBlank { model.modelId }.ifBlank { null }
    }

/** 「工具」菜单：连接器、AI 翻译、图像生成、视频生成。 */
@Composable
internal fun drawerToolsMenu(navController: Navigator): List<AppMenuEntry> = listOf(
    AppMenuEntry(
        text = stringResource(R.string.setting_connectors_title),
        icon = Lucide.Plug,
        onClick = { navController.navigate(Screen.SettingConnectors) },
    ),
    AppMenuEntry(
        text = stringResource(R.string.chat_page_menu_ai_translator),
        icon = Lucide.Languages,
        onClick = { navController.navigate(Screen.Translator) },
    ),
    AppMenuEntry(
        text = stringResource(R.string.chat_page_menu_image_generation),
        icon = Lucide.LucideImage,
        onClick = { navController.navigate(Screen.ImageGen) },
    ),
    AppMenuEntry(
        text = stringResource(R.string.chat_page_menu_video_generation),
        icon = Lucide.Video,
        onClick = { navController.navigate(Screen.VideoGen) },
    ),
)

/** 「更多」菜单：助手管理、聊天历史、统计数据、虚拟屏管理。 */
@Composable
internal fun drawerMoreMenu(navController: Navigator): List<AppMenuEntry> = listOf(
    AppMenuEntry(
        text = stringResource(R.string.assistant_page_title),
        icon = Lucide.Bot,
        onClick = { navController.navigate(Screen.Assistant) },
    ),
    AppMenuEntry(
        text = stringResource(R.string.chat_page_history),
        icon = Lucide.History,
        onClick = { navController.navigate(Screen.History) },
    ),
    AppMenuEntry(
        text = stringResource(R.string.chat_drawer_statistics),
        icon = Lucide.ChartColumn,
        onClick = { navController.navigate(Screen.Stats) },
    ),
    AppMenuEntry(
        text = stringResource(R.string.virtual_display_manage_title),
        icon = Lucide.MonitorCog,
        onClick = { navController.navigate(Screen.VirtualDisplays) },
    ),
)

@Composable
private fun DrawerSquareButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    size: Dp,
    shape: Shape,
    container: Color,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    iconSize: Dp = 18.dp,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = shape,
        color = container,
        contentColor = tint,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                tint = tint,
            )
        }
    }
}

@Composable
private fun OnlineDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(OnlineGreen)
    )
}

// ---------------------------------------------------------------------------------------------
// 方案 A · 助手卡片
// ---------------------------------------------------------------------------------------------

/** 顶部助手卡：当前助手 + 横向滑动的其他助手头像 + 「+」进助手管理。 */
@Composable
internal fun DrawerAssistantHero(
    assistants: List<Assistant>,
    current: Assistant,
    modelName: String?,
    onOpenPicker: () -> Unit,
    onOpenDetail: () -> Unit,
    onSelect: (Assistant) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val defaultName = stringResource(R.string.assistant_page_default_assistant)
    val currentName = current.name.ifBlank { defaultName }
    val others = remember(assistants, current.id) { assistants.filter { it.id != current.id } }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(HeroShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    )
                )
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AssistantAvatar(
                assistant = current,
                name = currentName,
                onClick = onOpenPicker,
                modifier = Modifier.size(44.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(SmallShape)
                    .clickable(
                        onClickLabel = stringResource(R.string.drawer_switch_assistant),
                        onClick = onOpenPicker,
                    )
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = currentName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        imageVector = Lucide.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (modelName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        OnlineDot()
                        Text(
                            text = modelName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            DrawerSquareButton(
                icon = Lucide.SlidersHorizontal,
                contentDescription = stringResource(R.string.drawer_assistant_detail),
                onClick = onOpenDetail,
                size = 32.dp,
                shape = SmallShape,
                container = drawerTonalColor(),
                iconSize = 16.dp,
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(others, key = { it.id.toString() }) { assistant ->
                AssistantAvatar(
                    assistant = assistant,
                    name = assistant.name.ifBlank { defaultName },
                    onClick = { onSelect(assistant) },
                    modifier = Modifier.size(30.dp),
                )
            }
            item(key = "manage") {
                DrawerSquareButton(
                    icon = Lucide.Plus,
                    contentDescription = stringResource(R.string.assistant_page_title),
                    onClick = onManage,
                    size = 30.dp,
                    shape = CircleShape,
                    container = drawerTonalColor(),
                    iconSize = 16.dp,
                )
            }
        }
    }
}

/** 「新聊天」主按钮 + 搜索方块。 */
@Composable
internal fun DrawerNewChatRow(
    onNewChat: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onNewChat,
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
            shape = ButtonShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Lucide.SquarePen,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = stringResource(R.string.drawer_new_chat),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
        DrawerSquareButton(
            icon = Lucide.Search,
            contentDescription = stringResource(R.string.chat_page_search_chats),
            onClick = onSearch,
            size = 42.dp,
            shape = ButtonShape,
            container = drawerTonalColor(),
        )
    }
}

@Composable
private fun DrawerChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = ChipShape,
        color = drawerTonalColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 底部三个小块：收藏 / 工具（菜单）/ 更多（菜单）。 */
@Composable
internal fun DrawerQuickChips(
    onFavorite: () -> Unit,
    toolsMenu: List<AppMenuEntry>,
    moreMenu: List<AppMenuEntry>,
    modifier: Modifier = Modifier,
) {
    var showTools by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DrawerChip(
            icon = Lucide.Star,
            label = stringResource(R.string.favorite_page_title),
            onClick = onFavorite,
            modifier = Modifier.weight(1f),
        )
        Box(modifier = Modifier.weight(1f)) {
            DrawerChip(
                icon = Lucide.Sparkles,
                label = stringResource(R.string.drawer_tools),
                onClick = { showTools = true },
                modifier = Modifier.fillMaxWidth(),
            )
            AppDropdownMenu(
                expanded = showTools,
                onDismissRequest = { showTools = false },
                items = toolsMenu,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            DrawerChip(
                icon = Lucide.LayoutGrid,
                label = stringResource(R.string.drawer_more),
                onClick = { showMore = true },
                modifier = Modifier.fillMaxWidth(),
            )
            AppDropdownMenu(
                expanded = showMore,
                onDismissRequest = { showMore = false },
                items = moreMenu,
            )
        }
    }
}

/** 「我」卡片：头像（跟随「显示用户头像」开关）、昵称（点击改名）、设置按钮。 */
@Composable
internal fun DrawerMeCard(
    nickname: String,
    avatar: Avatar,
    showAvatar: Boolean,
    onAvatarUpdate: (Avatar) -> Unit,
    onEditNickname: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ButtonShape,
        color = drawerRaisedColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showAvatar) {
                UIAvatar(
                    name = nickname,
                    value = avatar,
                    onUpdate = onAvatarUpdate,
                    modifier = Modifier.size(36.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(SmallShape)
                    .clickable(
                        onClickLabel = stringResource(R.string.accessibility_edit_nickname),
                        onClick = onEditNickname,
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = nickname,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        if (showAvatar) R.string.drawer_me_hint_avatar else R.string.drawer_me_hint
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DrawerSquareButton(
                icon = Lucide.LucideSettings,
                contentDescription = stringResource(R.string.settings),
                onClick = onSettings,
                size = 40.dp,
                shape = RoundedCornerShape(10.dp),
                container = drawerAccentSoftColor(),
                tint = MaterialTheme.colorScheme.primary,
                iconSize = 19.dp,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 方案 B · 助手导轨
// ---------------------------------------------------------------------------------------------

/** 左侧竖向导轨：所有助手（可滚动）+「+」，底部 收藏 / 工具 / 更多 / 设置 和用户头像。 */
@Composable
internal fun DrawerAssistantRail(
    assistants: List<Assistant>,
    current: Assistant,
    onSelect: (Assistant) -> Unit,
    onOpenPicker: () -> Unit,
    onManage: () -> Unit,
    onFavorite: () -> Unit,
    toolsMenu: List<AppMenuEntry>,
    moreMenu: List<AppMenuEntry>,
    onSettings: () -> Unit,
    nickname: String,
    avatar: Avatar,
    showAvatar: Boolean,
    onAvatarUpdate: (Avatar) -> Unit,
    onEditNickname: () -> Unit,
    agentMode: Boolean,
    onAgentModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val defaultName = stringResource(R.string.assistant_page_default_assistant)
    var showTools by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    val ringColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .width(64.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f))
            .padding(vertical = 12.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DrawerModeRailToggle(agentMode = agentMode, onChange = onAgentModeChange)
        Spacer(modifier = Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(assistants, key = { it.id.toString() }) { assistant ->
                val selected = assistant.id == current.id
                val name = assistant.name.ifBlank { defaultName }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(width = 4.dp, height = 28.dp)
                                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                                .background(ringColor)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .then(
                                if (selected) Modifier.border(2.dp, ringColor, CircleShape) else Modifier
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        AssistantAvatar(
                            assistant = assistant,
                            name = name,
                            onClick = { if (selected) onOpenPicker() else onSelect(assistant) },
                            modifier = Modifier.size(if (selected) 40.dp else 38.dp),
                        )
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(OnlineGreen)
                            )
                        }
                    }
                }
            }
            item(key = "manage") {
                DrawerSquareButton(
                    icon = Lucide.Plus,
                    contentDescription = stringResource(R.string.assistant_page_title),
                    onClick = onManage,
                    size = 40.dp,
                    shape = ButtonShape,
                    container = drawerTonalColor(),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DrawerSquareButton(
                icon = Lucide.Star,
                contentDescription = stringResource(R.string.favorite_page_title),
                onClick = onFavorite,
                size = 42.dp,
                shape = RailButtonShape,
                container = drawerTonalColor(),
            )
            Box {
                DrawerSquareButton(
                    icon = Lucide.Sparkles,
                    contentDescription = stringResource(R.string.drawer_tools),
                    onClick = { showTools = true },
                    size = 42.dp,
                    shape = RailButtonShape,
                    container = drawerTonalColor(),
                )
                AppDropdownMenu(
                    expanded = showTools,
                    onDismissRequest = { showTools = false },
                    items = toolsMenu,
                )
            }
            Box {
                DrawerSquareButton(
                    icon = Lucide.LayoutGrid,
                    contentDescription = stringResource(R.string.drawer_more),
                    onClick = { showMore = true },
                    size = 42.dp,
                    shape = RailButtonShape,
                    container = drawerTonalColor(),
                )
                AppDropdownMenu(
                    expanded = showMore,
                    onDismissRequest = { showMore = false },
                    items = moreMenu,
                )
            }
            DrawerSquareButton(
                icon = Lucide.LucideSettings,
                contentDescription = stringResource(R.string.settings),
                onClick = onSettings,
                size = 42.dp,
                shape = RailButtonShape,
                container = drawerAccentSoftColor(),
                tint = MaterialTheme.colorScheme.primary,
            )
            if (showAvatar) {
                UIAvatar(
                    name = nickname,
                    value = avatar,
                    onUpdate = onAvatarUpdate,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(36.dp),
                )
            } else {
                DrawerSquareButton(
                    icon = Lucide.UserRoundPen,
                    contentDescription = stringResource(R.string.accessibility_edit_nickname),
                    onClick = onEditNickname,
                    size = 36.dp,
                    shape = CircleShape,
                    container = drawerTonalColor(),
                    modifier = Modifier.padding(top = 4.dp),
                    iconSize = 17.dp,
                )
            }
        }
    }
}

/** 导轨右侧面板的标题：「当前助手」+ 名字 + 模型，右边是助手设置。点标题可切换助手。 */
@Composable
internal fun DrawerRailHeader(
    current: Assistant,
    modelName: String?,
    onOpenPicker: () -> Unit,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = current.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(SmallShape)
                .clickable(
                    onClickLabel = stringResource(R.string.drawer_switch_assistant),
                    onClick = onOpenPicker,
                )
                .padding(horizontal = 2.dp, vertical = 2.dp),
        ) {
            Text(
                text = stringResource(R.string.drawer_current_assistant),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (modelName != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    OnlineDot()
                    Text(
                        text = modelName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DrawerSquareButton(
            icon = Lucide.SlidersHorizontal,
            contentDescription = stringResource(R.string.drawer_assistant_detail),
            onClick = onOpenDetail,
            size = 34.dp,
            shape = RoundedCornerShape(10.dp),
            container = drawerTonalColor(),
            iconSize = 17.dp,
        )
    }
}

/** 搜索框（点开进全文搜索），右端嵌一个「新聊天」按钮。 */
@Composable
internal fun DrawerSearchField(
    onSearch: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onSearch,
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp),
        shape = ButtonShape,
        color = drawerTonalColor(),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Lucide.Search,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = stringResource(R.string.drawer_search_hint),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DrawerSquareButton(
                icon = Lucide.SquarePen,
                contentDescription = stringResource(R.string.drawer_new_chat),
                onClick = onNewChat,
                size = 34.dp,
                shape = RoundedCornerShape(9.dp),
                container = MaterialTheme.colorScheme.primary,
                tint = MaterialTheme.colorScheme.onPrimary,
                iconSize = 17.dp,
            )
        }
    }
}

/** 导轨面板底部的昵称行，点一下改昵称。 */
@Composable
internal fun DrawerProfileRow(
    nickname: String,
    onEditNickname: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ButtonShape)
            .clickable(
                onClickLabel = stringResource(R.string.accessibility_edit_nickname),
                onClick = onEditNickname,
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = nickname,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = stringResource(R.string.drawer_edit_profile),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = Lucide.Pencil,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
