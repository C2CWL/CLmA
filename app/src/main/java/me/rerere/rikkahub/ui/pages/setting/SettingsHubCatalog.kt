package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsHubStyle

/**
 * Stable id for one hub row. Icons and special actions (share) live in [SettingPage];
 * this catalog only owns grouping + copy so classic/reorganized cannot drift apart on routes.
 */
enum class SettingsHubItemId {
    THEME,
    UI,
    GENERAL,
    NOTIF_PREFS,
    MODELS,
    PROVIDERS,
    SEARCH,
    SPEECH,
    NETWORK,
    ASSISTANT,
    EXTENSIONS,
    SUB_AGENTS,
    MCP,
    WORKFLOWS,
    SCHEDULED,
    TOOL_APPROVALS,
    ACCESSIBILITY,
    FLOATING_BALL,
    NOTIF_ACCESS,
    PERMISSIONS,
    SHIZUKU,
    TERMUX,
    SSH,
    SECRETS,
    WEB,
    TELEGRAM,
    BROWSER,
    BACKUP,
    FILES,
    DOCTOR,
    LOGS,
    ABOUT,
    SPONSOR,
    SHARE,
}

enum class SettingsHubSectionId {
    APPEARANCE,
    MODELS,
    ASSISTANT,
    DEVICE,
    CONNECTIONS,
    DATA,
    ABOUT,
}

data class SettingsHubItemCopy(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int? = null,
)

/**
 * One navigable (or action) hub row. [route] is null only for [SettingsHubItemId.SHARE].
 */
data class SettingsHubItemDef(
    val id: SettingsHubItemId,
    val route: Screen?,
    val classic: SettingsHubItemCopy,
    val reorganized: SettingsHubItemCopy,
) {
    fun copyFor(style: SettingsHubStyle): SettingsHubItemCopy = when (style) {
        SettingsHubStyle.CLASSIC -> classic
        SettingsHubStyle.REORGANIZED -> reorganized
    }
}

data class SettingsHubSectionDef(
    val id: SettingsHubSectionId,
    @param:StringRes val classicTitleRes: Int,
    @param:StringRes val reorganizedTitleRes: Int,
    val items: List<SettingsHubItemId>,
) {
    fun titleRes(style: SettingsHubStyle): Int = when (style) {
        SettingsHubStyle.CLASSIC -> classicTitleRes
        SettingsHubStyle.REORGANIZED -> reorganizedTitleRes
    }
}

