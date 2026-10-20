package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.PopupMenuStyle
import me.rerere.rikkahub.data.datastore.SettingsHubStyle
import me.rerere.rikkahub.data.datastore.ToolCallStyle
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.ui.components.message.THINKING_STEPS_VISIBLE_ALL
import me.rerere.rikkahub.ui.components.message.THINKING_STEPS_VISIBLE_MAX
import me.rerere.rikkahub.ui.components.message.thinkingStepsVisibleFromSlider
import me.rerere.rikkahub.ui.components.message.thinkingStepsVisibleToSlider
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.PercentSlider
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.SettingSlider
import me.rerere.rikkahub.ui.components.ui.parseIntInRange
import me.rerere.rikkahub.ui.components.ui.snapToInt
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.FontScale
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.ui.theme.resolveUiFontFamily
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.io.File
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import me.rerere.rikkahub.ui.theme.LocalBaseFontScale
import androidx.compose.material3.MaterialTheme

@Composable
fun SettingPreferencesUIPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val chatFontFamily = rememberChatFontFamily(displaySetting)
    val uiFontFamily = remember(displaySetting.uiFontFamily, displaySetting.uiCustomFontPath) {
        displaySetting.resolveUiFontFamily(context)
    }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val importSuccessMsg = stringResource(R.string.setting_display_page_custom_font_import_success)
    val importFailedMsg = stringResource(R.string.setting_display_page_custom_font_import_failed)
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    importCustomChatFontInternal(context, uri, CHAT_FONT_PREFIX)
                }
            }.onSuccess { importedFont ->
                updateDisplaySetting(
                    displaySetting.copy(
                        chatFontFamily = ChatFontFamily.CUSTOM,
                        chatCustomFontPath = importedFont.relativePath,
                        chatCustomFontName = importedFont.displayName,
                    )
                )
                toaster.show(importSuccessMsg, type = ToastType.Success)
            }.onFailure { error ->
                toaster.show(importFailedMsg.format(error.message.orEmpty()), type = ToastType.Error)
            }
        }
    }

    val uiFontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    importCustomChatFontInternal(context, uri, UI_FONT_PREFIX)
                }
            }.onSuccess { importedFont ->
                updateDisplaySetting(
                    displaySetting.copy(
                        uiFontFamily = ChatFontFamily.CUSTOM,
                        uiCustomFontPath = importedFont.relativePath,
                        uiCustomFontName = importedFont.displayName,
                    )
                )
                toaster.show(importSuccessMsg, type = ToastType.Success)
            }.onFailure { error ->
                toaster.show(importFailedMsg.format(error.message.orEmpty()), type = ToastType.Error)
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_ui))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_ui_style_section)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_settings_hub_style_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_settings_hub_style_desc))
                        },
                        trailingContent = {
                            Select(
                                options = SettingsHubStyle.entries,
                                selectedOption = displaySetting.settingsHubStyle,
                                onOptionSelected = {
                                    updateDisplaySetting(displaySetting.copy(settingsHubStyle = it))
                                },
                                optionToString = {
                                    stringResource(
                                        when (it) {
                                            SettingsHubStyle.CLASSIC -> R.string.setting_page_settings_hub_style_classic
                                            SettingsHubStyle.REORGANIZED -> R.string.setting_page_settings_hub_style_reorganized
                                        }
                                    )
                                },
                                containerColor = CustomColors.listItemColors.containerColor,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.width(150.dp),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_popup_menu_style_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_popup_menu_style_desc))
                        },
                        trailingContent = {
                            Select(
                                options = PopupMenuStyle.entries,
                                selectedOption = displaySetting.popupMenuStyle,
                                onOptionSelected = {
                                    updateDisplaySetting(displaySetting.copy(popupMenuStyle = it))
                                },
                                optionToString = {
                                    stringResource(
                                        when (it) {
                                            PopupMenuStyle.CLASSIC -> R.string.setting_display_page_popup_menu_style_classic
                                            PopupMenuStyle.EXPRESSIVE -> R.string.setting_display_page_popup_menu_style_expressive
                                        }
                                    )
                                },
                                containerColor = CustomColors.listItemColors.containerColor,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.width(150.dp),
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_layout_display_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_clean_layout_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_clean_layout_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.cleanLayout,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(cleanLayout = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.cleanLayout) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_clean_layout_glass_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_clean_layout_glass_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.cleanLayoutGlass,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(cleanLayoutGlass = it))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_clean_layout_top_opacity_title)) },
                            supportingContent = {
                                Column {
                                    Text(stringResource(R.string.setting_display_page_clean_layout_opacity_desc))
                                    PercentSlider(
                                        value = displaySetting.cleanLayoutTopOpacity,
                                        onValueChange = {
                                            updateDisplaySetting(displaySetting.copy(cleanLayoutTopOpacity = it))
                                        },
                                        valueRange = 0f..1f,
                                        title = stringResource(R.string.setting_display_page_clean_layout_top_opacity_title),
                                    )
                                }
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_clean_layout_top_height_title)) },
                            supportingContent = {
                                Column {
                                    Text(stringResource(R.string.setting_display_page_clean_layout_top_height_desc))
                                    SettingSlider(
                                        value = displaySetting.cleanLayoutTopHeight,
                                        onValueChange = {
                                            updateDisplaySetting(displaySetting.copy(cleanLayoutTopHeight = it))
                                        },
                                        valueRange = 40f..72f,
                                        title = stringResource(R.string.setting_display_page_clean_layout_top_height_title),
                                        valueLabel = { "${it.roundToInt()} dp" },
                                        parseInput = { parseIntInRange(it, 40f..72f) },
                                        snap = { snapToInt(it, 40f..72f) },
                                        rangeHint = "40 – 72 dp",
                                    )
                                }
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_clean_layout_bottom_padding_title)) },
                            supportingContent = {
                                Column {
                                    Text(stringResource(R.string.setting_display_page_clean_layout_bottom_padding_desc))
                                    SettingSlider(
                                        value = displaySetting.cleanLayoutBottomPadding,
                                        onValueChange = {
                                            updateDisplaySetting(displaySetting.copy(cleanLayoutBottomPadding = it))
                                        },
                                        valueRange = 4f..10f,
                                        title = stringResource(R.string.setting_display_page_clean_layout_bottom_padding_title),
                                        valueLabel = { "${it.roundToInt()} dp" },
                                        parseInput = { parseIntInRange(it, 4f..10f) },
                                        snap = { snapToInt(it, 4f..10f) },
                                        rangeHint = "4 – 10 dp",
                                    )
                                }
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_clean_layout_bottom_opacity_title)) },
                            supportingContent = {
                                Column {
                                    Text(stringResource(R.string.setting_display_page_clean_layout_opacity_desc))
                                    PercentSlider(
                                        value = displaySetting.cleanLayoutBottomOpacity,
                                        onValueChange = {
                                            updateDisplaySetting(displaySetting.copy(cleanLayoutBottomOpacity = it))
                                        },
                                        valueRange = 0f..1f,
                                        title = stringResource(R.string.setting_display_page_clean_layout_bottom_opacity_title),
                                    )
                                }
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_drawer_capsules_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_drawer_capsules_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.drawerCapsules,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(drawerCapsules = it))
                                }
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_message_display_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showUserAvatar,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showUserAvatar = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showAssistantBubble,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showAssistantBubble = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_bubble_opacity_title)) },
                        supportingContent = {
                            PercentSlider(
                                value = displaySetting.bubbleOpacity,
                                onValueChange = {
                                    updateDisplaySetting(displaySetting.copy(bubbleOpacity = it))
                                },
                                valueRange = 0.1f..1.0f,
                                title = stringResource(R.string.setting_display_page_bubble_opacity_title),
                            )
                        }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showModelIcon,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showModelIcon = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_model_name_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_model_name_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showModelName,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showModelName = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_datetime_in_message_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_datetime_in_message_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showDateTimeInMessage,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showDateTimeInMessage = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showTokenUsage,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showTokenUsage = it))
                                }
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_thinking_tools_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showThinkingContent,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showThinkingContent = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.autoCloseThinking,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(autoCloseThinking = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_fold_process_narration_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_fold_process_narration_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.foldProcessNarration,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(foldProcessNarration = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_thinking_steps_visible_title)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_display_page_thinking_steps_visible_desc))
                                val allLabel = stringResource(R.string.setting_display_page_thinking_steps_visible_all)
                                val stepsRange = 0f..(THINKING_STEPS_VISIBLE_MAX + 1).toFloat()
                                SettingSlider(
                                    value = thinkingStepsVisibleToSlider(
                                        displaySetting.thinkingStepsVisibleCount
                                    ).toFloat(),
                                    onValueChange = {
                                        updateDisplaySetting(
                                            displaySetting.copy(
                                                thinkingStepsVisibleCount = thinkingStepsVisibleFromSlider(it.roundToInt())
                                            )
                                        )
                                    },
                                    valueRange = stepsRange,
                                    title = stringResource(R.string.setting_display_page_thinking_steps_visible_title),
                                    valueLabel = { raw ->
                                        val count = thinkingStepsVisibleFromSlider(raw.roundToInt())
                                        if (count == THINKING_STEPS_VISIBLE_ALL) allLabel
                                        else "${count.coerceIn(0, THINKING_STEPS_VISIBLE_MAX)}"
                                    },
                                    parseInput = { input ->
                                        val trimmed = input.trim()
                                        if (trimmed.equals(allLabel, ignoreCase = true)) {
                                            (THINKING_STEPS_VISIBLE_MAX + 1).toFloat()
                                        } else {
                                            parseIntInRange(trimmed, stepsRange)
                                        }
                                    },
                                    snap = { snapToInt(it, stepsRange) },
                                    rangeHint = "0 – $THINKING_STEPS_VISIBLE_MAX / $allLabel",
                                )
                            }
                        }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_tool_call_style_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_tool_call_style_desc))
                        },
                        trailingContent = {
                            Select(
                                options = ToolCallStyle.entries,
                                selectedOption = displaySetting.toolCallStyle,
                                onOptionSelected = {
                                    updateDisplaySetting(displaySetting.copy(toolCallStyle = it))
                                },
                                optionToString = {
                                    stringResource(
                                        when (it) {
                                            ToolCallStyle.CLASSIC -> R.string.setting_display_page_tool_call_style_classic
                                            ToolCallStyle.CAPSULE -> R.string.setting_display_page_tool_call_style_capsule
                                        }
                                    )
                                },
                                containerColor = CustomColors.listItemColors.containerColor,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.width(150.dp),
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_font_display_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_font_family_title)) },
                        supportingContent = {
                            Select(
                                options = ChatFontFamily.entries,
                                selectedOption = displaySetting.chatFontFamily,
                                onOptionSelected = { family ->
                                    if (family == ChatFontFamily.CUSTOM && displaySetting.chatCustomFontPath.isBlank()) {
                                        fontPickerLauncher.launch(CustomFontMimeTypesUI)
                                    } else {
                                        updateDisplaySetting(displaySetting.copy(chatFontFamily = family))
                                    }
                                },
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .fillMaxWidth(),
                                optionToString = { it.labelUI() },
                                optionLeading = { family ->
                                    Text(
                                        text = "Aa",
                                        fontFamily = family.toFontFamilyUI(chatFontFamily),
                                    )
                                }
                            )
                        }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_custom_font_title)) },
                        supportingContent = {
                            Text(
                                if (displaySetting.chatCustomFontName.isNotBlank()) {
                                    displaySetting.chatCustomFontName
                                } else {
                                    stringResource(R.string.setting_display_page_custom_font_not_imported)
                                }
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = { fontPickerLauncher.launch(CustomFontMimeTypesUI) }
                                ) {
                                    Icon(
                                        HugeIcons.FileImport,
                                        contentDescription = stringResource(
                                            R.string.setting_display_page_custom_font_import
                                        )
                                    )
                                }
                                if (displaySetting.chatCustomFontPath.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            deleteCustomChatFontInternal(context, displaySetting.chatCustomFontPath)
                                            updateDisplaySetting(
                                                displaySetting.copy(
                                                    chatFontFamily = ChatFontFamily.DEFAULT,
                                                    chatCustomFontPath = "",
                                                    chatCustomFontName = "",
                                                )
                                            )
                                        }
                                    ) {
                                        Icon(
                                            HugeIcons.Delete02,
                                            contentDescription = stringResource(
                                                R.string.setting_display_page_custom_font_remove
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_ui_font_family_title)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_display_page_ui_font_family_desc))
                                Select(
                                    options = ChatFontFamily.entries,
                                    selectedOption = displaySetting.uiFontFamily,
                                    onOptionSelected = { family ->
                                        if (family == ChatFontFamily.CUSTOM && displaySetting.uiCustomFontPath.isBlank()) {
                                            uiFontPickerLauncher.launch(CustomFontMimeTypesUI)
                                        } else {
                                            updateDisplaySetting(displaySetting.copy(uiFontFamily = family))
                                        }
                                    },
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .fillMaxWidth(),
                                    optionToString = { it.labelUI() },
                                    optionLeading = { family ->
                                        Text(
                                            text = "Aa",
                                            fontFamily = family.toFontFamilyUI(uiFontFamily),
                                        )
                                    }
                                )
                            }
                        }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_ui_custom_font_title)) },
                        supportingContent = {
                            Text(
                                if (displaySetting.uiCustomFontName.isNotBlank()) {
                                    displaySetting.uiCustomFontName
                                } else {
                                    stringResource(R.string.setting_display_page_custom_font_not_imported)
                                }
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = { uiFontPickerLauncher.launch(CustomFontMimeTypesUI) }
                                ) {
                                    Icon(
                                        HugeIcons.FileImport,
                                        contentDescription = stringResource(
                                            R.string.setting_display_page_custom_font_import
                                        )
                                    )
                                }
                                if (displaySetting.uiCustomFontPath.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            deleteCustomChatFontInternal(context, displaySetting.uiCustomFontPath)
                                            updateDisplaySetting(
                                                displaySetting.copy(
                                                    uiFontFamily = ChatFontFamily.DEFAULT,
                                                    uiCustomFontPath = "",
                                                    uiCustomFontName = "",
                                                )
                                            )
                                        }
                                    ) {
                                        Icon(
                                            HugeIcons.Delete02,
                                            contentDescription = stringResource(
                                                R.string.setting_display_page_custom_font_remove
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_font_size_title)) },
                        supportingContent = {
                            Column {
                                PercentSlider(
                                    value = displaySetting.fontSizeRatio,
                                    onValueChange = {
                                        updateDisplaySetting(displaySetting.copy(fontSizeRatio = it))
                                    },
                                    valueRange = FontScale.CHAT_RANGE,
                                    title = stringResource(R.string.setting_display_page_font_size_title),
                                ) { ratio ->
                                    // Follows the thumb while dragging; the setting is saved on release.
                                    MarkdownBlock(
                                        content = stringResource(R.string.setting_display_page_font_size_preview),
                                        style = LocalTextStyle.current.copy(
                                            fontSize = LocalTextStyle.current.fontSize * ratio,
                                            lineHeight = LocalTextStyle.current.lineHeight * ratio,
                                            fontFamily = chatFontFamily
                                        )
                                    )
                                }
                            }
                        }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_ui_font_scale_title)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_display_page_ui_font_scale_desc))
                                PercentSlider(
                                    value = FontScale.uiScale(displaySetting.uiFontScale),
                                    onValueChange = {
                                        updateDisplaySetting(displaySetting.copy(uiFontScale = it))
                                    },
                                    valueRange = FontScale.UI_RANGE,
                                    title = stringResource(R.string.setting_display_page_ui_font_scale_title),
                                ) { candidate ->
                                    // The app already renders at the saved scale; scale the sample
                                    // by candidate/current so it shows the size the thumb points at.
                                    val currentScale = LocalDensity.current.fontScale
                                    val systemScale = LocalBaseFontScale.current ?: currentScale
                                    val ratio = FontScale.previewRatio(systemScale, currentScale, candidate)
                                    val sample = MaterialTheme.typography.bodyMedium
                                    Text(
                                        text = stringResource(R.string.setting_display_page_ui_font_scale_preview),
                                        style = sample.copy(
                                            fontSize = sample.fontSize * ratio,
                                            lineHeight = sample.lineHeight * ratio,
                                        ),
                                    )
                                }
                            }
                        }
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_code_display_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.codeBlockAutoWrap,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(codeBlockAutoWrap = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.codeBlockAutoCollapse,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(codeBlockAutoCollapse = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showLineNumbers,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showLineNumbers = it))
                                }
                            )
                        },
                    )
                }
            }
        }
    }
}

