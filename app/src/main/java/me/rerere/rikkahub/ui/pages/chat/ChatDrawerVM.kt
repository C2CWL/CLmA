package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.subagent.SubAgentArchiveRules
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

/** P2-23 (D7) — why a folder delete did or did not happen, so the UI can explain itself. */
enum class FolderDeleteOutcome { Deleted, Generating, Protected }

class ChatDrawerVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val chatService: ChatService,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val assistantIdFlow = settingsStore.settingsFlow
        .map { it.assistantId }
        .distinctUntilChanged()

    // 当前选中的文件夹筛选，null 表示「未归类」视图
    private val _selectedFolderId = MutableStateFlow<Uuid?>(null)
    val selectedFolderId: StateFlow<Uuid?> = _selectedFolderId.asStateFlow()

    // 重命名对话框的目标会话，跨抽屉的两个入口（菜单项 + 顶部标题点击）共享，
    // 保证两者打开同一个对话框、走同一条提交路径
    private val _conversationToRename = MutableStateFlow<Conversation?>(null)
    val conversationToRename: StateFlow<Conversation?> = _conversationToRename.asStateFlow()

    // 当前助手的文件夹列表（Room Flow，增删改自动刷新）
    val folders: StateFlow<List<Folder>> = assistantIdFlow
        .flatMapLatest { folderRepo.getFoldersOfAssistant(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: Flow<PagingData<ConversationListItem>> =
        combine(assistantIdFlow, _selectedFolderId) { assistantId, folderId ->
            assistantId to folderId
        }
            .flatMapLatest { (assistantId, folderId) ->
                if (folderId == null) {
                    conversationRepo.getUnfiledConversationsOfAssistantPaging(assistantId)
                } else {
                    conversationRepo.getConversationsOfFolderPaging(folderId)
                }
            }
            .map { pagingData ->
                pagingData
                    .map { ConversationListItem.Item(it) }
                    .insertSeparators<ConversationListItem.Item, ConversationListItem> { before, after ->
                        when {
                            before == null && after is ConversationListItem.Item -> {
                                if (after.conversation.isPinned) {
                                    ConversationListItem.PinnedHeader
                                } else {
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                }
                            }

                            before is ConversationListItem.Item && after is ConversationListItem.Item -> {
                                if (before.conversation.isPinned && !after.conversation.isPinned) {
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                } else if (!after.conversation.isPinned) {
                                    val beforeDate = before.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()

                                    if (beforeDate != afterDate) {
                                        ConversationListItem.DateHeader(
                                            date = afterDate,
                                            label = getDateLabel(afterDate)
                                        )
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                            }

                            else -> null
                        }
                    }
            }
            .cachedIn(viewModelScope)

    val scrollIndex: Int get() = savedStateHandle["scrollIndex"] ?: 0
    val scrollOffset: Int get() = savedStateHandle["scrollOffset"] ?: 0

    init {
        // 助手切换时重置文件夹筛选，回到「聊天」视图，
        // 避免继续显示上一个助手文件夹内的会话（文件夹是助手内分组）
        viewModelScope.launch {
            assistantIdFlow.collect {
                _selectedFolderId.value = null
            }
        }
    }

    fun saveScrollPosition(index: Int, offset: Int) {
        savedStateHandle["scrollIndex"] = index
        savedStateHandle["scrollOffset"] = offset
    }

    fun selectFolder(folderId: Uuid?) {
        _selectedFolderId.value = folderId
    }

    /**
     * P2-23 (D7) — create a folder, optionally marking it as this assistant's sub-agent archive.
     * Marking writes the same single slot the assistant settings screen writes.
     */
    fun createFolder(name: String, archive: Boolean = false) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val assistantId = assistantIdFlow.first()
            val folder = folderRepo.createFolder(assistantId, trimmed)
            if (archive) setArchiveFolder(assistantId, folder.id)
        }
    }

    fun renameFolder(folderId: Uuid, name: String, archive: Boolean = false) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            folderRepo.renameFolder(folderId, trimmed)
            val assistantId = assistantIdFlow.first()
            val current = settingsStore.settingsFlow.value.subAgentArchiveFolders[assistantId.toString()]
            // Only touch the archive slot when this choice concerns it: turning it on sets the
            // slot; turning it off clears it ONLY if this folder owned it, so toggling an
            // unrelated folder's switch cannot silently unset the real one.
            when {
                archive -> setArchiveFolder(assistantId, folderId)
                current == folderId.toString() -> setArchiveFolder(assistantId, null)
            }
        }
    }

    /** Write (or clear, with a null [folderId]) the assistant's archive-folder slot. */
    private suspend fun setArchiveFolder(assistantId: Uuid, folderId: Uuid?) {
        settingsStore.update { s ->
            val key = assistantId.toString()
            val next = s.subAgentArchiveFolders.toMutableMap()
            if (folderId == null) next.remove(key) else next[key] = folderId.toString()
            s.copy(subAgentArchiveFolders = next)
        }
    }

    /** The assistant's archive folder, or null. Pre-fills the create/rename dialogs. */
    val archiveFolderId: StateFlow<Uuid?> =
        combine(settingsStore.settingsFlow, assistantIdFlow) { s, a ->
            SubAgentArchiveRules.targetFolderId(s.subAgentArchiveFolders, a.toString())
                ?.let { Uuid.parse(it) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * The archive folder once it holds a conversation — the one folder the UI must not let the
     * user delete. Null when nothing is marked, or the marked one is empty (an empty mistake is
     * still removable).
     */
    val protectedFolderId: StateFlow<Uuid?> = archiveFolderId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else conversationRepo.countInFolderFlow(id).map { count -> if (count > 0) id else null }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 删除文件夹。
     *
     * P2-23 (D7)：若该文件夹是当前助手的「子智能体归档」目标**且里面已有对话**，拒绝删除
     * （[FolderDeleteOutcome.Protected]）——空归档夹不受保护，误建的仍可删。文件夹内有正在生成
     * 的会话同样拒绝（[FolderDeleteOutcome.Generating]）。两者都返回原因，UI 据此提示。
     */
    suspend fun deleteFolder(folderId: Uuid): FolderDeleteOutcome {
        if (chatService.hasGeneratingConversationInFolder(folderId)) {
            return FolderDeleteOutcome.Generating
        }
        val assistantId = assistantIdFlow.first()
        val isTarget =
            settingsStore.settingsFlow.value.subAgentArchiveFolders[assistantId.toString()] == folderId.toString()
        if (SubAgentArchiveRules.isProtected(isTarget, folderRepo.countConversations(folderId))) {
            return FolderDeleteOutcome.Protected
        }
        // 归档目标被删（空夹可删）时顺手清掉设置里的目标，避免下次派发写进悬空 folder_id
        if (isTarget) setArchiveFolder(assistantId, null)
        // 经 ChatService 删除：会同步清空活跃 session 内存态的 folderId，避免整对象保存写回已删文件夹
        chatService.deleteFolder(folderId)
        if (_selectedFolderId.value == folderId) {
            _selectedFolderId.value = null
        }
        return FolderDeleteOutcome.Deleted
    }

    fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        viewModelScope.launch {
            // 经 ChatService 移动：活跃会话会先同步内存态，避免后续整对象保存覆盖 folder_id
            chatService.moveConversationToFolder(conversationId, folderId)
        }
    }

    fun moveConversationsToFolder(conversationIds: Collection<Uuid>, folderId: Uuid?) {
        if (conversationIds.isEmpty()) return
        viewModelScope.launch {
            chatService.moveConversationsToFolder(conversationIds, folderId)
        }
    }

    /**
     * 抽屉多选是否进行中。ChatPage 的返回键比抽屉内容更晚注册，必须读这个标志，
     * 否则返回会直接关掉抽屉而不是退出多选。
     */
    private val _multiSelectActive = MutableStateFlow(false)
    val multiSelectActive: StateFlow<Boolean> = _multiSelectActive.asStateFlow()

    private val _exitMultiSelectTick = MutableStateFlow(0)
    val exitMultiSelectTick: StateFlow<Int> = _exitMultiSelectTick.asStateFlow()

    fun setMultiSelectActive(active: Boolean) {
        _multiSelectActive.value = active
    }

    /** 由外层返回键调用；抽屉收集 [exitMultiSelectTick] 后清掉本地选中态。 */
    fun requestExitMultiSelect() {
        _exitMultiSelectTick.value = _exitMultiSelectTick.value + 1
    }

    /**
     * 新建文件夹并将所选会话移入，然后切到该文件夹，方便确认归档结果。
     * 返回 [kotlinx.coroutines.Job] 便于 UI 在完成后 refresh / 退出多选。
     */
    fun createFolderAndMoveConversations(
        name: String,
        conversationIds: Collection<Uuid>,
        archive: Boolean = false,
    ): kotlinx.coroutines.Job {
        val trimmed = name.trim()
        val ids = conversationIds.distinct()
        return viewModelScope.launch {
            if (trimmed.isEmpty() || ids.isEmpty()) return@launch
            val assistantId = assistantIdFlow.first()
            val folder = folderRepo.createFolder(assistantId, trimmed)
            if (archive) setArchiveFolder(assistantId, folder.id)
            chatService.moveConversationsToFolder(ids, folder.id)
            selectFolder(folder.id)
        }
    }

    fun requestRenameConversation(conversation: Conversation) {
        _conversationToRename.value = conversation
    }

    fun dismissRenameConversation() {
        _conversationToRename.value = null
    }

    fun renameConversation(conversationId: Uuid, title: String) {
        val trimmed = title.trim()
        _conversationToRename.value = null
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // 经 ChatService 重命名：活跃会话会先同步内存态，避免后续整对象保存覆盖标题
            chatService.renameConversation(conversationId, trimmed)
        }
    }

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }
}