/** All hub rows keyed by id. Routes never differ between styles. */
val settingsHubItems: Map<SettingsHubItemId, SettingsHubItemDef> = listOf(
    SettingsHubItemDef(
        id = SettingsHubItemId.THEME,
        route = Screen.SettingPreferencesTheme,
        classic = SettingsHubItemCopy(R.string.setting_page_preferences_theme, R.string.setting_page_preferences_theme_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_preferences_theme, R.string.setting_page_preferences_theme_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.UI,
        route = Screen.SettingPreferencesUI,
        classic = SettingsHubItemCopy(R.string.setting_page_preferences_ui, R.string.setting_page_preferences_ui_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_hub_chat_display, R.string.setting_page_hub_chat_display_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.GENERAL,
        route = Screen.SettingPreferencesGeneral,
        classic = SettingsHubItemCopy(R.string.setting_page_preferences_general, R.string.setting_page_preferences_general_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_hub_chat_actions, R.string.setting_page_hub_chat_actions_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.NOTIF_PREFS,
        route = Screen.SettingPreferencesNotification,
        classic = SettingsHubItemCopy(R.string.setting_page_preferences_notification, R.string.setting_page_preferences_notification_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_hub_app_alerts, R.string.setting_page_hub_app_alerts_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.MODELS,
        route = Screen.SettingModels,
        classic = SettingsHubItemCopy(R.string.setting_model_page_title, R.string.setting_page_default_model_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_model_page_title, R.string.setting_page_default_model_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.PROVIDERS,
        route = Screen.SettingProvider,
        classic = SettingsHubItemCopy(R.string.setting_page_providers, R.string.setting_page_providers_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_providers, R.string.setting_page_providers_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.SEARCH,
        route = Screen.SettingSearch,
        classic = SettingsHubItemCopy(R.string.setting_page_search_service, R.string.setting_page_search_service_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_search_service, R.string.setting_page_search_service_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.SPEECH,
        route = Screen.SettingSpeech,
        classic = SettingsHubItemCopy(R.string.setting_page_tts_service, R.string.setting_page_tts_service_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_tts_service, R.string.setting_page_tts_service_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.NETWORK,
        route = Screen.SettingPreferencesNetwork,
        classic = SettingsHubItemCopy(R.string.setting_page_preferences_network, R.string.setting_page_preferences_network_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_preferences_network, R.string.setting_page_hub_network_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.ASSISTANT,
        route = Screen.Assistant,
        classic = SettingsHubItemCopy(R.string.setting_page_assistant, R.string.setting_page_assistant_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_assistant, R.string.setting_page_assistant_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.EXTENSIONS,
        route = Screen.Extensions,
        classic = SettingsHubItemCopy(R.string.setting_page_extensions, R.string.setting_page_extensions_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_extensions, R.string.setting_page_extensions_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.SUB_AGENTS,
        route = Screen.SettingSubAgents,
        classic = SettingsHubItemCopy(R.string.setting_page_sub_agents, R.string.setting_page_sub_agents_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_sub_agents, R.string.setting_page_sub_agents_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.MCP,
        route = Screen.SettingMcp,
        classic = SettingsHubItemCopy(R.string.setting_page_mcp, R.string.setting_page_mcp_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_mcp, R.string.setting_page_mcp_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.WORKFLOWS,
        route = Screen.SettingWorkflows,
        classic = SettingsHubItemCopy(R.string.setting_page_workflows, R.string.setting_page_workflows_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_workflows, R.string.setting_page_workflows_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.SCHEDULED,
        route = Screen.SettingScheduledJobs,
        classic = SettingsHubItemCopy(R.string.setting_page_scheduled_jobs, R.string.setting_page_scheduled_jobs_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_scheduled_jobs, R.string.setting_page_scheduled_jobs_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.TOOL_APPROVALS,
        route = Screen.SettingToolApprovals,
        classic = SettingsHubItemCopy(R.string.setting_page_tool_approvals, R.string.setting_page_tool_approvals_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_tool_approvals, R.string.setting_page_tool_approvals_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.ACCESSIBILITY,
        route = Screen.SettingAccessibility,
        classic = SettingsHubItemCopy(R.string.setting_page_accessibility, R.string.setting_page_accessibility_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_accessibility, R.string.setting_page_accessibility_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.FLOATING_BALL,
        route = Screen.SettingFloatingBall,
        classic = SettingsHubItemCopy(R.string.setting_page_floating_ball, R.string.setting_page_floating_ball_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_floating_ball, R.string.setting_page_floating_ball_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.NOTIF_ACCESS,
        route = Screen.SettingNotifications,
        classic = SettingsHubItemCopy(R.string.setting_page_notifications, R.string.setting_page_notifications_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_hub_notif_access, R.string.setting_page_hub_notif_access_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.PERMISSIONS,
        route = Screen.SettingPermissions,
        classic = SettingsHubItemCopy(R.string.setting_page_permissions, R.string.setting_page_permissions_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_permissions, R.string.setting_page_permissions_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.SHIZUKU,
        route = Screen.SettingShizuku,
        classic = SettingsHubItemCopy(R.string.setting_page_shizuku, R.string.setting_page_shizuku_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_shizuku, R.string.setting_page_shizuku_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.TERMUX,
        route = Screen.SettingTermux,
        classic = SettingsHubItemCopy(R.string.setting_page_termux, R.string.setting_page_termux_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_termux, R.string.setting_page_termux_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.SSH,
        route = Screen.SettingSsh,
        classic = SettingsHubItemCopy(R.string.setting_page_ssh, R.string.setting_page_ssh_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_ssh, R.string.setting_page_ssh_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.SECRETS,
        route = Screen.SettingSecrets,
        classic = SettingsHubItemCopy(R.string.setting_page_secrets, R.string.setting_page_secrets_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_secrets, R.string.setting_page_secrets_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.WEB,
        route = Screen.SettingWeb,
        classic = SettingsHubItemCopy(R.string.setting_page_web_server, R.string.setting_page_web_server_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_web_server, R.string.setting_page_web_server_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.TELEGRAM,
        route = Screen.SettingTelegram,
        classic = SettingsHubItemCopy(R.string.setting_page_telegram, R.string.setting_page_telegram_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_telegram, R.string.setting_page_telegram_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.BROWSER,
        route = Screen.SettingBrowser,
        classic = SettingsHubItemCopy(R.string.setting_page_browser, R.string.setting_page_browser_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_browser, R.string.setting_page_browser_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.BACKUP,
        route = Screen.Backup,
        classic = SettingsHubItemCopy(R.string.setting_page_data_backup, R.string.setting_page_data_backup_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_data_backup, R.string.setting_page_data_backup_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.FILES,
        route = Screen.SettingFiles,
        classic = SettingsHubItemCopy(R.string.setting_files_page_title, null),
        reorganized = SettingsHubItemCopy(R.string.setting_files_page_title, null),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.DOCTOR,
        route = Screen.SettingDoctor,
        classic = SettingsHubItemCopy(R.string.setting_page_doctor, R.string.setting_page_doctor_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_doctor, R.string.setting_page_doctor_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.LOGS,
        route = Screen.Log,
        classic = SettingsHubItemCopy(R.string.setting_page_request_logs, R.string.setting_page_request_logs_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_request_logs, R.string.setting_page_request_logs_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.ABOUT,
        route = Screen.SettingAbout,
        classic = SettingsHubItemCopy(R.string.setting_page_about, R.string.setting_page_about_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_about, R.string.setting_page_about_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.SPONSOR,
        route = Screen.SettingSponsor,
        classic = SettingsHubItemCopy(R.string.setting_page_sponsor, R.string.setting_page_sponsor_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_sponsor, R.string.setting_page_sponsor_desc),
    ),
    SettingsHubItemDef(
        id = SettingsHubItemId.SHARE,
        route = null,
        classic = SettingsHubItemCopy(R.string.setting_page_share, R.string.setting_page_share_desc),
        reorganized = SettingsHubItemCopy(R.string.setting_page_share, R.string.setting_page_share_desc),
    ),
).associateBy { it.id }

private val classicSections: List<SettingsHubSectionDef> = listOf(
    SettingsHubSectionDef(
        id = SettingsHubSectionId.APPEARANCE,
        classicTitleRes = R.string.setting_page_group_appearance,
        reorganizedTitleRes = R.string.setting_page_group_appearance,
        items = listOf(
            SettingsHubItemId.THEME,
            SettingsHubItemId.UI,
            SettingsHubItemId.GENERAL,
            SettingsHubItemId.NOTIF_PREFS,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.MODELS,
        classicTitleRes = R.string.setting_page_group_models,
        reorganizedTitleRes = R.string.setting_page_group_models,
        items = listOf(
            SettingsHubItemId.MODELS,
            SettingsHubItemId.PROVIDERS,
            SettingsHubItemId.SEARCH,
            SettingsHubItemId.SPEECH,
            SettingsHubItemId.NETWORK,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.ASSISTANT,
        classicTitleRes = R.string.setting_page_group_assistant,
        reorganizedTitleRes = R.string.setting_page_group_assistant,
        items = listOf(
            SettingsHubItemId.ASSISTANT,
            SettingsHubItemId.EXTENSIONS,
            SettingsHubItemId.SUB_AGENTS,
            SettingsHubItemId.MCP,
            SettingsHubItemId.WORKFLOWS,
            SettingsHubItemId.SCHEDULED,
            SettingsHubItemId.TOOL_APPROVALS,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.DEVICE,
        classicTitleRes = R.string.setting_page_group_device,
        reorganizedTitleRes = R.string.setting_page_group_device,
        items = listOf(
            SettingsHubItemId.ACCESSIBILITY,
            SettingsHubItemId.FLOATING_BALL,
            SettingsHubItemId.NOTIF_ACCESS,
            SettingsHubItemId.PERMISSIONS,
            SettingsHubItemId.SHIZUKU,
            SettingsHubItemId.TERMUX,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.CONNECTIONS,
        classicTitleRes = R.string.setting_page_group_connections,
        reorganizedTitleRes = R.string.setting_page_group_connections,
        items = listOf(
            SettingsHubItemId.SSH,
            SettingsHubItemId.SECRETS,
            SettingsHubItemId.WEB,
            SettingsHubItemId.TELEGRAM,
            SettingsHubItemId.BROWSER,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.DATA,
        classicTitleRes = R.string.setting_page_group_data,
        reorganizedTitleRes = R.string.setting_page_group_data,
        items = listOf(SettingsHubItemId.BACKUP, SettingsHubItemId.FILES),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.ABOUT,
        classicTitleRes = R.string.setting_page_group_about,
        reorganizedTitleRes = R.string.setting_page_group_about,
        items = listOf(
            SettingsHubItemId.DOCTOR,
            SettingsHubItemId.LOGS,
            SettingsHubItemId.ABOUT,
            SettingsHubItemId.SPONSOR,
            SettingsHubItemId.SHARE,
        ),
    ),
)

/** Reorganized: clearer appearance labels; network under Connections. */
private val reorganizedSections: List<SettingsHubSectionDef> = listOf(
    SettingsHubSectionDef(
        id = SettingsHubSectionId.APPEARANCE,
        classicTitleRes = R.string.setting_page_group_appearance,
        reorganizedTitleRes = R.string.setting_page_group_appearance_reorg,
        items = listOf(
            SettingsHubItemId.THEME,
            SettingsHubItemId.UI,
            SettingsHubItemId.GENERAL,
            SettingsHubItemId.NOTIF_PREFS,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.MODELS,
        classicTitleRes = R.string.setting_page_group_models,
        reorganizedTitleRes = R.string.setting_page_group_models_reorg,
        items = listOf(
            SettingsHubItemId.MODELS,
            SettingsHubItemId.PROVIDERS,
            SettingsHubItemId.SEARCH,
            SettingsHubItemId.SPEECH,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.ASSISTANT,
        classicTitleRes = R.string.setting_page_group_assistant,
        reorganizedTitleRes = R.string.setting_page_group_assistant,
        items = listOf(
            SettingsHubItemId.ASSISTANT,
            SettingsHubItemId.EXTENSIONS,
            SettingsHubItemId.SUB_AGENTS,
            SettingsHubItemId.MCP,
            SettingsHubItemId.WORKFLOWS,
            SettingsHubItemId.SCHEDULED,
            SettingsHubItemId.TOOL_APPROVALS,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.DEVICE,
        classicTitleRes = R.string.setting_page_group_device,
        reorganizedTitleRes = R.string.setting_page_group_device,
        items = listOf(
            SettingsHubItemId.ACCESSIBILITY,
            SettingsHubItemId.FLOATING_BALL,
            SettingsHubItemId.NOTIF_ACCESS,
            SettingsHubItemId.PERMISSIONS,
            SettingsHubItemId.SHIZUKU,
            SettingsHubItemId.TERMUX,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.CONNECTIONS,
        classicTitleRes = R.string.setting_page_group_connections,
        reorganizedTitleRes = R.string.setting_page_group_connections_reorg,
        items = listOf(
            SettingsHubItemId.NETWORK,
            SettingsHubItemId.SSH,
            SettingsHubItemId.SECRETS,
            SettingsHubItemId.WEB,
            SettingsHubItemId.TELEGRAM,
            SettingsHubItemId.BROWSER,
        ),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.DATA,
        classicTitleRes = R.string.setting_page_group_data,
        reorganizedTitleRes = R.string.setting_page_group_data,
        items = listOf(SettingsHubItemId.BACKUP, SettingsHubItemId.FILES),
    ),
    SettingsHubSectionDef(
        id = SettingsHubSectionId.ABOUT,
        classicTitleRes = R.string.setting_page_group_about,
        reorganizedTitleRes = R.string.setting_page_group_about,
        items = listOf(
            SettingsHubItemId.DOCTOR,
            SettingsHubItemId.LOGS,
            SettingsHubItemId.ABOUT,
            SettingsHubItemId.SPONSOR,
            SettingsHubItemId.SHARE,
        ),
    ),
)

fun settingsHubSections(style: SettingsHubStyle): List<SettingsHubSectionDef> = when (style) {
    SettingsHubStyle.CLASSIC -> classicSections
    SettingsHubStyle.REORGANIZED -> reorganizedSections
}

/** Hub rows for search index (one entry per destination; SHARE omitted). */
fun settingsHubSearchRows(style: SettingsHubStyle): List<Triple<SettingsHubItemDef, SettingsHubItemCopy, Int>> {
    val out = mutableListOf<Triple<SettingsHubItemDef, SettingsHubItemCopy, Int>>()
    for (section in settingsHubSections(style)) {
        val groupRes = section.titleRes(style)
        for (id in section.items) {
            val def = settingsHubItems.getValue(id)
            if (def.route == null) continue
            out += Triple(def, def.copyFor(style), groupRes)
        }
    }
    return out
}
