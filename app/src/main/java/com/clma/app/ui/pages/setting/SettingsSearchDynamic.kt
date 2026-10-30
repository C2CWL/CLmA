package com.clma.app.ui.pages.setting

import androidx.annotation.StringRes
import com.clma.app.R
import com.clma.app.Screen

/** A user-created item (a workspace, a skill, a provider …) shown as a settings search result. */
data class SettingsSearchDynamicEntry(
    val title: String,
    val subtitle: String?,
    /**
     * Page title resolved at search time, in the UI language and in the English fallback.
     * Zero when [title] is already the user-facing text (a workspace name, an assistant name).
     */
    @param:StringRes val titleRes: Int = 0,
    /** Extra searchable copy, not shown. Zero when there is none. */
    @param:StringRes val descriptionRes: Int = 0,
    @param:StringRes override val groupRes: Int,
    override val breadcrumbRes: List<Int>,
    override val route: Screen,
) : SettingsSearchItem

/** One assistant sub-page. [route] receives the assistant id. */
internal data class AssistantSearchSubPage(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val route: (String) -> Screen,
)

/** Sub-pages of an assistant, in the same order as the assistant detail screen. */
internal val assistantSearchSubPages: List<AssistantSearchSubPage> = listOf(
    AssistantSearchSubPage(R.string.assistant_page_tab_basic, R.string.assistant_detail_basic_desc) { Screen.AssistantBasic(it) },
    AssistantSearchSubPage(R.string.assistant_page_tab_prompt, R.string.assistant_detail_prompt_desc) { Screen.AssistantPrompt(it) },
    AssistantSearchSubPage(R.string.assistant_page_tab_extensions, R.string.assistant_detail_extensions_desc) { Screen.AssistantInjections(it) },
    AssistantSearchSubPage(R.string.assistant_page_tab_memory, R.string.assistant_detail_memory_desc) { Screen.AssistantMemory(it) },
    AssistantSearchSubPage(R.string.assistant_page_tab_request, R.string.assistant_detail_request_desc) { Screen.AssistantRequest(it) },
    AssistantSearchSubPage(R.string.assistant_page_tab_mcp, R.string.assistant_detail_mcp_desc) { Screen.AssistantMcp(it) },
    AssistantSearchSubPage(R.string.assistant_detail_ssh_title, R.string.assistant_detail_ssh_desc) { Screen.AssistantSsh(it) },
    AssistantSearchSubPage(R.string.assistant_page_tab_local_tools, R.string.assistant_detail_local_tools_desc) { Screen.AssistantLocalTool(it) },
)

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
 *
 * Each assistant also yields one result per sub-page. The headline is the sub-page title (so
 * "记忆" or "SSH" matches) and the subtitle is the assistant name; tapping opens that page for
 * that assistant.
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

    fun addAssistantSubPages(items: List<SettingsSearchUserItem>) {
        if (items.isEmpty()) return
        val anchor = index.firstOrNull { entry ->
            entry.route == Screen.Assistant && entry.parentTitleRes == null
        } ?: return
        val breadcrumb = anchor.breadcrumbRes + anchor.titleRes
        for (item in items) {
            if (item.name.isBlank()) continue
            for (page in assistantSearchSubPages) {
                out += SettingsSearchDynamicEntry(
                    title = "",
                    subtitle = item.name,
                    titleRes = page.titleRes,
                    descriptionRes = page.descriptionRes,
                    groupRes = anchor.groupRes,
                    breadcrumbRes = breadcrumb,
                    route = page.route(item.id),
                )
            }
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
    addAssistantSubPages(content.assistants)
    add(content.ttsProviders, Screen.SettingSpeech(initialTab = 0), R.string.speech_tab_tts) { Screen.SettingSpeech(initialTab = 0) }
    add(content.asrProviders, Screen.SettingSpeech(initialTab = 1), R.string.speech_tab_asr) { Screen.SettingSpeech(initialTab = 1) }

    return out
}
