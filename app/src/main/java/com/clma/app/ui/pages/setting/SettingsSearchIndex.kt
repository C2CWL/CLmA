package com.clma.app.ui.pages.setting

import androidx.annotation.StringRes
import com.clma.app.R
import com.clma.app.Screen

/**
 * Anything the settings search can show and open: a static row from [settingsSearchIndex] or a
 * user-created item from [settingsSearchDynamicEntries].
 */
sealed interface SettingsSearchItem {
    /** Where tapping the result goes (may carry a tab, e.g. `Screen.Backup(initialTab = 1)`). */
    val route: Screen

    @get:StringRes
    val groupRes: Int

    /**
     * Page titles from the settings hub row down to the page that shows this result (the
     * result's own title is not included). Rendered as "Group · Page · Sub-page".
     */
    val breadcrumbRes: List<Int>
}

/**
 * One static searchable entry.
 *
 * Hub rows have an empty [breadcrumbRes]. Everything below a hub row (sub-pages, tabs, rows
 * inside a page) carries the path of page titles that leads to it, so the result can show
 * "外观与视觉基调 · 字体与排版" and still navigate straight to the right page (and tab).
 */
data class SettingsSearchEntry(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int? = null,
    @param:StringRes override val groupRes: Int,
    override val route: Screen,
    /**
     * Extra search-only terms, never rendered. Lets a page be found by a synonym, by a former
     * page name, or by the name of a row it owns.
     */
    @param:StringRes val altTitles: List<Int> = emptyList(),
    override val breadcrumbRes: List<Int> = emptyList(),
) : SettingsSearchItem {
    /** Title of the page that directly contains this entry; null for hub rows. */
    val parentTitleRes: Int? get() = breadcrumbRes.lastOrNull()
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Page tree — the single place that says which settings pages exist, who links to them, and
// which rows they show. Hub rows themselves (title / description / group) come
// from [settingsHubItems]; a spec for a hub route only adds rows, tabs and aliases.
//
// Adding a settings page: add a [SettingsSearchPageSpec] with `parent` = the page that opens
// it. SettingsSearchCoverageTest fails when a Screen, a screen that a settings page navigates
// to, or a `headlineContent` row of a mapped page source is not indexed.
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** A tab inside a page, opened through the page route's `initialTab`. */
class SettingsSearchTabSpec(
    @param:StringRes val titleRes: Int,
    val route: Screen,
    val altTitles: List<Int> = emptyList(),
)

/** One row inside a page (or inside one of its tabs). */
class SettingsSearchItemSpec(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int? = null,
    val altTitles: List<Int> = emptyList(),
    val tab: SettingsSearchTabSpec? = null,
)

class SettingsSearchPageSpec(
    val route: Screen,
    /**
     * The page that links here. Null only for hub routes (their placement comes from the hub
     * catalog).
     */
    val parent: Screen? = null,
    /** Title of a non-hub page. Ignored for hub routes, whose copy comes from the catalog. */
    @param:StringRes val titleRes: Int = 0,
    @param:StringRes val descriptionRes: Int? = null,
    val altTitles: List<Int> = emptyList(),
    val tabs: List<SettingsSearchTabSpec> = emptyList(),
    val items: List<SettingsSearchItemSpec> = emptyList(),
)

private fun row(
    @StringRes title: Int,
    @StringRes desc: Int? = null,
    @StringRes vararg alts: Int,
    tab: SettingsSearchTabSpec? = null,
) = SettingsSearchItemSpec(title, desc, alts.toList(), tab)

// Tabs that deep-link through `initialTab`.
private val speechTtsTab = SettingsSearchTabSpec(R.string.speech_tab_tts, Screen.SettingSpeech(initialTab = 0))
private val speechAsrTab = SettingsSearchTabSpec(R.string.speech_tab_asr, Screen.SettingSpeech(initialTab = 1))
private val modelsModelTab = SettingsSearchTabSpec(R.string.setting_model_page_tab_model, Screen.SettingModels(initialTab = 0))
private val modelsPromptTab = SettingsSearchTabSpec(R.string.setting_model_page_tab_prompt, Screen.SettingModels(initialTab = 1))
private val backupLocalTab = SettingsSearchTabSpec(R.string.backup_page_import_export, Screen.Backup(initialTab = 0))
private val backupWebDavTab = SettingsSearchTabSpec(R.string.backup_page_webdav_backup, Screen.Backup(initialTab = 1))
private val backupS3Tab = SettingsSearchTabSpec(R.string.backup_page_s3_backup, Screen.Backup(initialTab = 2))
private val backupReminderTab = SettingsSearchTabSpec(R.string.backup_page_reminder, Screen.Backup(initialTab = 3))
private val promptsModeTab = SettingsSearchTabSpec(R.string.prompt_page_mode_injection_tab, Screen.Prompts(initialTab = 0))
private val promptsLorebookTab = SettingsSearchTabSpec(R.string.prompt_page_lorebook_tab, Screen.Prompts(initialTab = 1))

val settingsSearchPages: List<SettingsSearchPageSpec> = listOf(
    // ── Appearance ──
    SettingsSearchPageSpec(
        route = Screen.SettingPreferencesTheme,
        altTitles = listOf(R.string.setting_page_preferences_theme, R.string.theme_name_claude, R.string.theme_name_gemini),
        items = listOf(
            row(R.string.setting_page_dynamic_color, R.string.setting_page_dynamic_color_desc),
            row(R.string.setting_visual_style_section_details),
            row(R.string.setting_display_page_popup_menu_style_title, R.string.setting_display_page_popup_menu_style_desc, R.string.setting_display_page_popup_menu_style_classic, R.string.setting_display_page_popup_menu_style_expressive),
            row(R.string.setting_display_page_loading_indicator_style_title, R.string.setting_display_page_loading_indicator_style_desc),
            row(R.string.setting_display_page_enable_blur_effect_title, R.string.setting_display_page_enable_blur_effect_desc),
            row(R.string.setting_display_page_app_icon_title, R.string.setting_display_page_app_icon_desc),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingColorMode,
        altTitles = listOf(R.string.setting_page_color_mode),
        items = listOf(
            row(R.string.setting_page_color_mode, null, R.string.setting_page_color_mode_light, R.string.setting_page_color_mode_dark),
            row(R.string.setting_display_page_amoled_dark_mode_title, R.string.setting_display_page_amoled_dark_mode_desc),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingTheme,
        parent = Screen.SettingPreferencesTheme,
        titleRes = R.string.setting_page_theme_setting,
        descriptionRes = R.string.setting_page_theme_setting_desc,
        items = listOf(
            row(R.string.setting_theme_page_preset_themes),
            row(R.string.setting_theme_page_custom_themes),
            row(R.string.setting_theme_page_import_theme),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingChatLayout,
        items = listOf(
            row(R.string.setting_display_page_chat_layout_title, R.string.setting_display_page_chat_layout_desc),
            row(R.string.setting_display_page_clean_layout_title, R.string.setting_display_page_clean_layout_desc),
            row(R.string.setting_display_page_clean_layout_glass_title, R.string.setting_display_page_clean_layout_glass_desc),
            row(R.string.setting_display_page_clean_layout_top_opacity_title),
            row(R.string.setting_display_page_clean_layout_top_height_title, R.string.setting_display_page_clean_layout_top_height_desc),
            row(R.string.setting_display_page_clean_layout_bottom_padding_title, R.string.setting_display_page_clean_layout_bottom_padding_desc),
            row(R.string.setting_display_page_clean_layout_bottom_opacity_title),
            row(R.string.setting_display_page_drawer_capsules_title, R.string.setting_display_page_drawer_capsules_desc),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingFonts,
        items = listOf(
            row(R.string.setting_display_page_chat_font_family_title),
            row(R.string.setting_display_page_custom_font_title),
            row(R.string.setting_display_page_ui_font_family_title, R.string.setting_display_page_ui_font_family_desc),
            row(R.string.setting_display_page_ui_custom_font_title),
            row(R.string.setting_display_page_font_size_title),
            row(R.string.setting_display_page_ui_font_scale_title, R.string.setting_display_page_ui_font_scale_desc),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingPreferencesUI,
        altTitles = listOf(R.string.setting_page_preferences_ui, R.string.setting_page_hub_chat_display),
        items = listOf(
            row(R.string.setting_display_page_show_user_avatar_title, R.string.setting_display_page_show_user_avatar_desc),
            row(R.string.setting_display_page_show_assistant_bubble_title, R.string.setting_display_page_show_assistant_bubble_desc),
            row(R.string.setting_display_page_bubble_opacity_title),
            row(R.string.setting_display_page_chat_list_model_icon_title, R.string.setting_display_page_chat_list_model_icon_desc),
            row(R.string.setting_display_page_show_model_name_title, R.string.setting_display_page_show_model_name_desc),
            row(R.string.setting_display_page_show_datetime_in_message_title, R.string.setting_display_page_show_datetime_in_message_desc),
            row(R.string.setting_display_page_show_message_end_time_title, R.string.setting_display_page_show_message_end_time_desc),
            row(R.string.setting_display_page_show_token_usage_title, R.string.setting_display_page_show_token_usage_desc),
            row(R.string.setting_display_page_show_thinking_content_title, R.string.setting_display_page_show_thinking_content_desc),
            row(R.string.setting_display_page_auto_collapse_thinking_title, R.string.setting_display_page_auto_collapse_thinking_desc),
            row(R.string.setting_display_page_fold_process_narration_title, R.string.setting_display_page_fold_process_narration_desc),
            row(R.string.setting_display_page_thinking_steps_visible_title, R.string.setting_display_page_thinking_steps_visible_desc),
            row(R.string.setting_display_page_tool_call_style_title, R.string.setting_display_page_tool_call_style_desc, R.string.setting_display_page_tool_call_style_capsule, R.string.setting_display_page_tool_call_style_classic),
            row(R.string.setting_display_page_code_block_auto_wrap_title, R.string.setting_display_page_code_block_auto_wrap_desc),
            row(R.string.setting_display_page_code_block_auto_collapse_title, R.string.setting_display_page_code_block_auto_collapse_desc),
            row(R.string.setting_display_page_show_line_numbers_title, R.string.setting_display_page_show_line_numbers_desc),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingPreferencesGeneral,
        altTitles = listOf(R.string.setting_page_preferences_general, R.string.setting_page_hub_chat_actions),
        items = listOf(
            row(R.string.setting_display_page_create_new_conversation_on_start_title, R.string.setting_display_page_create_new_conversation_on_start_desc),
            row(R.string.setting_display_page_send_on_enter_title, R.string.setting_display_page_send_on_enter_desc),
            row(R.string.setting_display_page_show_message_jumper_title, R.string.setting_display_page_show_message_jumper_desc),
            row(R.string.setting_display_page_message_jumper_position_title, R.string.setting_display_page_message_jumper_position_desc),
            row(R.string.setting_display_page_enable_auto_scroll_title, R.string.setting_display_page_enable_auto_scroll_desc),
            row(R.string.setting_display_page_enable_interject_title, R.string.setting_display_page_enable_interject_desc),
            row(R.string.setting_display_page_enable_message_generation_haptic_effect_title, R.string.setting_display_page_enable_message_generation_haptic_effect_desc),
            row(R.string.setting_display_page_keep_screen_on_title, R.string.setting_display_page_keep_screen_on_desc),
            row(R.string.setting_display_page_skip_crop_image_title, R.string.setting_display_page_skip_crop_image_desc),
            row(R.string.setting_display_page_paste_long_text_as_file_title, R.string.setting_display_page_paste_long_text_as_file_desc),
            row(R.string.setting_display_page_paste_long_text_threshold_title),
            row(R.string.setting_display_page_volume_key_scroll_title, R.string.setting_display_page_volume_key_scroll_desc),
            row(R.string.setting_display_page_volume_key_scroll_ratio),
            row(R.string.setting_display_page_bounce_overscroll_title, R.string.setting_display_page_bounce_overscroll_desc),
            row(R.string.error_auto_dismiss_title, R.string.error_auto_dismiss_desc),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingPreferencesNotification,
        altTitles = listOf(R.string.setting_page_preferences_notification, R.string.setting_page_hub_app_alerts),
        items = listOf(
            row(R.string.setting_display_page_notification_message_generated, R.string.setting_display_page_notification_message_generated_desc),
            row(R.string.setting_display_page_live_update_notification, R.string.setting_display_page_live_update_notification_desc),
        ),
    ),

    // ── Models & services ──
    SettingsSearchPageSpec(
        route = Screen.SettingModels(),
        altTitles = listOf(R.string.setting_model_page_title),
        tabs = listOf(modelsModelTab, modelsPromptTab),
        items = listOf(
            row(R.string.setting_model_page_chat_model, R.string.setting_model_page_chat_model_desc, tab = modelsModelTab),
            row(R.string.setting_model_page_fast_model, R.string.setting_model_page_fast_model_desc, tab = modelsModelTab),
            row(R.string.setting_model_page_translate_model, R.string.setting_model_page_translate_model_desc, tab = modelsModelTab),
            row(R.string.setting_model_page_image_recognition_mode, R.string.setting_model_page_image_recognition_mode_desc, tab = modelsModelTab),
            row(R.string.setting_model_page_ocr_model, R.string.setting_model_page_ocr_model_desc, tab = modelsModelTab),
            row(R.string.setting_model_page_compress_model, R.string.setting_model_page_compress_model_desc, tab = modelsModelTab),
            row(R.string.assistant_page_thinking_budget, tab = modelsModelTab),
            row(R.string.setting_model_page_enable_suggestion, tab = modelsModelTab),
            row(R.string.setting_model_page_prompt_title, null, R.string.setting_model_page_prompt, tab = modelsPromptTab),
            row(R.string.setting_model_page_prompt_suggestion, null, R.string.setting_model_page_prompt, tab = modelsPromptTab),
            row(R.string.setting_model_page_prompt_translation, null, R.string.setting_model_page_prompt, tab = modelsPromptTab),
            row(R.string.setting_model_page_prompt_ocr, null, R.string.setting_model_page_prompt, tab = modelsPromptTab),
            row(R.string.setting_model_page_prompt_compress, null, R.string.setting_model_page_prompt, tab = modelsPromptTab),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingRuntime,
        items = listOf(
            row(R.string.setting_runtime_timezone_title, R.string.setting_runtime_timezone_desc),
            row(R.string.setting_model_page_generation_limits),
            row(R.string.setting_model_page_turn_budget, R.string.setting_model_page_turn_budget_desc),
            row(R.string.setting_model_page_max_tool_steps, R.string.setting_model_page_max_tool_steps_desc),
            row(R.string.setting_display_page_parse_text_tool_calls_title, R.string.setting_display_page_parse_text_tool_calls_desc),
            row(R.string.setting_runtime_section_compaction),
            row(R.string.setting_model_page_enable_auto_compaction, R.string.setting_model_page_enable_auto_compaction_desc),
            row(R.string.setting_compaction_engine, R.string.setting_compaction_engine_desc,
                R.string.setting_compaction_engine_model, R.string.setting_compaction_engine_hybrid),
            row(R.string.setting_tomc_compaction_ledger, R.string.setting_tomc_compaction_ledger_desc),
            row(R.string.setting_model_page_auto_compaction_threshold, R.string.setting_model_page_auto_compaction_threshold_desc),
            row(R.string.setting_model_page_auto_compaction_tokens, R.string.setting_model_page_auto_compaction_tokens_desc),
            row(R.string.setting_model_page_auto_compaction_keep_tool_calls),
            row(R.string.setting_model_page_context_compaction_target, R.string.setting_model_page_context_compaction_target_desc),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingProvider,
        altTitles = listOf(R.string.setting_page_providers),
        items = listOf(
            row(R.string.setting_provider_page_add_provider),
            row(R.string.setting_provider_page_import_dialog_title, null, R.string.setting_provider_page_scan_qr_code),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingSearch,
        items = listOf(
            row(R.string.setting_page_search_add_provider),
            row(R.string.setting_page_search_common_options),
            row(R.string.setting_page_search_result_size),
            row(R.string.setting_page_search_snippet_max_chars),
            row(R.string.setting_page_search_scrape_max_chars),
            row(R.string.setting_page_search_web_fetch_tools_title),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingSpeech(),
        altTitles = listOf(R.string.speech_page_title),
        tabs = listOf(speechTtsTab, speechAsrTab),
        items = listOf(
            row(R.string.setting_tts_page_add_provider, tab = speechTtsTab),
            row(R.string.setting_speech_page_playback, tab = speechTtsTab),
            row(R.string.setting_tts_page_default_playback_speed, tab = speechTtsTab),
            row(R.string.setting_display_page_auto_play_tts_title, R.string.setting_display_page_auto_play_tts_desc, tab = speechTtsTab),
            row(R.string.setting_display_page_tts_only_read_quoted_title, R.string.setting_display_page_tts_only_read_quoted_desc, tab = speechTtsTab),
            row(R.string.setting_display_page_tts_read_outside_brackets_title, R.string.setting_display_page_tts_read_outside_brackets_desc, tab = speechTtsTab),
            row(R.string.setting_asr_page_add_provider, tab = speechAsrTab),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingPreferencesNetwork,
        altTitles = listOf(R.string.setting_page_preferences_network),
        items = listOf(
            row(R.string.setting_page_preferences_network_proxy),
            row(R.string.setting_page_preferences_network_user_agent),
            row(R.string.setting_page_preferences_network_first_output_timeout, R.string.setting_page_preferences_network_first_output_timeout_desc),
            row(R.string.setting_page_preferences_network_stall_timeout, R.string.setting_page_preferences_network_stall_timeout_desc),
            row(R.string.setting_page_preferences_network_stream_retries, R.string.setting_page_preferences_network_stream_retries_desc),
        ),
    ),

    // ── Assistant & extensions ──
    SettingsSearchPageSpec(route = Screen.Assistant, altTitles = listOf(R.string.setting_page_assistant)),
    SettingsSearchPageSpec(
        route = Screen.QuickMessages,
        altTitles = listOf(R.string.setting_page_extensions),
        items = listOf(row(R.string.quick_messages_page_add_title)),
    ),
    SettingsSearchPageSpec(
        route = Screen.Prompts(),
        altTitles = listOf(R.string.prompt_page_title, R.string.extensions_page_prompts, R.string.setting_page_extensions),
        tabs = listOf(promptsModeTab, promptsLorebookTab),
        items = listOf(
            row(R.string.prompt_page_add_mode_injection, tab = promptsModeTab),
            row(R.string.prompt_page_add_lorebook, tab = promptsLorebookTab),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.Skills,
        altTitles = listOf(R.string.skills_page_title, R.string.setting_page_extensions),
        items = listOf(
            row(R.string.skills_page_add_title),
            row(R.string.skills_page_import_from_github),
            row(R.string.skill_catalog_title),
            row(R.string.skills_page_new_group),
            row(R.string.skills_page_group_core, R.string.skills_page_group_core_hint),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.Workspaces,
        altTitles = listOf(R.string.workspace_page_title, R.string.setting_page_extensions),
        items = listOf(row(R.string.workspace_page_create)),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingSubAgents,
        altTitles = listOf(R.string.setting_page_sub_agents),
        items = listOf(
            row(R.string.setting_sub_agents_dispatch_profile_title, R.string.setting_sub_agents_dispatch_profile_desc,
                R.string.setting_sub_agents_profile_never, R.string.setting_sub_agents_profile_assist,
                R.string.setting_sub_agents_profile_normal, R.string.setting_sub_agents_profile_burst,
                R.string.setting_sub_agents_profile_unleashed),
            row(R.string.setting_sub_agents_global_concurrency),
            row(R.string.setting_sub_agents_page_runs_title),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingMcp,
        altTitles = listOf(R.string.setting_page_mcp),
        items = listOf(row(R.string.setting_mcp_page_import_title)),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingWorkflows,
        items = listOf(row(R.string.setting_page_workflows_how_it_works_dialog_title)),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingScheduledJobs,
        items = listOf(row(R.string.setting_page_scheduled_jobs_how_it_works_title)),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingToolApprovals,
        items = listOf(
            row(R.string.setting_page_tool_approvals_mode_title, null,
                R.string.setting_page_tool_approvals_mode_auto, R.string.setting_page_tool_approvals_mode_semi,
                R.string.setting_page_tool_approvals_mode_manual),
            row(R.string.setting_page_tool_approvals_mcp_switch, R.string.setting_page_tool_approvals_mcp_switch_desc,
                R.string.setting_page_tool_approvals_mcp_group),
        ),
    ),

    // ── Device ──
    SettingsSearchPageSpec(
        route = Screen.SettingAccessibility,
        items = listOf(
            row(R.string.setting_page_accessibility_open_settings),
            row(R.string.setting_page_accessibility_repair, R.string.setting_page_accessibility_repair_desc),
            row(R.string.setting_page_accessibility_restricted_title, null, R.string.setting_page_accessibility_open_app_info),
            row(R.string.setting_page_accessibility_keepalive_section, null, R.string.setting_page_accessibility_keepalive_open_app_info),
            row(R.string.setting_page_accessibility_overlay_section, null, R.string.setting_page_accessibility_overlay_open_settings),
            row(R.string.setting_page_accessibility_diagnostics_section, null, R.string.setting_page_accessibility_capture_diag),
            row(R.string.setting_page_accessibility_recent_section),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingFloatingBall,
        items = listOf(
            row(R.string.setting_floating_ball_enable_title),
            row(R.string.setting_floating_ball_overlay_title),
            row(R.string.setting_floating_ball_color_title, R.string.setting_floating_ball_preview_desc, R.string.setting_floating_ball_preview_title),
            row(R.string.setting_floating_ball_task_card_title, R.string.setting_floating_ball_task_card_desc),
            row(R.string.setting_floating_ball_done_tap_title, R.string.setting_floating_ball_done_tap_desc),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingNotifications,
        altTitles = listOf(R.string.setting_page_notifications),
        items = listOf(
            row(R.string.setting_page_notifications_open_settings),
            row(R.string.setting_page_notifications_recent_section),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingPermissions,
        altTitles = listOf(R.string.setting_page_permissions),
        items = listOf(
            row(R.string.setting_page_permissions_group_runtime),
            row(R.string.setting_page_permissions_group_special),
            row(R.string.setting_page_permissions_group_services),
            row(R.string.setting_page_permissions_group_auto),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingShizuku,
        items = listOf(
            row(R.string.setting_shizuku_status_app),
            row(R.string.setting_shizuku_status_service),
            row(R.string.setting_shizuku_status_permission),
            row(R.string.setting_shizuku_help_title),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingTermux,
        items = listOf(
            row(R.string.setting_termux_status_app),
            row(R.string.setting_termux_status_permission),
            row(R.string.setting_termux_status_open),
            row(R.string.setting_termux_status_verify),
            row(R.string.setting_termux_section_timeouts),
            row(R.string.setting_termux_command_timeout),
            row(R.string.setting_termux_verify_timeout),
            row(R.string.setting_termux_working_dir),
            row(R.string.setting_termux_max_stdout),
            row(R.string.setting_termux_max_stderr),
            row(R.string.setting_termux_apt_wrap),
            row(R.string.setting_termux_help_allow_external_apps_title),
        ),
    ),

    // ── Connections ──
    SettingsSearchPageSpec(
        route = Screen.SettingSsh,
        items = listOf(
            row(R.string.setting_ssh_page_hosts, null, R.string.setting_ssh_page_add),
            row(R.string.setting_ssh_page_known_hosts),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingSecrets,
        items = listOf(
            row(R.string.setting_secrets_page_list),
            row(R.string.setting_secrets_page_about_title),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingWeb,
        items = listOf(
            row(R.string.setting_page_web_server_port, R.string.setting_page_web_server_port_desc),
            row(R.string.setting_page_web_server_localhost_only, R.string.setting_page_web_server_localhost_only_desc),
            row(R.string.setting_page_web_server_jwt_enable, R.string.setting_page_web_server_jwt_enable_desc),
            row(R.string.setting_page_web_server_password, R.string.setting_page_web_server_password_desc),
            row(R.string.setting_page_web_server_lan_address),
            row(R.string.setting_page_web_server_mdns_address),
            row(R.string.setting_page_web_server_local_address),
            row(R.string.setting_page_web_server_address_note),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingBrowser,
        items = listOf(
            row(R.string.setting_browser_open),
            row(R.string.setting_browser_clear_data),
            row(R.string.setting_browser_prefer_background),
            row(R.string.setting_browser_network_proxy_mode),
            row(R.string.setting_browser_tools_enabled_title),
            row(R.string.setting_browser_search_engine),
            row(R.string.setting_browser_per_tool_timeout),
            row(R.string.setting_browser_single_task_timeout),
        ),
    ),

    // ── Data ──
    SettingsSearchPageSpec(
        route = Screen.Backup(),
        altTitles = listOf(R.string.setting_page_data_backup),
        tabs = listOf(backupLocalTab, backupWebDavTab, backupS3Tab, backupReminderTab),
        items = listOf(
            row(R.string.backup_page_backup_items, tab = backupLocalTab),
            row(R.string.backup_item_assistant_memory, tab = backupLocalTab),
            row(R.string.backup_page_local_backup_export, tab = backupLocalTab),
            row(R.string.backup_page_local_backup_import, tab = backupLocalTab),
            row(R.string.backup_page_import_from_chatbox, tab = backupLocalTab),
            row(R.string.backup_page_import_from_cherry_studio, tab = backupLocalTab),
            row(R.string.backup_page_webdav_server_address, tab = backupWebDavTab),
            row(R.string.backup_page_username, tab = backupWebDavTab),
            row(R.string.backup_page_password, tab = backupWebDavTab),
            row(R.string.backup_secrets_encryption_password, tab = backupWebDavTab),
            row(R.string.backup_page_path, tab = backupWebDavTab),
            row(R.string.backup_page_backup_items, tab = backupWebDavTab),
            row(R.string.backup_item_assistant_memory, tab = backupWebDavTab),
            row(R.string.backup_page_s3_endpoint, tab = backupS3Tab),
            row(R.string.backup_page_s3_access_key_id, tab = backupS3Tab),
            row(R.string.backup_page_s3_secret_access_key, tab = backupS3Tab),
            row(R.string.backup_secrets_encryption_password, tab = backupS3Tab),
            row(R.string.backup_page_s3_bucket, tab = backupS3Tab),
            row(R.string.backup_page_s3_path_style, tab = backupS3Tab),
            row(R.string.backup_page_s3_region, tab = backupS3Tab),
            row(R.string.backup_page_backup_items, tab = backupS3Tab),
            row(R.string.backup_item_assistant_memory, tab = backupS3Tab),
            row(R.string.backup_page_reminder_enable, tab = backupReminderTab),
            row(R.string.backup_page_reminder_interval, tab = backupReminderTab),
        ),
    ),
    SettingsSearchPageSpec(route = Screen.SettingFiles),

    // ── About ──
    SettingsSearchPageSpec(route = Screen.SettingDoctor),
    SettingsSearchPageSpec(
        route = Screen.Log,
        items = listOf(
            row(R.string.log_page_record_requests, R.string.log_page_record_requests_desc),
        ),
    ),
    SettingsSearchPageSpec(
        route = Screen.SettingAbout,
        items = listOf(
            row(R.string.about_page_version),
            row(R.string.about_page_system),
            row(R.string.about_page_github),
            row(R.string.about_page_license),
        ),
    ),
)

/**
 * Builds the static index: every hub row, then every page / tab / row in [settingsSearchPages].
 * Uniqueness is (route, titleRes, parentTitleRes).
 */
fun settingsSearchIndex(
    developerMode: Boolean,
    pages: List<SettingsSearchPageSpec> = settingsSearchPages,
): List<SettingsSearchEntry> {
    val specByRoute = pages.associateBy { it.route }
    val entries = mutableListOf<SettingsSearchEntry>()

    // Placement of a page: its hub group, the titles leading to it (inclusive) and search-only
    // aliases inherited by everything below it.
    class Placement(val groupRes: Int, val path: List<Int>, val aliases: List<Int>)

    val hubPlacement = mutableMapOf<Screen, Placement>()
    for ((def, groupRes) in settingsHubSearchRows()) {
        val route = def.route ?: continue
        hubPlacement[route] = Placement(groupRes, listOf(def.titleRes), emptyList())
        entries += SettingsSearchEntry(
            titleRes = def.titleRes,
            descriptionRes = def.descriptionRes,
            groupRes = groupRes,
            route = route,
            altTitles = specByRoute[route]?.altTitles.orEmpty().filter { it != def.titleRes },
        )
    }

    val resolving = mutableSetOf<Screen>()
    fun placementOf(route: Screen): Placement? {
        hubPlacement[route]?.let { return it }
        val spec = specByRoute[route] ?: return null
        val parent = spec.parent ?: return null
        if (!resolving.add(route)) return null // cycle guard
        val parentPlacement = placementOf(parent)
        resolving.remove(route)
        parentPlacement ?: return null
        return Placement(parentPlacement.groupRes, parentPlacement.path + spec.titleRes, parentPlacement.aliases)
    }

    for (spec in pages) {
        val page = placementOf(spec.route) ?: continue
        if (spec.route !in hubPlacement) {
            entries += SettingsSearchEntry(
                titleRes = spec.titleRes,
                descriptionRes = spec.descriptionRes,
                groupRes = page.groupRes,
                route = spec.route,
                altTitles = spec.altTitles + page.aliases,
                breadcrumbRes = page.path.dropLast(1),
            )
        }
        for (tab in spec.tabs) {
            entries += SettingsSearchEntry(
                titleRes = tab.titleRes,
                groupRes = page.groupRes,
                route = tab.route,
                altTitles = tab.altTitles + page.aliases,
                breadcrumbRes = page.path,
            )
        }
        for (item in spec.items) {
            entries += SettingsSearchEntry(
                titleRes = item.titleRes,
                descriptionRes = item.descriptionRes,
                groupRes = page.groupRes,
                route = item.tab?.route ?: spec.route,
                altTitles = (item.altTitles + page.aliases).filter { it != item.titleRes },
                breadcrumbRes = if (item.tab != null) page.path + item.tab.titleRes else page.path,
            )
        }
    }

    if (developerMode) {
        entries += SettingsSearchEntry(
            titleRes = R.string.accessibility_developer_options,
            descriptionRes = null,
            groupRes = R.string.settings,
            route = Screen.Developer,
        )
    }

    return entries.distinctBy { Triple(it.route, it.titleRes, it.parentTitleRes) }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Matching & ranking
// ─────────────────────────────────────────────────────────────────────────────────────────────

private const val RANK_TITLE = 0
private const val RANK_ALIAS = 1
private const val RANK_DESCRIPTION = 2
private const val RANK_PATH = 3
private const val RANK_GROUP = 4
private const val EXACT_TITLE_BONUS = 10

private fun SettingsSearchItem.searchFields(text: (Int) -> String): List<Pair<String, Int>> = buildList {
    when (val item = this@searchFields) {
        is SettingsSearchEntry -> {
            add(text(item.titleRes) to RANK_TITLE)
            item.altTitles.forEach { add(text(it) to RANK_ALIAS) }
            item.descriptionRes?.let { add(text(it) to RANK_DESCRIPTION) }
        }
        is SettingsSearchDynamicEntry -> {
            if (item.titleRes != 0) add(text(item.titleRes) to RANK_TITLE)
            if (item.title.isNotBlank()) add(item.title to RANK_TITLE)
            item.subtitle?.let { add(it to RANK_DESCRIPTION) }
            if (item.descriptionRes != 0) add(text(item.descriptionRes) to RANK_DESCRIPTION)
        }
    }
    breadcrumbRes.forEach { add(text(it) to RANK_PATH) }
    add(text(groupRes) to RANK_GROUP)
}

private fun searchTokens(query: String): List<String> =
    query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * Lower is better; null = no match.
 *
 * The query is split on whitespace and every token has to hit one of the entry's searchable
 * strings (title, aliases, description, page path, group), so "顶栏 高度" and "顶栏高度" find
 * the same row and a word that only appears in a parent page's name still matches. Each token
 * scores by the best field it hit, and a title equal to the whole query sorts first.
 *
 * [fallbackText] resolves the same resources in a second language (English), so an English
 * term finds a row while the app runs in Chinese.
 */
fun SettingsSearchItem.searchScore(
    query: String,
    text: (Int) -> String,
    fallbackText: ((Int) -> String)? = null,
): Int? {
    val tokens = searchTokens(query)
    if (tokens.isEmpty()) return null
    val fields = searchFields(text) + (fallbackText?.let { searchFields(it) } ?: emptyList())
    var score = 0
    for (token in tokens) {
        score += fields.filter { it.first.contains(token, ignoreCase = true) }
            .minOfOrNull { it.second } ?: return null
    }
    val whole = query.trim()
    if (fields.any { it.second == RANK_TITLE && it.first.equals(whole, ignoreCase = true) }) {
        score -= EXACT_TITLE_BONUS
    }
    return score
}

/** Kept for callers that only need a yes/no. */
fun SettingsSearchItem.matchesSearchQuery(
    query: String,
    text: (Int) -> String,
    fallbackText: ((Int) -> String)? = null,
): Boolean = searchScore(query, text, fallbackText) != null

/** Matching items, best first; ties keep index order (hub rows, then pages, then rows). */
fun rankSettingsSearch(
    query: String,
    items: List<SettingsSearchItem>,
    text: (Int) -> String,
    fallbackText: ((Int) -> String)? = null,
): List<SettingsSearchItem> =
    items.mapIndexedNotNull { index, item ->
        item.searchScore(query, text, fallbackText)?.let { Triple(it, index, item) }
    }
        .sortedWith(compareBy<Triple<Int, Int, SettingsSearchItem>>({ it.first }, { it.second }))
        .map { it.third }
