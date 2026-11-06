package com.clma.app.ui.pages.setting

import androidx.annotation.StringRes
import com.clma.app.R
import com.clma.app.Screen
import kotlinx.serialization.Serializable

/**
 * Stable id for one hub row. Icons, status summaries and special actions (share) live in
 * [SettingPage]; this catalog only owns grouping + copy so the hub and the settings search
 * cannot drift apart.
 */
enum class SettingsHubItemId {
    // 模型
    PROVIDERS,
    MODELS,
    ASSISTANT,
    PROMPTS,
    SEARCH,
    SPEECH,

    // Agent 与工具
    WORKSPACES,
    SKILLS,
    MCP,
    SUB_AGENTS,
    TOOL_APPROVALS,
    RUNTIME,

    // 执行环境与设备
    NETWORK,
    TERMUX,
    SSH,
    BROWSER,
    CONNECTORS,
    SECRETS,

    // 设备与权限（仍属执行环境分组）
    PERMISSIONS,
    ACCESSIBILITY,
    SHIZUKU,
    NOTIF_ACCESS,
    FLOATING_BALL,

    // 自动化（归 Agent）
    WORKFLOWS,
    SCHEDULED,
    WEB,

    // 外观
    VISUAL_STYLE,
    COLOR_MODE,
    FONTS,
    CHAT_LAYOUT,

    // 聊天外观项
    MESSAGE_DISPLAY,
    INPUT,
    QUICK_MESSAGES,
    NOTIF_PREFS,

    // 数据与关于
    BACKUP,
    FILES,
    LOGS,
    DOCTOR,
    ABOUT,
    SHARE,
}

/**
 * Six hub sections (v4 IA). Ordinal / name used by [Screen.SettingsCategory] deep links.
 */
@Serializable
enum class SettingsHubSectionId {
    MODELS,
    CHAT,
    AGENT,
    DEVICE,
    APPEARANCE,
    DATA,
}

/**
 * One navigable (or action) hub row. [route] is null only for [SettingsHubItemId.SHARE].
 *
 * The hub shows only [titleRes] (plus a live status summary); [descriptionRes] is never
 * rendered and exists so the settings search can still match on it.
 */
data class SettingsHubItemDef(
    val id: SettingsHubItemId,
    val route: Screen?,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int? = null,
)

/**
 * Optional mid-section label used by「数据与关于」to split 数据 | 关于 under one section header.
 * [startItem] is the first [SettingsHubItemId] in [SettingsHubSectionDef.items] that belongs
 * to this subsection.
 */
data class SettingsHubSubsectionDef(
    @param:StringRes val titleRes: Int,
    val startItem: SettingsHubItemId,
)

data class SettingsHubSectionDef(
    val id: SettingsHubSectionId,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val items: List<SettingsHubItemId>,
    val subsections: List<SettingsHubSubsectionDef> = emptyList(),
) {
    val route: Screen.SettingsCategory get() = Screen.SettingsCategory(id)
}

private fun hubItem(
    id: SettingsHubItemId,
    route: Screen?,
    @StringRes title: Int,
    @StringRes description: Int? = null,
) = SettingsHubItemDef(id, route, title, description)

