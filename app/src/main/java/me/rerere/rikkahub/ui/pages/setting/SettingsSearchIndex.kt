package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen

/**
 * One searchable entry in the settings hub. [titleRes] prefers the destination page's own
 * top-bar title over the hub row's headline when the two differ in wording, so a search for
 * either term finds the page.
 */
data class SettingsSearchEntry(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int? = null,
    @param:StringRes val groupRes: Int,
    val route: Screen,
    /**
     * Extra search-only terms, never rendered. Lets a page be found by a synonym or by the name
     * of a row it owns (the theme page answers "colour mode" too) without listing the same route
     * twice - the index keeps one entry per destination.
     */
    @param:StringRes val altTitles: List<Int> = emptyList(),
)

/**
 * Static mirror of the hub rows in [SettingPage]. This list is NOT derived from that page, so it
 * drifts if a row is added, removed, or renamed there without a matching edit here (see the
 * reminder comment in SettingPage.kt).
 *
 */
fun settingsSearchIndex(developerMode: Boolean): List<SettingsSearchEntry> {
    val entries = mutableListOf(
        // Appearance & interaction
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_theme,
            descriptionRes = R.string.setting_page_preferences_theme_desc,
            groupRes = R.string.setting_page_group_appearance,
            route = Screen.SettingPreferencesTheme,
            altTitles = listOf(R.string.setting_page_color_mode),
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_ui,
            descriptionRes = R.string.setting_page_preferences_ui_desc,
            groupRes = R.string.setting_page_group_appearance,
            route = Screen.SettingPreferencesUI,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_general,
            descriptionRes = R.string.setting_page_preferences_general_desc,
            groupRes = R.string.setting_page_group_appearance,
            route = Screen.SettingPreferencesGeneral,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_notification,
            descriptionRes = R.string.setting_page_preferences_notification_desc,
            groupRes = R.string.setting_page_group_appearance,
            route = Screen.SettingPreferencesNotification,
        ),

        // Models & AI
        SettingsSearchEntry(
            titleRes = R.string.setting_model_page_title,
            descriptionRes = R.string.setting_page_default_model_desc,
            groupRes = R.string.setting_page_group_models,
            route = Screen.SettingModels,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_providers,
            descriptionRes = R.string.setting_page_providers_desc,
            groupRes = R.string.setting_page_group_models,
            route = Screen.SettingProvider,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_search_service,
            descriptionRes = R.string.setting_page_search_service_desc,
            groupRes = R.string.setting_page_group_models,
            route = Screen.SettingSearch,
        ),
        SettingsSearchEntry(
            titleRes = R.string.speech_page_title,
            descriptionRes = R.string.setting_page_tts_service_desc,
            groupRes = R.string.setting_page_group_models,
            route = Screen.SettingSpeech,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_network,
            descriptionRes = R.string.setting_page_preferences_network_desc,
            groupRes = R.string.setting_page_group_models,
            route = Screen.SettingPreferencesNetwork,
        ),

        // Assistant & automation
        SettingsSearchEntry(
            titleRes = R.string.assistant_page_title,
            descriptionRes = R.string.setting_page_assistant_desc,
            groupRes = R.string.setting_page_group_assistant,
            route = Screen.Assistant,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_extensions,
            descriptionRes = R.string.setting_page_extensions_desc,
            groupRes = R.string.setting_page_group_assistant,
            route = Screen.Extensions,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_sub_agents,
            descriptionRes = R.string.setting_page_sub_agents_desc,
            groupRes = R.string.setting_page_group_assistant,
            route = Screen.SettingSubAgents,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_mcp,
            descriptionRes = R.string.setting_page_mcp_desc,
            groupRes = R.string.setting_page_group_assistant,
            route = Screen.SettingMcp,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_workflows,
            descriptionRes = R.string.setting_page_workflows_desc,
            groupRes = R.string.setting_page_group_assistant,
            route = Screen.SettingWorkflows,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_scheduled_jobs,
            descriptionRes = R.string.setting_page_scheduled_jobs_desc,
            groupRes = R.string.setting_page_group_assistant,
            route = Screen.SettingScheduledJobs,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_tool_approvals,
            descriptionRes = R.string.setting_page_tool_approvals_desc,
            groupRes = R.string.setting_page_group_assistant,
            route = Screen.SettingToolApprovals,
        ),

        // Device & permissions
        SettingsSearchEntry(
            titleRes = R.string.setting_page_accessibility,
            descriptionRes = R.string.setting_page_accessibility_desc,
            groupRes = R.string.setting_page_group_device,
            route = Screen.SettingAccessibility,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_floating_ball_page_title,
            descriptionRes = R.string.setting_page_floating_ball_desc,
            groupRes = R.string.setting_page_group_device,
            route = Screen.SettingFloatingBall,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_notifications,
            descriptionRes = R.string.setting_page_notifications_desc,
            groupRes = R.string.setting_page_group_device,
            route = Screen.SettingNotifications,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_permissions,
            descriptionRes = R.string.setting_page_permissions_desc,
            groupRes = R.string.setting_page_group_device,
            route = Screen.SettingPermissions,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_shizuku,
            descriptionRes = R.string.setting_page_shizuku_desc,
            groupRes = R.string.setting_page_group_device,
            route = Screen.SettingShizuku,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_termux,
            descriptionRes = R.string.setting_page_termux_desc,
            groupRes = R.string.setting_page_group_device,
            route = Screen.SettingTermux,
        ),

        // Connections
        SettingsSearchEntry(
            titleRes = R.string.setting_page_ssh,
            descriptionRes = R.string.setting_page_ssh_desc,
            groupRes = R.string.setting_page_group_connections,
            route = Screen.SettingSsh,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_web_server,
            descriptionRes = R.string.setting_page_web_server_desc,
            groupRes = R.string.setting_page_group_connections,
            route = Screen.SettingWeb,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_telegram,
            descriptionRes = R.string.setting_page_telegram_desc,
            groupRes = R.string.setting_page_group_connections,
            route = Screen.SettingTelegram,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_browser,
            descriptionRes = R.string.setting_page_browser_desc,
            groupRes = R.string.setting_page_group_connections,
            route = Screen.SettingBrowser,
        ),

        // Data
        SettingsSearchEntry(
            titleRes = R.string.backup_page_title,
            descriptionRes = R.string.setting_page_data_backup_desc,
            groupRes = R.string.setting_page_group_data,
            route = Screen.Backup,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_files_page_title,
            descriptionRes = R.string.setting_page_chat_storage_desc,
            groupRes = R.string.setting_page_group_data,
            route = Screen.SettingFiles,
        ),

        // About & diagnostics
        SettingsSearchEntry(
            titleRes = R.string.setting_page_doctor,
            descriptionRes = R.string.setting_page_doctor_desc,
            groupRes = R.string.setting_page_group_about,
            route = Screen.SettingDoctor,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_request_logs,
            descriptionRes = R.string.setting_page_request_logs_desc,
            groupRes = R.string.setting_page_group_about,
            route = Screen.Log,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_about,
            descriptionRes = R.string.setting_page_about_desc,
            groupRes = R.string.setting_page_group_about,
            route = Screen.SettingAbout,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_sponsor,
            descriptionRes = R.string.setting_page_sponsor_desc,
            groupRes = R.string.setting_page_group_about,
            route = Screen.SettingSponsor,
        ),
    )

    if (developerMode) {
        entries.add(
            SettingsSearchEntry(
                titleRes = R.string.accessibility_developer_options,
                descriptionRes = null,
                groupRes = R.string.settings,
                route = Screen.Developer,
            )
        )
    }

    return entries
}
