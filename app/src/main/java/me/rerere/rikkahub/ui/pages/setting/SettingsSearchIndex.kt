package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen

/**
 * One searchable entry in the settings hub.
 *
 * Hub pages set only [titleRes] / [route]. Nested rows under a secondary page also set
 * [parentTitleRes] so the search result can show "Parent · Item" and still navigate to the
 * parent page (deep-link scroll is out of scope).
 */
data class SettingsSearchEntry(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int? = null,
    @param:StringRes val groupRes: Int,
    val route: Screen,
    /**
     * Extra search-only terms, never rendered. Lets a page be found by a synonym or by the name
     * of a row it owns without listing the same route twice for the hub row itself.
     */
    @param:StringRes val altTitles: List<Int> = emptyList(),
    /** Secondary-page title when this entry is a nested setting under that page. */
    @param:StringRes val parentTitleRes: Int? = null,
)

/**
 * Static mirror of hub rows in [SettingPage] plus nested items from secondary pages.
 * Nested rows share the parent [Screen] route — uniqueness is (route, titleRes, parentTitleRes).
 */
fun settingsSearchIndex(developerMode: Boolean): List<SettingsSearchEntry> {
    val entries = mutableListOf<SettingsSearchEntry>()

    fun hub(
        @StringRes title: Int,
        @StringRes desc: Int?,
        @StringRes group: Int,
        route: Screen,
        @StringRes vararg alts: Int,
    ) {
        entries += SettingsSearchEntry(
            titleRes = title,
            descriptionRes = desc,
            groupRes = group,
            route = route,
            altTitles = alts.toList(),
        )
    }

    fun nested(
        @StringRes parent: Int,
        route: Screen,
        @StringRes group: Int,
        @StringRes title: Int,
        @StringRes desc: Int? = null,
        @StringRes vararg alts: Int,
    ) {
        entries += SettingsSearchEntry(
            titleRes = title,
            descriptionRes = desc,
            groupRes = group,
            route = route,
            altTitles = alts.toList(),
            parentTitleRes = parent,
        )
    }

    val appearance = R.string.setting_page_group_appearance
    val models = R.string.setting_page_group_models
    val assistant = R.string.setting_page_group_assistant
    val device = R.string.setting_page_group_device
    val connections = R.string.setting_page_group_connections
    val data = R.string.setting_page_group_data
    val about = R.string.setting_page_group_about

    // ── Hub: Appearance ──────────────────────────────────────────────
    hub(
        R.string.setting_page_preferences_theme,
        R.string.setting_page_preferences_theme_desc,
        appearance,
        Screen.SettingPreferencesTheme,
        R.string.setting_page_color_mode,
    )
    hub(
        R.string.setting_page_preferences_ui,
        R.string.setting_page_preferences_ui_desc,
        appearance,
        Screen.SettingPreferencesUI,
    )
    hub(
        R.string.setting_page_preferences_general,
        R.string.setting_page_preferences_general_desc,
        appearance,
        Screen.SettingPreferencesGeneral,
    )
    hub(
        R.string.setting_page_preferences_notification,
        R.string.setting_page_preferences_notification_desc,
        appearance,
        Screen.SettingPreferencesNotification,
    )

    // Nested: UI / display
    val uiParent = R.string.setting_page_preferences_ui
    val uiRoute = Screen.SettingPreferencesUI
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_clean_layout_title, R.string.setting_display_page_clean_layout_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_clean_layout_glass_title, R.string.setting_display_page_clean_layout_glass_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_clean_layout_top_opacity_title)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_clean_layout_top_height_title, R.string.setting_display_page_clean_layout_top_height_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_clean_layout_bottom_padding_title, R.string.setting_display_page_clean_layout_bottom_padding_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_clean_layout_bottom_opacity_title)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_drawer_capsules_title, R.string.setting_display_page_drawer_capsules_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_show_user_avatar_title, R.string.setting_display_page_show_user_avatar_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_show_assistant_bubble_title, R.string.setting_display_page_show_assistant_bubble_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_bubble_opacity_title)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_chat_list_model_icon_title, R.string.setting_display_page_chat_list_model_icon_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_show_model_name_title, R.string.setting_display_page_show_model_name_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_show_datetime_in_message_title, R.string.setting_display_page_show_datetime_in_message_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_show_token_usage_title, R.string.setting_display_page_show_token_usage_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_show_thinking_content_title, R.string.setting_display_page_show_thinking_content_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_auto_collapse_thinking_title, R.string.setting_display_page_auto_collapse_thinking_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_fold_process_narration_title, R.string.setting_display_page_fold_process_narration_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_thinking_steps_visible_title, R.string.setting_display_page_thinking_steps_visible_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_tool_call_style_title, R.string.setting_display_page_tool_call_style_desc, R.string.setting_display_page_tool_call_style_capsule, R.string.setting_display_page_tool_call_style_classic)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_popup_menu_style_title, R.string.setting_display_page_popup_menu_style_desc, R.string.setting_display_page_popup_menu_style_classic, R.string.setting_display_page_popup_menu_style_expressive)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_chat_font_family_title)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_custom_font_title)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_ui_font_family_title, R.string.setting_display_page_ui_font_family_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_ui_custom_font_title)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_font_size_title)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_ui_font_scale_title, R.string.setting_display_page_ui_font_scale_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_code_block_auto_wrap_title, R.string.setting_display_page_code_block_auto_wrap_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_code_block_auto_collapse_title, R.string.setting_display_page_code_block_auto_collapse_desc)
    nested(uiParent, uiRoute, appearance, R.string.setting_display_page_show_line_numbers_title, R.string.setting_display_page_show_line_numbers_desc)

    // Nested: General interaction
    val genParent = R.string.setting_page_preferences_general
    val genRoute = Screen.SettingPreferencesGeneral
    nested(genParent, genRoute, appearance, R.string.setting_display_page_create_new_conversation_on_start_title, R.string.setting_display_page_create_new_conversation_on_start_desc)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_send_on_enter_title, R.string.setting_display_page_send_on_enter_desc)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_show_message_jumper_title, R.string.setting_display_page_show_message_jumper_desc)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_message_jumper_position_title, R.string.setting_display_page_message_jumper_position_desc)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_enable_auto_scroll_title, R.string.setting_display_page_enable_auto_scroll_desc)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_enable_interject_title, R.string.setting_display_page_enable_interject_desc)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_loading_indicator_style_title, R.string.setting_display_page_loading_indicator_style_desc)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_enable_blur_effect_title, R.string.setting_display_page_enable_blur_effect_desc)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_enable_message_generation_haptic_effect_title, R.string.setting_display_page_enable_message_generation_haptic_effect_desc)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_skip_crop_image_title, R.string.setting_display_page_skip_crop_image_desc)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_paste_long_text_as_file_title, R.string.setting_display_page_paste_long_text_as_file_desc)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_paste_long_text_threshold_title)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_volume_key_scroll_title, R.string.setting_display_page_volume_key_scroll_desc)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_volume_key_scroll_ratio)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_bounce_overscroll_title, R.string.setting_display_page_bounce_overscroll_desc)
    nested(genParent, genRoute, appearance, R.string.setting_display_page_parse_text_tool_calls_title, R.string.setting_display_page_parse_text_tool_calls_desc)

    // Nested: Theme
    val themeParent = R.string.setting_page_preferences_theme
    nested(themeParent, Screen.SettingPreferencesTheme, appearance, R.string.setting_page_color_mode)
    nested(themeParent, Screen.SettingPreferencesTheme, appearance, R.string.setting_display_page_amoled_dark_mode_title, R.string.setting_display_page_amoled_dark_mode_desc)

    // Nested: Notification prefs
    val notifParent = R.string.setting_page_preferences_notification
    nested(notifParent, Screen.SettingPreferencesNotification, appearance, R.string.setting_display_page_notification_message_generated, R.string.setting_display_page_notification_message_generated_desc)
    nested(notifParent, Screen.SettingPreferencesNotification, appearance, R.string.setting_display_page_live_update_notification, R.string.setting_display_page_live_update_notification_desc)

    // ── Hub: Models ──────────────────────────────────────────────────
    hub(R.string.setting_model_page_title, R.string.setting_page_default_model_desc, models, Screen.SettingModels)
    hub(R.string.setting_page_providers, R.string.setting_page_providers_desc, models, Screen.SettingProvider)
    hub(R.string.setting_page_search_service, R.string.setting_page_search_service_desc, models, Screen.SettingSearch)
    hub(R.string.speech_page_title, R.string.setting_page_tts_service_desc, models, Screen.SettingSpeech)
    hub(R.string.setting_page_preferences_network, R.string.setting_page_preferences_network_desc, models, Screen.SettingPreferencesNetwork)

    val speechParent = R.string.speech_page_title
    nested(speechParent, Screen.SettingSpeech, models, R.string.setting_display_page_auto_play_tts_title, R.string.setting_display_page_auto_play_tts_desc)
    nested(speechParent, Screen.SettingSpeech, models, R.string.setting_display_page_tts_only_read_quoted_title, R.string.setting_display_page_tts_only_read_quoted_desc)
    nested(speechParent, Screen.SettingSpeech, models, R.string.setting_display_page_tts_read_outside_brackets_title, R.string.setting_display_page_tts_read_outside_brackets_desc)

    // ── Hub: Assistant ───────────────────────────────────────────────
    hub(R.string.assistant_page_title, R.string.setting_page_assistant_desc, assistant, Screen.Assistant)
    hub(R.string.setting_page_extensions, R.string.setting_page_extensions_desc, assistant, Screen.Extensions)
    hub(R.string.setting_page_sub_agents, R.string.setting_page_sub_agents_desc, assistant, Screen.SettingSubAgents)
    hub(R.string.setting_page_mcp, R.string.setting_page_mcp_desc, assistant, Screen.SettingMcp)
    hub(R.string.setting_page_workflows, R.string.setting_page_workflows_desc, assistant, Screen.SettingWorkflows)
    hub(R.string.setting_page_scheduled_jobs, R.string.setting_page_scheduled_jobs_desc, assistant, Screen.SettingScheduledJobs)
    hub(R.string.setting_page_tool_approvals, R.string.setting_page_tool_approvals_desc, assistant, Screen.SettingToolApprovals)

    // ── Hub: Device ──────────────────────────────────────────────────
    hub(R.string.setting_page_accessibility, R.string.setting_page_accessibility_desc, device, Screen.SettingAccessibility)
    hub(R.string.setting_floating_ball_page_title, R.string.setting_page_floating_ball_desc, device, Screen.SettingFloatingBall)
    hub(R.string.setting_page_notifications, R.string.setting_page_notifications_desc, device, Screen.SettingNotifications)
    hub(R.string.setting_page_permissions, R.string.setting_page_permissions_desc, device, Screen.SettingPermissions)
    hub(R.string.setting_page_shizuku, R.string.setting_page_shizuku_desc, device, Screen.SettingShizuku)
    hub(R.string.setting_page_termux, R.string.setting_page_termux_desc, device, Screen.SettingTermux)

    val ballParent = R.string.setting_floating_ball_page_title
    nested(ballParent, Screen.SettingFloatingBall, device, R.string.setting_floating_ball_idle_title, R.string.setting_floating_ball_idle_desc)
    nested(ballParent, Screen.SettingFloatingBall, device, R.string.setting_floating_ball_alpha_title, R.string.setting_floating_ball_alpha_desc)

    // ── Hub: Connections ─────────────────────────────────────────────
    hub(R.string.setting_page_ssh, R.string.setting_page_ssh_desc, connections, Screen.SettingSsh)
    hub(R.string.setting_page_web_server, R.string.setting_page_web_server_desc, connections, Screen.SettingWeb)
    hub(R.string.setting_page_telegram, R.string.setting_page_telegram_desc, connections, Screen.SettingTelegram)
    hub(R.string.setting_page_browser, R.string.setting_page_browser_desc, connections, Screen.SettingBrowser)

    // ── Hub: Data ────────────────────────────────────────────────────
    hub(R.string.backup_page_title, R.string.setting_page_data_backup_desc, data, Screen.Backup)
    hub(R.string.setting_files_page_title, R.string.setting_page_chat_storage_desc, data, Screen.SettingFiles)

    // ── Hub: About ───────────────────────────────────────────────────
    hub(R.string.setting_page_doctor, R.string.setting_page_doctor_desc, about, Screen.SettingDoctor)
    hub(R.string.setting_page_request_logs, R.string.setting_page_request_logs_desc, about, Screen.Log)
    hub(R.string.setting_page_about, R.string.setting_page_about_desc, about, Screen.SettingAbout)
    hub(R.string.setting_page_sponsor, R.string.setting_page_sponsor_desc, about, Screen.SettingSponsor)

    if (developerMode) {
        hub(R.string.accessibility_developer_options, null, R.string.settings, Screen.Developer)
    }

    return entries
}