/** All hub rows keyed by id. */
val settingsHubItems: Map<SettingsHubItemId, SettingsHubItemDef> = listOf(
    hubItem(SettingsHubItemId.PROVIDERS, Screen.SettingProvider, R.string.setting_hub_providers, R.string.setting_page_providers_desc),
    hubItem(SettingsHubItemId.MODELS, Screen.SettingModels(), R.string.setting_hub_default_model, R.string.setting_page_default_model_desc),
    hubItem(SettingsHubItemId.ASSISTANT, Screen.Assistant, R.string.setting_hub_assistant, R.string.setting_page_assistant_desc),
    hubItem(SettingsHubItemId.PROMPTS, Screen.Prompts(), R.string.setting_hub_prompts, R.string.extensions_page_prompts_desc),
    hubItem(SettingsHubItemId.SEARCH, Screen.SettingSearch, R.string.setting_page_search_service, R.string.setting_page_search_service_desc),
    hubItem(SettingsHubItemId.SPEECH, Screen.SettingSpeech(), R.string.setting_page_tts_service, R.string.setting_page_tts_service_desc),

    hubItem(SettingsHubItemId.WORKSPACES, Screen.Workspaces, R.string.extensions_page_workspace, R.string.extensions_page_workspace_desc),
    hubItem(SettingsHubItemId.SKILLS, Screen.Skills, R.string.extensions_page_agent_skills, R.string.extensions_page_agent_skills_desc),
    hubItem(SettingsHubItemId.MCP, Screen.SettingMcp, R.string.setting_hub_mcp, R.string.setting_page_mcp_desc),
    hubItem(SettingsHubItemId.SUB_AGENTS, Screen.SettingSubAgents, R.string.setting_hub_sub_agents, R.string.setting_page_sub_agents_desc),
    hubItem(SettingsHubItemId.TOOL_APPROVALS, Screen.SettingToolApprovals, R.string.setting_page_tool_approvals, R.string.setting_page_tool_approvals_desc),
    hubItem(SettingsHubItemId.RUNTIME, Screen.SettingRuntime, R.string.setting_hub_runtime, R.string.setting_hub_runtime_desc),

    hubItem(SettingsHubItemId.NETWORK, Screen.SettingPreferencesNetwork, R.string.setting_hub_network, R.string.setting_page_hub_network_desc),
    hubItem(SettingsHubItemId.TERMUX, Screen.SettingTermux, R.string.setting_page_termux, R.string.setting_page_termux_desc),
    hubItem(SettingsHubItemId.SSH, Screen.SettingSsh, R.string.setting_page_ssh, R.string.setting_page_ssh_desc),
    hubItem(SettingsHubItemId.BROWSER, Screen.SettingBrowser, R.string.setting_page_browser, R.string.setting_page_browser_desc),
    hubItem(SettingsHubItemId.CONNECTORS, Screen.SettingConnectors, R.string.setting_connectors_title, R.string.setting_connectors_desc),
    hubItem(SettingsHubItemId.SECRETS, Screen.SettingSecrets, R.string.setting_page_secrets, R.string.setting_page_secrets_desc),

    hubItem(SettingsHubItemId.PERMISSIONS, Screen.SettingPermissions, R.string.setting_hub_permissions, R.string.setting_page_permissions_desc),
    hubItem(SettingsHubItemId.ACCESSIBILITY, Screen.SettingAccessibility, R.string.setting_page_accessibility, R.string.setting_page_accessibility_desc),
    hubItem(SettingsHubItemId.SHIZUKU, Screen.SettingShizuku, R.string.setting_page_shizuku, R.string.setting_page_shizuku_desc),
    hubItem(SettingsHubItemId.NOTIF_ACCESS, Screen.SettingNotifications, R.string.setting_page_hub_notif_access, R.string.setting_page_hub_notif_access_desc),
    hubItem(SettingsHubItemId.FLOATING_BALL, Screen.SettingFloatingBall, R.string.setting_page_floating_ball, R.string.setting_page_floating_ball_desc),

    hubItem(SettingsHubItemId.WORKFLOWS, Screen.SettingWorkflows, R.string.setting_page_workflows, R.string.setting_page_workflows_desc),
    hubItem(SettingsHubItemId.SCHEDULED, Screen.SettingScheduledJobs, R.string.setting_page_scheduled_jobs, R.string.setting_page_scheduled_jobs_desc),
    hubItem(SettingsHubItemId.WEB, Screen.SettingWeb, R.string.setting_page_web_server, R.string.setting_page_web_server_desc),

    hubItem(SettingsHubItemId.VISUAL_STYLE, Screen.SettingPreferencesTheme, R.string.setting_visual_style_title, R.string.setting_visual_style_desc),
    hubItem(SettingsHubItemId.COLOR_MODE, Screen.SettingColorMode, R.string.setting_hub_color_mode, R.string.setting_hub_color_mode_desc),
    hubItem(SettingsHubItemId.FONTS, Screen.SettingFonts, R.string.setting_hub_fonts, R.string.setting_hub_fonts_desc),
    hubItem(SettingsHubItemId.CHAT_LAYOUT, Screen.SettingChatLayout, R.string.setting_display_page_chat_layout_title, R.string.setting_display_page_chat_layout_desc),

    hubItem(SettingsHubItemId.MESSAGE_DISPLAY, Screen.SettingPreferencesUI, R.string.setting_hub_message_display, R.string.setting_hub_message_display_desc),
    hubItem(SettingsHubItemId.INPUT, Screen.SettingPreferencesGeneral, R.string.setting_hub_input, R.string.setting_hub_input_desc),
    hubItem(SettingsHubItemId.QUICK_MESSAGES, Screen.QuickMessages, R.string.assistant_page_quick_messages, R.string.extensions_page_quick_messages_desc),
    hubItem(SettingsHubItemId.NOTIF_PREFS, Screen.SettingPreferencesNotification, R.string.setting_hub_message_notification, R.string.setting_page_preferences_notification_desc),

    hubItem(SettingsHubItemId.BACKUP, Screen.Backup(), R.string.setting_hub_backup, R.string.setting_page_data_backup_desc),
    hubItem(SettingsHubItemId.FILES, Screen.SettingFiles, R.string.setting_files_page_title),
    hubItem(SettingsHubItemId.LOGS, Screen.Log, R.string.setting_page_request_logs, R.string.setting_page_request_logs_desc),

    hubItem(SettingsHubItemId.DOCTOR, Screen.SettingDoctor, R.string.setting_page_doctor, R.string.setting_page_doctor_desc),
    hubItem(SettingsHubItemId.ABOUT, Screen.SettingAbout, R.string.setting_page_about, R.string.setting_page_about_desc),
    hubItem(SettingsHubItemId.SHARE, null, R.string.setting_page_share, R.string.setting_page_share_desc),
).associateBy { it.id }

