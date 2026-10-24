package com.clma.app.ui.pages.chat

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.TransactionHistory
import com.clma.app.R
import com.clma.app.ui.components.ui.AppDropdownMenu
import com.clma.app.ui.components.ui.AppMenuEntry
import com.clma.app.Screen
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.getCurrentAssistant
import com.clma.app.ui.theme.LocalDarkMode
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.Conversation
import com.clma.app.data.model.Folder
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.ui.components.ai.AssistantPicker
import com.clma.app.ui.components.ui.BackupReminderCard
import com.clma.app.ui.components.ui.Tooltip
import com.clma.app.ui.components.ui.AssistantAvatar
import com.clma.app.ui.components.ui.UIAvatar
import com.clma.app.ui.components.ui.frostedPlate
import com.clma.app.ui.components.message.WallpaperOnlySelection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import com.clma.app.ui.context.LocalToaster
import com.clma.app.ui.context.Navigator
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import com.clma.app.ui.hooks.EditStateContent
import com.clma.app.ui.hooks.readBooleanPreference
import com.clma.app.ui.hooks.useEditState
import com.clma.app.utils.navigateToChatPage
import com.clma.app.utils.toDp
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun ChatDrawerContent(
    navController: Navigator,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
    hazeState: HazeState,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val repo = koinInject<ConversationRepository>()

    val activity = context as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)

    val conversations = drawerVm.conversations.collectAsLazyPagingItems()
    val folders by drawerVm.folders.collectAsStateWithLifecycle()
    val selectedFolderId by drawerVm.selectedFolderId.collectAsStateWithLifecycle()
    val archiveFolderId by drawerVm.archiveFolderId.collectAsStateWithLifecycle()
    val protectedFolderId by drawerVm.protectedFolderId.collectAsStateWithLifecycle()
    val conversationListState = rememberLazyListState(
        initialFirstVisibleItemIndex = drawerVm.scrollIndex,
        initialFirstVisibleItemScrollOffset = drawerVm.scrollOffset,
    )

    LaunchedEffect(conversationListState) {
        snapshotFlow {
            conversationListState.firstVisibleItemIndex to
                conversationListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                drawerVm.saveScrollPosition(index, offset)
            }
    }

    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )

    // 昵称编辑状态
    val nicknameEditState = useEditState<String> { newNickname ->
        vm.updateSettings(
            settings.copy(
                displaySetting = settings.displaySetting.copy(
                    userNickname = newNickname
                )
            )
        )
    }

    // 移动对话状态
    var showMoveToAssistantSheet by remember { mutableStateOf(false) }
    var conversationToMove by remember { mutableStateOf<Conversation?>(null) }
    val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    // 文件夹相关状态
    var showMoveToFolderSheet by remember { mutableStateOf(false) }
    var conversationToMoveFolder by remember { mutableStateOf<Conversation?>(null) }
    val folderSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    // 多选：长按菜单进入；底部栏批量移动到已有文件夹，或新建文件夹后直接移入。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Uuid>()) }
    var batchMove by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
        batchMove = false
        showDeleteSelectedDialog = false
        drawerVm.setMultiSelectActive(false)
    }
    fun enterSelection(conversation: Conversation) {
        selectionMode = true
        selectedIds = setOf(conversation.id)
        drawerVm.setMultiSelectActive(true)
    }
    fun toggleSelected(conversation: Conversation) {
        selectedIds = if (conversation.id in selectedIds) {
            selectedIds - conversation.id
        } else {
            selectedIds + conversation.id
        }
    }
    // 大屏永久抽屉没有外层「关闭抽屉」返回键，这里直接退出多选。
    // 手机上 ChatPage 的返回键注册更晚，会改走 drawerVm.requestExitMultiSelect()。
    BackHandler(enabled = selectionMode) { exitSelection() }
    val exitMultiSelectTick by drawerVm.exitMultiSelectTick.collectAsStateWithLifecycle()
    LaunchedEffect(exitMultiSelectTick) {
        if (exitMultiSelectTick != 0) exitSelection()
    }
    DisposableEffect(Unit) {
        onDispose { drawerVm.setMultiSelectActive(false) }
    }
    LaunchedEffect(selectedFolderId, settings.assistantId) {
        if (selectionMode) exitSelection()
    }
    var folderToRename by remember { mutableStateOf<Folder?>(null) }
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }
    // 与顶部标题点击共享，见 ChatDrawerVM.conversationToRename
    val conversationToRename by drawerVm.conversationToRename.collectAsStateWithLifecycle()

    // Menu popup 状态
    var showMenuPopup by remember { mutableStateOf(false) }

    var bottomBarHeightPx by remember { mutableStateOf(0) }
    val bottomBarHeight = with(LocalDensity.current) { bottomBarHeightPx.toDp() }
    // 上方功能区固定悬浮在顶部，对话列表从其下方滚过；用实测高度给列表留出顶部空间。
    var topAreaHeightPx by remember { mutableStateOf(0) }
    val topAreaHeight = with(LocalDensity.current) { topAreaHeightPx.toDp() }

    // 整块抽屉做成磨砂：面板半透明，背后的聊天页被均匀虚化；上下的通透玻璃卡片与它拉开层次。
    val drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
    val drawerBlurStyle = HazeBlurStyle.Material3 {
        blurRadius(24.dp)
    }
    // 聊天页显示照片壁纸时（API 31+），抽屉里的卡片改成磨砂：模糊背后的页面再叠一层半透明色，
    // 不再是不透明白块。没有壁纸或系统不支持模糊时保持原样。
    val drawerAssistant = settings.getCurrentAssistant()
    // 叠色比全局磨砂更淡（见 DrawerFrost.kt），卡片与面板融为一体，靠细描边区分。
    val drawerFrost = drawerFrostedPlateSpec(
        photoShown = chatPhotoShown(
            effectiveBackground(
                darkTheme = LocalDarkMode.current,
                background = drawerAssistant.background,
                darkBackground = drawerAssistant.darkBackground,
                lightBackground = drawerAssistant.lightBackground,
                mode = drawerAssistant.backgroundMode,
            ),
            drawerAssistant.useGradientBackground,
        ),
        sdkInt = Build.VERSION.SDK_INT,
        darkTheme = LocalDarkMode.current,
    )
    val drawerFrostBorder = if (drawerFrost != null) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = DRAWER_FROST_OUTLINE_ALPHA))
    } else {
        null
    }
    // 抽屉的功能区有两种做法，由 DisplaySetting.drawerCapsules 切换（默认开）：
    //  - 开：没有底板。身份行、两个入口、文件夹、底部动作各自是小胶囊，直接落在磨砂抽屉上；
    //    列表在控件所在的那一段渐隐（drawerFadeEdges），所以不需要卡片去遮挡滚过去的文字。
    //  - 关：回到原来的上下两张浮岛卡片（阴影或磨砂），行为与之前完全一致。
    val drawerCapsules = settings.displaySetting.drawerCapsules
    val topBarShape = RoundedCornerShape(20.dp)

    // 顶部两条提醒卡两种做法下都保留：它们是内容，不是控件底板。
    val drawerBanners: @Composable () -> Unit = {
        BackupReminderCard(
            settings = settings,
            onClick = { navController.navigate(Screen.Backup()) },
            modifier = if (drawerFrost != null) {
                Modifier.frostedPlate(
                    hazeState = hazeState,
                    shape = MaterialTheme.shapes.medium,
                    spec = drawerFrost,
                    selection = WallpaperOnlySelection,
                )
            } else {
                Modifier
            },
            colors = if (drawerFrost != null) {
                CardDefaults.cardColors(containerColor = Color.Transparent)
            } else {
                CardDefaults.cardColors()
            },
        )
    }

    // 身份区（头像在上、昵称在下）与两个常用入口并排。两条路径共用这一块，区别只在于
    // 外面包不包一张卡片，以及入口是圆还是胶囊。
    val drawerChrome: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (drawerCapsules) 0.dp else 16.dp,
                        vertical = if (drawerCapsules) 0.dp else 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (drawerCapsules) 8.dp else 12.dp),
            ) {
                // 昵称本身就是编辑入口（原先那个铅笔图标已去掉，点一下名字即可改名），
                // 所以这里不再额外留一个点击热区，只把无障碍标签挂在昵称上。
                Column(
                    modifier = Modifier.widthIn(max = if (drawerCapsules) 84.dp else 96.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    UIAvatar(
                        name = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                        value = settings.displaySetting.userAvatar,
                        onUpdate = { newAvatar ->
                            vm.updateSettings(
                                settings.copy(
                                    displaySetting = settings.displaySetting.copy(
                                        userAvatar = newAvatar
                                    )
                                )
                            )
                        },
                        modifier = Modifier.size(if (drawerCapsules) 44.dp else 50.dp),
                    )

                    Text(
                        text = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                        style = if (drawerCapsules) {
                            MaterialTheme.typography.labelLarge
                        } else {
                            MaterialTheme.typography.titleSmall
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(
                                onClickLabel = stringResource(R.string.accessibility_edit_nickname),
                            ) {
                                nicknameEditState.open(settings.displaySetting.userNickname)
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                DrawerActions(
                    navController = navController,
                    capsule = drawerCapsules,
                    modifier = Modifier.weight(1f),
                )
            }

            FolderBar(
                protectedFolderId = protectedFolderId,
                folders = folders,
                selectedFolderId = selectedFolderId,
                onSelect = { drawerVm.selectFolder(it) },
                onCreate = {
                    // 文件夹条上的「新建」是普通建夹，不能沿用多选里的批量移入标志。
                    batchMove = false
                    showCreateFolderDialog = true
                },
                onRename = { folderToRename = it },
                onDelete = { folderToDelete = it },
                modifier = Modifier.padding(horizontal = if (drawerCapsules) 0.dp else 12.dp),
            )
        }
    }

    // 上方功能区：固定悬浮在抽屉顶部；对话列表从其下方滚过。
    val drawerTopArea: @Composable () -> Unit = {
        if (drawerCapsules) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                drawerBanners()
                drawerChrome()
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                drawerBanners()

                // 上方功能区：一块 M3 填充卡片（阴影视作悬浮）。这里刻意不用 hazeGlass——两张固定
                // 卡片下方没有可折射的内容（对话列表为它们留了上下 padding），空背板会让玻璃把
                // 自己的边缘渲染成一圈灰边，卡片中心与边缘出现明显色差，所以直接换成纯色卡片。
                // Over a photo (API 31+) the card is frosted instead. No shadow then: it would show
                // through the translucent tint as a grey rim.
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (drawerFrost != null) {
                                Modifier.frostedPlate(
                                    hazeState, topBarShape, drawerFrost,
                                    tint = MaterialTheme.colorScheme.surface,
                                    selection = WallpaperOnlySelection,
                                )
                            } else {
                                Modifier.shadow(4.dp, topBarShape, clip = false)
                            }
                        ),
                    shape = topBarShape,
                    color = if (drawerFrost != null) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = drawerFrostBorder,
                    tonalElevation = 0.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        drawerChrome()
                    }
                }
            }
        }
    }

    ModalDrawerSheet(
        modifier = Modifier
            .width(300.dp)
            .clip(drawerShape)
            .hazeBlur(
                input = HazeInput.Sources(
                    hazeState,
                    selection = WallpaperOnlySelection,
                ),
                style = drawerBlurStyle,
            ),
        drawerShape = drawerShape,
        drawerContainerColor = Color.Transparent,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
        drawerTonalElevation = 0.dp,
    ) {
        // 列表铺满整块，从上下玻璃卡片下方滚过；上方功能区固定悬浮，底部功能区浮在列表之上。
        Box(modifier = Modifier.fillMaxSize()) {
            ConversationList(
                current = current,
                conversations = conversations,
                conversationJobs = conversationJobs.keys,
                listState = conversationListState,
                contentPadding = PaddingValues(top = topAreaHeight + 12.dp, bottom = bottomBarHeight + 12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        // 胶囊路径没有底板挡在列表前面，所以让列表自己在控件所在的那一段淡出；
                        // 卡片路径由卡片遮挡，保持原来的硬边界。
                        if (drawerCapsules) {
                            Modifier.drawerFadeEdges(
                                topFade = topAreaHeight,
                                bottomFade = bottomBarHeight,
                            )
                        } else {
                            Modifier
                        }
                    ),
                onClick = {
                    navigateToChatPage(navController, it.id)
                },
                onRename = {
                    drawerVm.requestRenameConversation(it)
                },
                onDelete = {
                    scope.launch {
                        vm.deleteConversation(it).join()
                        conversations.refresh()
                        if (it.id == current.id) {
                            navigateToChatPage(navController)
                        }
                    }
                },
                onPin = {
                    vm.updatePinnedStatus(it)
                },
                onMoveToAssistant = {
                    conversationToMove = it
                    batchMove = false
                    showMoveToAssistantSheet = true
                },
                onMoveToFolder = {
                    conversationToMoveFolder = it
                    batchMove = false
                    showMoveToFolderSheet = true
                },
                selectionMode = selectionMode,
                selectedIds = selectedIds,
                onToggleSelect = { toggleSelected(it) },
                onEnterSelection = { enterSelection(it) },
            )

            // 上方功能区：固定悬浮在抽屉顶部，对话列表从其下方滚过时被玻璃折射虚化。
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { topAreaHeightPx = it.height },
            ) {
                drawerTopArea()
            }

            // 底部功能区：两种做法与顶部一致——开时是助手胶囊 + 五个动作胶囊，关时回到
            // 原来的 M3 填充卡片浮岛。边距与顶部对称，并避让系统手势导航条。
            val bottomBarShape = RoundedCornerShape(20.dp)
            // 多选栏 / 助手行 + 五个动作：两条路径共用同一份内容，只是外壳不同。
            val drawerBottomContent: @Composable () -> Unit = {
                if (selectionMode) {
                    SelectionActionBar(
                        capsule = drawerCapsules,
                        count = selectedIds.size,
                        onCancel = { exitSelection() },
                        onDelete = {
                            if (selectedIds.isNotEmpty()) showDeleteSelectedDialog = true
                        },
                        onMove = {
                            if (selectedIds.isNotEmpty()) {
                                batchMove = true
                                conversationToMoveFolder = null
                                showMoveToFolderSheet = true
                            }
                        },
                        onCreateAndMove = {
                            if (selectedIds.isNotEmpty()) {
                                batchMove = true
                                showCreateFolderDialog = true
                            }
                        },
                        onMoveToAssistant = {
                            if (selectedIds.isNotEmpty()) {
                                batchMove = true
                                conversationToMove = null
                                showMoveToAssistantSheet = true
                            }
                        },
                    )
                } else {
                    // 助手选择器
                    AssistantPicker(
                        settings = settings,
                        onUpdateSettings = {
                            val updateJob = vm.updateSettings(it)
                            scope.launch {
                                updateJob.join()
                                val id = if (context.readBooleanPreference("create_new_conversation_on_start", true)) {
                                    Uuid.random()
                                } else {
                                    repo.getConversationsOfAssistant(it.assistantId)
                                        .first()
                                        .firstOrNull()
                                        ?.id ?: Uuid.random()
                                }
                                navigateToChatPage(navigator = navController, chatId = id)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                // 胶囊路径：助手行自己就是一条胶囊，直接落在磨砂面板上；卡片路径由外面的
                                // 卡片提供底板，这里不加任何底色。
                                if (drawerCapsules) {
                                    Modifier
                                        .clip(DrawerCapsuleShape)
                                        .background(drawerCapsuleColor())
                                } else {
                                    Modifier
                                }
                            ),
                        onClickSetting = {
                            val currentAssistantId = settings.assistantId
                            navController.navigate(Screen.AssistantDetail(id = currentAssistantId.toString()))
                        }
                    )

                    Row(
                        horizontalArrangement = if (drawerCapsules) {
                            Arrangement.spacedBy(6.dp)
                        } else {
                            Arrangement.SpaceAround
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (drawerCapsules) 0.dp else 4.dp)
                    ) {
                        DrawerAction(
                            modifier = if (drawerCapsules) Modifier.weight(1f) else Modifier,
                            capsule = drawerCapsules,
                            icon = { Icon(HugeIcons.LookTop, null) },
                            label = { Text(stringResource(R.string.assistant_page_title)) },
                            contentDescription = stringResource(R.string.assistant_page_title),
                            onClick = { navController.navigate(Screen.Assistant) },
                        )

                        Box(modifier = if (drawerCapsules) Modifier.weight(1f) else Modifier) {
                            DrawerAction(
                                modifier = if (drawerCapsules) Modifier.fillMaxWidth() else Modifier,
                                capsule = drawerCapsules,
                                icon = { Icon(HugeIcons.Sparkles, null) },
                                label = { Text(stringResource(R.string.menu)) },
                                contentDescription = stringResource(R.string.menu),
                                onClick = { showMenuPopup = true },
                            )
                            AppDropdownMenu(
                                expanded = showMenuPopup,
                                onDismissRequest = { showMenuPopup = false },
                                items = listOf(
                                    AppMenuEntry(
                                        text = stringResource(R.string.chat_page_menu_ai_translator),
                                        icon = HugeIcons.LanguageCircle,
                                        onClick = { navController.navigate(Screen.Translator) },
                                    ),
                                    AppMenuEntry(
                                        text = stringResource(R.string.chat_page_menu_image_generation),
                                        icon = HugeIcons.Image02,
                                        onClick = { navController.navigate(Screen.ImageGen) },
                                    ),
                                    AppMenuEntry(
                                        text = stringResource(R.string.chat_page_menu_video_generation),
                                        icon = HugeIcons.Video01,
                                        onClick = { navController.navigate(Screen.VideoGen) },
                                    ),
                                ),
                            )
                        }

                        DrawerAction(
                            modifier = if (drawerCapsules) Modifier.weight(1f) else Modifier,
                            capsule = drawerCapsules,
                            icon = { Icon(HugeIcons.InLove, null) },
                            label = { Text(stringResource(R.string.favorite_page_title)) },
                            contentDescription = stringResource(R.string.favorite_page_title),
                            onClick = { navController.navigate(Screen.Favorite) },
                        )

                        DrawerAction(
                            modifier = if (drawerCapsules) Modifier.weight(1f) else Modifier,
                            capsule = drawerCapsules,
                            icon = { Icon(HugeIcons.ChartColumn, null) },
                            label = { Text(stringResource(R.string.chat_drawer_statistics)) },
                            contentDescription = stringResource(R.string.chat_drawer_statistics),
                            onClick = { navController.navigate(Screen.Stats) },
                        )

                        DrawerAction(
                            modifier = if (drawerCapsules) Modifier.weight(1f) else Modifier,
                            capsule = drawerCapsules,
                            icon = { Icon(HugeIcons.Settings03, null) },
                            label = { Text(stringResource(R.string.settings)) },
                            contentDescription = stringResource(R.string.settings),
                            onClick = { navController.navigate(Screen.Setting) },
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = if (drawerCapsules) 12.dp else 8.dp, vertical = 8.dp)
                    .navigationBarsPadding()
                    .onSizeChanged { bottomBarHeightPx = it.height },
            ) {
                if (drawerCapsules) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        drawerBottomContent()
                    }
                } else {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (drawerFrost != null) {
                                    Modifier.frostedPlate(
                                        hazeState, bottomBarShape, drawerFrost,
                                        tint = MaterialTheme.colorScheme.surface,
                                        selection = WallpaperOnlySelection,
                                    )
                                } else {
                                    Modifier.shadow(4.dp, bottomBarShape, clip = false)
                                }
                            ),
                        shape = bottomBarShape,
                        color = if (drawerFrost != null) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = drawerFrostBorder,
                        tonalElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            drawerBottomContent()
                        }
                    }
                }
            }
        }
    }

    // 昵称编辑对话框
    nicknameEditState.EditStateContent { nickname, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                nicknameEditState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_nickname))
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到文件夹 Bottom Sheet（单条或批量）
    if (showMoveToFolderSheet) {
        val doMove: (Uuid?) -> Unit = { folderId ->
            val ids = if (batchMove) selectedIds.toList() else listOfNotNull(conversationToMoveFolder?.id)
            if (ids.isNotEmpty()) {
                if (batchMove) {
                    drawerVm.moveConversationsToFolder(ids, folderId)
                } else {
                    drawerVm.moveConversationToFolder(ids.first(), folderId)
                }
                scope.launch {
                    folderSheetState.hide()
                    showMoveToFolderSheet = false
                    conversationToMoveFolder = null
                    if (batchMove) exitSelection()
                    conversations.refresh()
                }
            }
        }
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToFolderSheet = false
                conversationToMoveFolder = null
                batchMove = false
            },
            sheetState = folderSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_folder),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (batchMove) {
                    Text(
                        text = stringResource(R.string.chat_page_selected_count, selectedIds.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 移出文件夹（未归类）
                Surface(
                    onClick = { doMove(null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (conversationToMoveFolder?.folderId == null) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(HugeIcons.Folder01, null)
                        Text(
                            text = stringResource(R.string.chat_page_remove_from_folder),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(folders) { folder ->
                        val isCurrent = folder.id == conversationToMoveFolder?.folderId
                        Surface(
                            onClick = { doMove(folder.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = if (isCurrent) 2.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(HugeIcons.Folder01, null)
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建文件夹对话框
    if (showCreateFolderDialog) {
        var name by remember { mutableStateOf("") }
        var archive by remember { mutableStateOf(false) }
        var submitting by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {
                if (submitting) return@AlertDialog
                showCreateFolderDialog = false
                batchMove = false
            },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.chat_page_create_folder))
                    if (batchMove) {
                        Text(
                            text = stringResource(R.string.chat_page_selected_count, selectedIds.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.chat_page_folder_name)) }
                    )
                    // P2-23 (D7) - mark this folder as the assistant's sub-agent archive.
                    // One folder per assistant; an empty one stays deletable.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_page_folder_purpose_sub_agent),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = archive, onCheckedChange = { archive = it })
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (submitting) return@TextButton
                        if (batchMove && selectedIds.isNotEmpty()) {
                            submitting = true
                            val ids = selectedIds.toList()
                            scope.launch {
                                try {
                                    drawerVm.createFolderAndMoveConversations(name, ids, archive).join()
                                    showCreateFolderDialog = false
                                    exitSelection()
                                    conversations.refresh()
                                } finally {
                                    submitting = false
                                }
                            }
                        } else {
                            drawerVm.createFolder(name, archive)
                            showCreateFolderDialog = false
                        }
                    },
                    enabled = name.isNotBlank() && !submitting
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (submitting) return@TextButton
                        showCreateFolderDialog = false
                        batchMove = false
                    },
                    enabled = !submitting,
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 重命名文件夹对话框
    folderToRename?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        var archive by remember(folder.id) { mutableStateOf(archiveFolderId == folder.id) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.chat_page_rename_folder)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_page_folder_purpose_sub_agent),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = archive, onCheckedChange = { archive = it })
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.renameFolder(folder.id, name, archive)
                        folderToRename = null
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 重命名会话对话框（顶部标题点击与本抽屉的 Rename 菜单项共享同一个目标状态）
    conversationToRename?.let { conversation ->
        var title by remember(conversation.id) { mutableStateOf(conversation.title) }
        AlertDialog(
            onDismissRequest = { drawerVm.dismissRenameConversation() },
            title = { Text(stringResource(R.string.chat_page_rename_chat)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            vm.generateTitle(conversation, true)
                            drawerVm.dismissRenameConversation()
                        }
                    ) {
                        Text(stringResource(R.string.chat_page_regenerate_title))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.renameConversation(conversation.id, title)
                    },
                    enabled = title.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { drawerVm.dismissRenameConversation() }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 批量删除确认
    if (showDeleteSelectedDialog) {
        var submitting by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {
                if (!submitting) showDeleteSelectedDialog = false
            },
            title = { Text(stringResource(R.string.chat_page_delete_selected_title)) },
            text = {
                Text(stringResource(R.string.chat_page_delete_selected_confirm, selectedIds.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (submitting || selectedIds.isEmpty()) return@TextButton
                        submitting = true
                        val ids = selectedIds.toList()
                        val deletingCurrent = current.id in ids
                        scope.launch {
                            try {
                                drawerVm.deleteConversations(ids).join()
                                showDeleteSelectedDialog = false
                                exitSelection()
                                conversations.refresh()
                                if (deletingCurrent) navigateToChatPage(navController)
                            } finally {
                                submitting = false
                            }
                        }
                    },
                    enabled = selectedIds.isNotEmpty() && !submitting,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.chat_page_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { if (!submitting) showDeleteSelectedDialog = false },
                    enabled = !submitting,
                ) { Text(stringResource(R.string.chat_page_cancel)) }
            },
        )
    }

    // 删除文件夹确认
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.chat_page_delete_folder)) },
            text = { Text(stringResource(R.string.chat_page_delete_folder_confirm, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            when (drawerVm.deleteFolder(folder.id)) {
                                FolderDeleteOutcome.Deleted -> {
                                    folderToDelete = null
                                    conversations.refresh()
                                }
                                FolderDeleteOutcome.Generating ->
                                    toaster.show(context.getString(R.string.chat_page_delete_folder_generating), type = ToastType.Warning)
                                FolderDeleteOutcome.Protected ->
                                    toaster.show(context.getString(R.string.chat_page_delete_folder_protected), type = ToastType.Warning)
                            }
                        }
                    }
                ) { Text(stringResource(R.string.chat_page_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到助手 Bottom Sheet（单条或批量）
    if (showMoveToAssistantSheet) {
        val currentAssistantId = if (batchMove) {
            // 批量时来源可能跨助手，用当前打开的会话做高亮参考
            current.assistantId
        } else {
            conversationToMove?.assistantId
        }
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToAssistantSheet = false
                conversationToMove = null
                batchMove = false
            },
            sheetState = bottomSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_assistant),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (batchMove) {
                    Text(
                        text = stringResource(R.string.chat_page_selected_count, selectedIds.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(settings.assistants, key = { it.id }) { assistant ->
                        AssistantItem(
                            assistant = assistant,
                            isCurrentAssistant = assistant.id == currentAssistantId,
                            onClick = {
                                if (batchMove) {
                                    val ids = selectedIds.toList()
                                    if (ids.isNotEmpty()) {
                                        val movedCurrent = current.id in ids
                                        scope.launch {
                                            drawerVm.moveConversationsToAssistant(ids, assistant.id).join()
                                            // 当前会话被移走时切到目标助手，和单条移动一致
                                            if (movedCurrent) drawerVm.switchAssistant(assistant.id)
                                            bottomSheetState.hide()
                                            showMoveToAssistantSheet = false
                                            conversationToMove = null
                                            exitSelection()
                                            conversations.refresh()
                                        }
                                    }
                                } else {
                                    conversationToMove?.let { conversation ->
                                        vm.moveConversationToAssistant(conversation, assistant.id)
                                        scope.launch {
                                            bottomSheetState.hide()
                                            showMoveToAssistantSheet = false
                                            conversationToMove = null
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionActionBar(
    count: Int,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onCreateAndMove: () -> Unit,
    onMoveToAssistant: () -> Unit,
    capsule: Boolean = false,
) {
    val canAct = count > 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                // 胶囊路径下没有外层卡片，多选栏自己带一层底板，免得按钮直接压在壁纸上。
                if (capsule) {
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(drawerCapsuleColor())
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                } else {
                    Modifier
                }
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.chat_page_exit_multi_select))
            }
            Text(
                text = stringResource(R.string.chat_page_selected_count, count),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDelete,
                enabled = canAct,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(HugeIcons.Delete01, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.chat_page_delete),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 三个动作等宽排一行，字号降到 labelMedium，窄抽屉里也能放下完整文案。
            TextButton(
                onClick = onMove,
                enabled = canAct,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Icon(HugeIcons.Folder01, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    text = stringResource(R.string.chat_page_move_to_folder),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            TextButton(
                onClick = onCreateAndMove,
                enabled = canAct,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Icon(HugeIcons.FolderAdd, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    text = stringResource(R.string.chat_page_create_folder),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            TextButton(
                onClick = onMoveToAssistant,
                enabled = canAct,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Icon(HugeIcons.LookTop, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    text = stringResource(R.string.chat_page_move_to_assistant),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun DrawerActions(
    navController: Navigator,
    modifier: Modifier = Modifier,
    capsule: Boolean = false,
) {
    // 两个常用入口各占一格。卡片路径下图标在上、文字在下；胶囊路径下合成横向小胶囊
    // （图标 + 文字），和下面那排文件夹胶囊、底部那排动作胶囊是同一种形状。
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (capsule) 6.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrawerShortcut(
            icon = HugeIcons.Search01,
            label = stringResource(R.string.chat_page_search_chats),
            onClick = { navController.navigate(Screen.MessageSearch) },
            modifier = Modifier.weight(1f),
            capsule = capsule,
        )
        DrawerShortcut(
            icon = HugeIcons.TransactionHistory,
            label = stringResource(R.string.chat_page_history),
            onClick = { navController.navigate(Screen.History) },
            modifier = Modifier.weight(1f),
            capsule = capsule,
        )
    }
}

@Composable
private fun DrawerShortcut(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    capsule: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .then(if (capsule) Modifier.height(40.dp) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = label
            },
        shape = if (capsule) DrawerCapsuleShape else MaterialTheme.shapes.medium,
        color = if (capsule) drawerCapsuleColor() else Color.Transparent,
    ) {
        if (capsule) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DrawerAction(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    contentDescription: String? = null,
    onClick: () -> Unit,
    capsule: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .then(if (capsule) Modifier.height(40.dp) else Modifier)
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        color = if (capsule) drawerCapsuleColor() else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = if (capsule) DrawerCapsuleShape else CircleShape,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Tooltip(
            tooltip = {
                label()
            }
        ) {
            Box(
                modifier = if (capsule) {
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                } else {
                    Modifier
                        .padding(9.dp)
                        .size(20.dp)
                },
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        }
    }
}

@Composable
private fun FolderBar(
    folders: List<Folder>,
    protectedFolderId: Uuid?,
    selectedFolderId: Uuid?,
    onSelect: (Uuid?) -> Unit,
    onCreate: () -> Unit,
    onRename: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            FolderChip(
                label = stringResource(R.string.chat_page_folder_default),
                selected = selectedFolderId == null,
                onClick = { onSelect(null) },
                onLongClick = {},
            )
        }
        items(folders) { folder ->
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                FolderChip(
                    label = folder.name,
                    icon = HugeIcons.Folder01,
                    selected = selectedFolderId == folder.id,
                    onClick = { onSelect(folder.id) },
                    onLongClick = { menuExpanded = true },
                )
                // P2-23 (D7) - the sub-agent archive folder cannot be deleted while it
                // holds conversations; the option is absent rather than failing on tap.
                val folderItems = buildList {
                    add(
                        AppMenuEntry(
                            text = stringResource(R.string.chat_page_rename),
                            icon = HugeIcons.PencilEdit01,
                            onClick = { onRename(folder) },
                        )
                    )
                    if (folder.id != protectedFolderId) {
                        add(
                            AppMenuEntry(
                                text = stringResource(R.string.chat_page_delete),
                                icon = HugeIcons.Delete01,
                                onClick = { onDelete(folder) },
                            )
                        )
                    }
                }
                AppDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    items = folderItems,
                )
            }
        }
        item {
            FolderChip(
                label = stringResource(R.string.chat_page_folder_add),
                icon = HugeIcons.FolderAdd,
                selected = false,
                onClick = onCreate,
                onLongClick = {},
            )
        }
    }
}

@Composable
private fun FolderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    icon: ImageVector? = null,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            Color.Transparent
        },
        modifier = Modifier
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(14.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    isCurrentAssistant: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrentAssistant) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isCurrentAssistant) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AssistantAvatar(
                assistant = assistant,
                name = assistant.name,
                onUpdate = {},
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrentAssistant) {
                    Text(
                        text = stringResource(R.string.assistant_page_current_assistant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 抽屉控件胶囊。
 *
 * 抽屉的功能区不再是一张盖住列表的卡片：身份、入口、文件夹、底部动作都是这种小胶囊，
 * 直接落在磨砂面板上。形状只在这里定义一次，两种路径之外的控件都从这里取。
 */
private val DrawerCapsuleShape = RoundedCornerShape(percent = 50)

/** 胶囊底色。比面板亮一档，但不加描边和阴影——它不该再读成一张卡。 */
@Composable
private fun drawerCapsuleColor(): Color = MaterialTheme.colorScheme.surfaceContainerHighest

/**
 * 让列表在顶部/底部控件所在的那一段淡出。
 *
 * 卡片被拿掉以后，滚过去的文字会直接穿过胶囊之间的缝隙。这里用 DstIn 只擦列表自己的像素，
 * 所以不需要知道背后是纯色面板还是照片壁纸；换成一层渐变色盖上去，浅色主题下又会变成
 * 之前在聊天页顶部见过的那种白雾。
 */
private fun Modifier.drawerFadeEdges(topFade: Dp, bottomFade: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (size.height <= 0f) return@drawWithContent
        val top = topFade.toPx().coerceIn(0f, size.height)
        val bottom = bottomFade.toPx().coerceIn(0f, size.height)
        if (top <= 0f && bottom <= 0f) return@drawWithContent
        val end = (size.height - bottom).coerceIn(top, size.height)
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                top / size.height to Color.White,
                end / size.height to Color.White,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