private val CustomFontMimeTypesUI = arrayOf(
    "font/*",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/vnd.ms-opentype",
    "application/octet-stream",
    "*/*",
)

private val CustomFontExtensionsUI = setOf("ttf", "otf", "ttc")

private data class ImportedChatFontUI(
    val relativePath: String,
    val displayName: String,
)

@Composable
private fun ChatFontFamily.labelUI(): String = when (this) {
    ChatFontFamily.DEFAULT -> stringResource(R.string.setting_display_page_chat_font_family_default)
    ChatFontFamily.SERIF -> stringResource(R.string.setting_display_page_chat_font_family_serif)
    ChatFontFamily.MONOSPACE -> stringResource(R.string.setting_display_page_chat_font_family_monospace)
    ChatFontFamily.CUSTOM -> stringResource(R.string.setting_display_page_chat_font_family_custom)
}

private fun ChatFontFamily.toFontFamilyUI(customFontFamily: FontFamily): FontFamily = when (this) {
    ChatFontFamily.DEFAULT -> FontFamily.Default
    ChatFontFamily.SERIF -> FontFamily.Serif
    ChatFontFamily.MONOSPACE -> FontFamily.Monospace
    ChatFontFamily.CUSTOM -> customFontFamily
}

// Chat and UI fonts are stored side by side in fonts/ (archived by the FONTS backup item) and
// never replace each other: each import only swaps files with its own prefix.
private const val CHAT_FONT_PREFIX = "chat_font"
private const val UI_FONT_PREFIX = "ui_font"