/**
 * The hub, top to bottom — six category cards that drill into leaf lists.
 * Every [SettingsHubItemId] appears exactly once. IA matches the #226 grouping.
 */
val settingsHubSections: List<SettingsHubSectionDef> = listOf(
    SettingsHubSectionDef(
        id = SettingsHubSectionId.MODELS,
        titleRes = R.string.setting_hub_group_models,
        descriptionRes = R.string.setting_hub_category_models_desc,
        items = listOf(
            SettingsHubItemId.PROVIDERS,
            SettingsHubItemId.MODELS,
            SettingsHubItemId.SEARCH,
            SettingsHubItemId.SPEECH,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.CHAT,
        titleRes = R.string.setting_hub_group_chat,
        descriptionRes = R.string.setting_hub_category_chat_desc,
        items = listOf(
            SettingsHubItemId.ASSISTANT,
            SettingsHubItemId.PROMPTS,
            SettingsHubItemId.RUNTIME,
            SettingsHubItemId.QUICK_MESSAGES,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.AGENT,
        titleRes = R.string.setting_hub_group_agent,
        descriptionRes = R.string.setting_hub_category_agent_desc,
        items = listOf(
            SettingsHubItemId.WORKSPACES,
            SettingsHubItemId.SKILLS,
            SettingsHubItemId.MCP,
            SettingsHubItemId.SUB_AGENTS,
            SettingsHubItemId.TOOL_APPROVALS,
            SettingsHubItemId.CONNECTORS,
            SettingsHubItemId.WORKFLOWS,
            SettingsHubItemId.SCHEDULED,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.DEVICE,
        titleRes = R.string.setting_hub_group_device,
        descriptionRes = R.string.setting_hub_category_device_desc,
        items = listOf(
            SettingsHubItemId.NETWORK,
            SettingsHubItemId.TERMUX,
            SettingsHubItemId.SSH,
            SettingsHubItemId.BROWSER,
            SettingsHubItemId.WEB,
            SettingsHubItemId.PERMISSIONS,
            SettingsHubItemId.ACCESSIBILITY,
            SettingsHubItemId.SHIZUKU,
            SettingsHubItemId.NOTIF_ACCESS,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.APPEARANCE,
        titleRes = R.string.setting_hub_group_appearance,
        descriptionRes = R.string.setting_hub_category_appearance_desc,
        items = listOf(
            SettingsHubItemId.VISUAL_STYLE,
            SettingsHubItemId.COLOR_MODE,
            SettingsHubItemId.FONTS,
            SettingsHubItemId.CHAT_LAYOUT,
            SettingsHubItemId.MESSAGE_DISPLAY,
            SettingsHubItemId.INPUT,
            SettingsHubItemId.NOTIF_PREFS,
            SettingsHubItemId.FLOATING_BALL,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.DATA,
        titleRes = R.string.setting_hub_group_data,
        descriptionRes = R.string.setting_hub_category_data_desc,
        items = listOf(
            SettingsHubItemId.BACKUP,
            SettingsHubItemId.FILES,
            SettingsHubItemId.SECRETS,
            SettingsHubItemId.DOCTOR,
            SettingsHubItemId.LOGS,
            SettingsHubItemId.ABOUT,
            SettingsHubItemId.SHARE,
        ),
        subsections = listOf(
            SettingsHubSubsectionDef(R.string.setting_hub_subsection_data, SettingsHubItemId.BACKUP),
            SettingsHubSubsectionDef(R.string.setting_hub_subsection_about, SettingsHubItemId.DOCTOR),
        ),
    ),
)

/** Split [SettingsHubSectionDef.items] by [SettingsHubSectionDef.subsections] labels. */
fun SettingsHubSectionDef.itemGroups(): List<Pair<Int?, List<SettingsHubItemId>>> {
    if (subsections.isEmpty()) return listOf(null to items)
    val starts = subsections.associate { it.startItem to it.titleRes }
    val groups = mutableListOf<Pair<Int?, MutableList<SettingsHubItemId>>>()
    for (id in items) {
        val label = starts[id]
        if (label != null || groups.isEmpty()) {
            groups += (label to mutableListOf(id))
        } else {
            groups.last().second += id
        }
    }
    return groups.map { it.first to it.second.toList() }
}

/** Hub rows for the search index, with the title of the group they sit in (SHARE omitted). */
fun settingsHubSearchRows(): List<Pair<SettingsHubItemDef, Int>> =
    settingsHubSections.flatMap { section ->
        section.items.mapNotNull { id ->
            val def = settingsHubItems.getValue(id)
            if (def.route == null) null else def to section.titleRes
        }
    }
