package com.clma.app.ui.pages.setting

import androidx.annotation.StringRes
import com.clma.app.R
import com.clma.app.Screen

/** A user-created item (a workspace, a skill, a provider …) shown as a settings search result. */
data class SettingsSearchDynamicEntry(
    val title: String,
    val subtitle: String?,
    @param:StringRes override val groupRes: Int,
    override val breadcrumbRes: List<Int>,
    override val route: Screen,
) : SettingsSearchItem

/** Plain snapshot of one user item; [id] is whatever the detail route needs. */
data class SettingsSearchUserItem(
    val id: String,
    val name: String,
    val detail: String? = null,
)

/**
 * Plain snapshot of user-created content. Kept free of Android / storage types so the mapping
 * below stays a pure function that unit tests can drive.
 */
data class SettingsSearchUserContent(
    val workspaces: List<SettingsSearchUserItem> = emptyList(),
    val skills: List<SettingsSearchUserItem> = emptyList(),
    val quickMessages: List<SettingsSearchUserItem> = emptyList(),
    val modeInjections: List<SettingsSearchUserItem> = emptyList(),
    val lorebooks: List<SettingsSearchUserItem> = emptyList(),
    val mcpServers: List<SettingsSearchUserItem> = emptyList(),
    val providers: List<SettingsSearchUserItem> = emptyList(),
    val searchServices: List<SettingsSearchUserItem> = emptyList(),
    val assistants: List<SettingsSearchUserItem> = emptyList(),
    val ttsProviders: List<SettingsSearchUserItem> = emptyList(),
    val asrProviders: List<SettingsSearchUserItem> = emptyList(),
)

/**
 * Turns user content into search results. Each kind is anchored to the static index entry of
 * the page (or tab) that lists it, so its group and breadcrumb come from the same page tree as
 * everything else; a kind whose anchor is missing is dropped rather than shown without a path.
 *
 * Tapping goes to the item's detail page where one exists (workspace, skill, provider, search
 * service, assistant), otherwise to the list page — on the right tab for lorebooks, mode
 * injections and TTS / ASR providers.
 */
fun settingsSearchDynamicEntries(
    content: SettingsSearchUserContent,
    index: List<SettingsSearchEntry>,
): List<SettingsSearchDynamicEntry> {
    val out = mutableListOf<SettingsSearchDynamicEntry>()

    /** [titleRes] null = the hub row for [route]; otherwise the page / tab entry with that title. */
    fun add(
        items: List<SettingsSearchUserItem>,
        listRoute: Screen,
        @StringRes titleRes: Int?,
        target: (SettingsSearchUserItem) -> Screen,
    ) {
        if (items.isEmpty()) return
        val anchor = index.firstOrNull { entry ->
            entry.route == listRoute &&
                (if (titleRes == null) entry.parentTitleRes == null else entry.titleRes == titleRes)
        } ?: return
        val breadcrumb = anchor.breadcrumbRes + anchor.titleRes
        for (item in items) {
            if (item.name.isBlank()) continue
            out += SettingsSearchDynamicEntry(
                title = item.name,
                subtitle = item.detail?.takeIf { it.isNotBlank() },
                groupRes = anchor.groupRes,
                breadcrumbRes = breadcrumb,
                route = target(item),
            )
        }
    }

    add(content.workspaces, Screen.Workspaces, R.string.extensions_page_workspace) { Screen.WorkspaceDetail(it.id) }
    add(content.skills, Screen.Skills, R.string.extensions_page_agent_skills) { Screen.SkillDetail(skillName = it.id) }
    add(content.quickMessages, Screen.QuickMessages, R.string.assistant_page_quick_messages) { Screen.QuickMessages }
    add(content.modeInjections, Screen.Prompts(initialTab = 0), R.string.prompt_page_mode_injection_tab) { Screen.Prompts(initialTab = 0) }
    add(content.lorebooks, Screen.Prompts(initialTab = 1), R.string.prompt_page_lorebook_tab) { Screen.Prompts(initialTab = 1) }
    add(content.mcpServers, Screen.SettingMcp, null) { Screen.SettingMcp }
    add(content.providers, Screen.SettingProvider, null) { Screen.SettingProviderDetail(providerId = it.id) }
    add(content.searchServices, Screen.SettingSearch, null) { Screen.SettingSearchDetail(serviceId = it.id) }
    add(content.assistants, Screen.Assistant, null) { Screen.AssistantDetail(id = it.id) }
    add(content.ttsProviders, Screen.SettingSpeech(initialTab = 0), R.string.speech_tab_tts) { Screen.SettingSpeech(initialTab = 0) }
    add(content.asrProviders, Screen.SettingSpeech(initialTab = 1), R.string.speech_tab_asr) { Screen.SettingSpeech(initialTab = 1) }

    return out
}
