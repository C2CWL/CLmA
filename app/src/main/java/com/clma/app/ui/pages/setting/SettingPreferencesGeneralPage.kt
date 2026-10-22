package com.clma.app.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.components.ui.PercentSlider
import com.clma.app.ui.hooks.rememberSharedPreferenceBoolean
import com.clma.app.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 输入与交互 (route kept as [com.clma.app.Screen.SettingPreferencesGeneral]): sending, scrolling,
 * pasting and feedback while chatting. 「错误提示自动消失」 moved here from the request-log page;
 * the look-related rows (loading animation, blur, launcher icon) moved to 视觉风格 and
 * 「解析正文中的工具调用」 to 运行与上下文.
 */
@Composable
fun SettingPreferencesGeneralPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    SettingSubPageScaffold(title = stringResource(R.string.setting_hub_input)) {
        item {
            var createNewConversationOnStart by rememberSharedPreferenceBoolean(
                "create_new_conversation_on_start",
                true
            )
            CardGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = { Text(stringResource(R.string.setting_page_general_group_conversation)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_desc)) },
                    trailingContent = {
                        Switch(
                            checked = createNewConversationOnStart,
                            onCheckedChange = { createNewConversationOnStart = it }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.sendOnEnter,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(sendOnEnter = it))
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_enable_interject_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_enable_interject_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableInterject,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableInterject = it))
                            }
                        )
                    },
                )
            }
        }

        item {
            CardGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = { Text(stringResource(R.string.setting_page_general_group_navigation)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.showMessageJumper,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(showMessageJumper = it))
                            }
                        )
                    },
                )
                if (displaySetting.showMessageJumper) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.messageJumperOnLeft,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(messageJumperOnLeft = it))
                                }
                            )
                        },
                    )
                }
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableAutoScroll,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableAutoScroll = it))
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableVolumeKeyScroll,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableVolumeKeyScroll = it))
                            }
                        )
                    },
                )
                if (displaySetting.enableVolumeKeyScroll) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_ratio)) },
                        supportingContent = {
                            PercentSlider(
                                value = displaySetting.volumeKeyScrollRatio,
                                onValueChange = {
                                    updateDisplaySetting(displaySetting.copy(volumeKeyScrollRatio = it))
                                },
                                valueRange = 0.25f..1.0f,
                                title = stringResource(R.string.setting_display_page_volume_key_scroll_ratio),
                            )
                        }
                    )
                }
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_bounce_overscroll_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_bounce_overscroll_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableBounceOverscroll,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableBounceOverscroll = it))
                            }
                        )
                    },
                )
            }
        }

        item {
            CardGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = { Text(stringResource(R.string.setting_page_general_group_feedback)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableMessageGenerationHapticEffect,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableMessageGenerationHapticEffect = it))
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.error_auto_dismiss_title)) },
                    supportingContent = { Text(stringResource(R.string.error_auto_dismiss_desc)) },
                    trailingContent = {
                        Switch(
                            checked = settings.errorCardAutoDismiss,
                            onCheckedChange = { enabled ->
                                vm.updateSettings { it.copy(errorCardAutoDismiss = enabled) }
                            }
                        )
                    },
                )
            }
        }

        item {
            CardGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = { Text(stringResource(R.string.setting_page_general_group_input_media)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.skipCropImage,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(skipCropImage = it))
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.pasteLongTextAsFile,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(pasteLongTextAsFile = it))
                            }
                        )
                    },
                )
                if (displaySetting.pasteLongTextAsFile) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_threshold_title)) },
                        supportingContent = {
                            var thresholdText by remember(displaySetting.pasteLongTextThreshold) {
                                mutableStateOf(displaySetting.pasteLongTextThreshold.toString())
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextField(
                                    value = thresholdText,
                                    onValueChange = { value ->
                                        thresholdText = value.filter { it.isDigit() }
                                        parsePasteLongTextThreshold(thresholdText)?.let { threshold ->
                                            updateDisplaySetting(displaySetting.copy(pasteLongTextThreshold = threshold))
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    isError = parsePasteLongTextThreshold(thresholdText) == null,
                                    modifier = Modifier.width(100.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}


/**
 * Parses the paste-long-text-as-file threshold field. Returns null for text that is not a
 * valid integer within the persisted 100..10000 range, so the caller can keep showing what
 * the user typed without writing an invalid value to the store.
 */
internal fun parsePasteLongTextThreshold(text: String): Int? {
    val value = text.toIntOrNull() ?: return null
    return value.takeIf { it in 100..10000 }
}
