package me.rerere.rikkahub.ui.pages.assistant.detail

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.AssistantResolver
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.subagent.SubAgentArchiveRules
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val mcpServerConfigs = settingsStore
        .settingsFlow.map { settings ->
            settings.mcpServers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            AssistantResolver.byId(settings, assistantId) ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = Assistant()
        )

    val memories = assistant
        .flatMapLatest { currentAssistant ->
            if (currentAssistant.useGlobalMemory) {
                memoryRepository.getGlobalMemoriesFlow()
            } else {
                memoryRepository.getMemoriesOfAssistantFlow(assistantId.toString())
            }
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val providers = settingsStore
        .settingsFlow
        .map { settings ->
            settings.providers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val tags = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistantTags
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository
        .listFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    /** P2-24 (D7) — this assistant's conversation folders, for the archive picker. */
    val folders: StateFlow<List<Folder>> = folderRepository
        .getFoldersOfAssistant(assistantId)
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList())

    /**
     * P2-24 (D7) — the folder this assistant's sub-agent conversations are filed into, if any.
     * The same single slot the chat drawer's folder dialogs write.
     */
    val subAgentArchiveFolderId: StateFlow<Uuid?> = settingsStore
        .settingsFlow
        .map { settings ->
            SubAgentArchiveRules.targetFolderId(settings.subAgentArchiveFolders, assistantId.toString())
                ?.let { Uuid.parse(it) }
        }
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = null)

    /** P2-24 (D7) — set (or clear, with a null [folderId]) this assistant's archive folder. */
    fun setSubAgentArchiveFolder(folderId: Uuid?) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                val key = assistantId.toString()
                val next = settings.subAgentArchiveFolders.toMutableMap()
                if (folderId == null) next.remove(key) else next[key] = folderId.toString()
                settings.copy(subAgentArchiveFolders = next)
            }
        }
    }

    fun updateTags(tagIds: List<Uuid>, tags: List<Tag>) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings = settings.copy(
                    assistantTags = tags
                )
            )
            update(
                assistant.value.copy(
                    tags = tagIds.toList()
                )
            )
            Log.d(TAG, "updateTags: ${tagIds.joinToString(",")}")
            cleanupUnusedTags()
        }
    }

    fun cleanupUnusedTags() {
        viewModelScope.launch {
            val settings = settings.value
            val validTagIds = settings.assistantTags.map { it.id }.toSet()

            // 清理 assistant 中的无效 tag id
            val cleanedAssistants = settings.assistants.map { assistant ->
                val validTags = assistant.tags.filter { tagId ->
                    validTagIds.contains(tagId)
                }
                if (validTags.size != assistant.tags.size) {
                    assistant.copy(tags = validTags)
                } else {
                    assistant
                }
            }

            // 获取清理后的 assistant 中使用的 tag id
            val usedTagIds = cleanedAssistants.flatMap { it.tags }.toSet()

            // 清理未使用的 tags
            val cleanedTags = settings.assistantTags.filter { tag ->
                usedTagIds.contains(tag.id)
            }

            // 检查是否需要更新
            val needUpdateAssistants = cleanedAssistants != settings.assistants
            val needUpdateTags = cleanedTags.size != settings.assistantTags.size

            if (needUpdateAssistants || needUpdateTags) {
                settingsStore.update(
                    settings = settings.copy(
                        assistants = cleanedAssistants,
                        assistantTags = cleanedTags
                    )
                )
            }
        }
    }

    fun update(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings = settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            checkAvatarDelete(old = it, new = assistant) // 删除旧头像
                            checkBackgroundDelete(old = it, new = assistant) // 删除旧背景
                            assistant
                        } else {
                            it
                        }
                    })
            )
        }
    }

    /**
     * Atomic transform-based update for the active assistant. Use this for any rapid
     * mutator (per-tool toggles, per-skill toggles, per-MCP toggles) where two taps in
     * quick succession would otherwise both snapshot the SAME stale Assistant from
     * `assistant.value` — last writer winning would silently drop the earlier toggle.
     *
     * The transform runs INSIDE [SettingsStore.update]'s mutex so concurrent calls
     * serialise; each transform sees the result of the previous one. Avatar / background
     * cleanup runs against the genuinely-prior assistant (read inside the lock).
     */
    fun updateAssistant(transform: (Assistant) -> Assistant) {
        viewModelScope.launch {
            settingsStore.update { current ->
                val prior = AssistantResolver.byId(current, assistantId)
                    ?: return@update current
                val next = transform(prior)
                checkAvatarDelete(old = prior, new = next)
                checkBackgroundDelete(old = prior, new = next)
                current.copy(
                    assistants = current.assistants.map {
                        if (it.id == assistantId) next else it
                    }
                )
            }
        }
    }

    fun addMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            val memoryAssistantId = if (assistant.value.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryRepository.addMemory(
                assistantId = memoryAssistantId,
                content = memory.content
            )
        }
    }

    fun updateMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            runCatching {
                memoryRepository.updateContent(id = memory.id, content = memory.content)
            }.onFailure {
                // The record may have been deleted (e.g. by the memory tool) between opening
                // the editor and saving; don't crash the VM scope, the update is moot.
                Log.e(TAG, "Failed to update memory #${memory.id}", it)
            }
        }
    }

    fun deleteMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id = memory.id)
        }
    }

    fun checkAvatarDelete(old: Assistant, new: Assistant) {
        if (old.avatar is Avatar.Image && old.avatar != new.avatar) {
            filesManager.deleteChatFiles(listOf(old.avatar.url.toUri()))
        }
    }

    fun checkBackgroundDelete(old: Assistant, new: Assistant) {
        // Clean up any per-theme wallpapers that were removed or changed.
        val oldBackgrounds = setOf(old.background, old.darkBackground, old.lightBackground)
        val newBackgrounds = setOf(new.background, new.darkBackground, new.lightBackground)
        val removed = oldBackgrounds - newBackgrounds

        for (bg in removed) {
            if (bg == null) continue
            try {
                val uri = bg.toUri()
                if (uri.scheme == "content" || uri.scheme == "file") {
                    filesManager.deleteChatFiles(listOf(uri))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete background file: $bg", e)
            }
        }
    }
}
