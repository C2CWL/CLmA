package com.clma.app.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.ToolCallStyle
import com.clma.app.ui.components.message.THINKING_STEPS_VISIBLE_ALL
import com.clma.app.ui.components.message.THINKING_STEPS_VISIBLE_MAX
import com.clma.app.ui.components.message.thinkingStepsVisibleFromSlider
import com.clma.app.ui.components.message.thinkingStepsVisibleToSlider
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.components.ui.PercentSlider
import com.clma.app.ui.components.ui.Select
import com.clma.app.ui.components.ui.SettingSlider
import com.clma.app.ui.components.ui.parseIntInRange
import com.clma.app.ui.components.ui.snapToInt
import com.clma.app.ui.theme.CustomColors
import com.clma.app.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt
import androidx.compose.material3.MaterialTheme


/**
 * 消息显示 (route kept as [com.clma.app.Screen.SettingPreferencesUI]): how messages, thinking,
 * tool calls and code blocks look. Layout, fonts and the interface-style rows that used to share
 * this page now live on 聊天布局 / 字体与排版 / 视觉风格.
 */
@Composable
fun SettingPreferencesUIPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    SettingSubPageScaffold(title = stringResource(R.string.setting_hub_message_display)) {
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
                if (displaySetting.showDateTimeInMessage) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_message_end_time_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_message_end_time_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showMessageEndTime,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showMessageEndTime = it))
                                }
                            )
                        },
                    )
                }
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