private fun importCustomChatFontInternal(context: Context, uri: Uri, prefix: String): ImportedChatFontUI {
    val displayName = FileUtils.getFileNameFromUri(context, uri)?.takeIf { it.isNotBlank() } ?: "custom_font"
    val extension = displayName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it in CustomFontExtensionsUI }
        ?: "ttf"
    val fontDir = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
    val targetFile = File(fontDir, "$prefix.${System.currentTimeMillis()}.$extension")
    val tempFile = File(fontDir, "${prefix}_import.tmp")

    try {
        tempFile.delete()
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open selected font")

        runCatching {
            Typeface.createFromFile(tempFile)
        }.onFailure { error ->
            throw IllegalArgumentException(error.message ?: "Invalid font file", error)
        }

        replaceCustomChatFontInternal(fontDir, tempFile, targetFile, prefix)
    } catch (error: Throwable) {
        tempFile.delete()
        throw error
    }

    val relativePath = FileUtils.getRelativePathInFilesDir(context.filesDir, targetFile)
        ?: "${FileFolders.FONTS}/${targetFile.name}"
    return ImportedChatFontUI(relativePath = relativePath, displayName = displayName)
}

private fun replaceCustomChatFontInternal(fontDir: File, tempFile: File, targetFile: File, prefix: String) {
    val existingFiles = fontDir.listFiles { file ->
        file.isFile && file.name.startsWith("$prefix.") && file != tempFile
    }?.toList().orEmpty()
    val backups = existingFiles.map { file ->
        file to File(fontDir, "previous_${file.name}").also { it.delete() }
    }

    try {
        backups.forEach { (file, backup) ->
            check(file.renameTo(backup)) { "Unable to prepare existing font for replacement" }
        }
        check(tempFile.renameTo(targetFile)) { "Unable to save selected font" }
        backups.forEach { (_, backup) -> backup.delete() }
    } catch (error: Throwable) {
        tempFile.delete()
        backups.forEach { (file, backup) ->
            if (!file.exists() && backup.exists()) {
                backup.renameTo(file)
            }
        }
        throw error
    }
}

private fun deleteCustomChatFontInternal(context: Context, relativePath: String) {
    val filesDir = runCatching { context.filesDir.canonicalFile }.getOrNull() ?: return
    val fontFile = runCatching { File(filesDir, relativePath).canonicalFile }.getOrNull() ?: return
    if (fontFile.path.startsWith("${filesDir.path}${File.separator}")) {
        fontFile.delete()
    }
}
