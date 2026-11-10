package com.clma.app.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import com.clma.app.data.botmode.botsHiddenForManage
import com.clma.app.ui.pages.chat.botmode.BotModeExtrasSection
import com.clma.app.data.botmode.withBotHidden
import com.clma.app.data.datastore.ChatLayout
import androidx.compose.material3.TextButton
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.DrawerStyle
import com.clma.app.data.datastore.effectiveChatLayout
import com.clma.app.data.datastore.withChatLayout
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.components.ui.CleanInputPaddingMaxDp
import com.clma.app.ui.components.ui.CleanInputPaddingMinDp
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


/** 聊天布局: chat layout (Gemini / 简洁 / 现代), its panel knobs and the drawer capsules. */
@Composable
fun SettingChatLayoutPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    SettingSubPageScaffold(title = stringResource(R.string.setting_display_page_chat_layout_title)) {
        item {
            CardGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = { Text(stringResource(R.string.setting_page_layout_display_settings)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_chat_layout_title)) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.setting_display_page_chat_layout_desc))
                            // 「现代」多一句说明它长什么样。
                            if (displaySetting.effectiveChatLayout == ChatLayout.MODERN) {
                                Text(
                                    text = stringResource(R.string.setting_display_page_chat_layout_modern_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    trailingContent = {
                        Select(
                            options = ChatLayout.entries.filter { it != ChatLayout.ORIGINAL },
                            selectedOption = displaySetting.effectiveChatLayout,
                            onOptionSelected = {
                                updateDisplaySetting(displaySetting.withChatLayout(it))
                            },
                            optionToString = {
                                stringResource(
                                    when (it) {
                                        ChatLayout.GEMINI -> R.string.setting_display_page_chat_layout_gemini
                                        ChatLayout.ORIGINAL -> R.string.setting_display_page_chat_layout_original
                                        ChatLayout.CLEAN -> R.string.setting_display_page_chat_layout_clean
                                        ChatLayout.MODERN -> R.string.setting_display_page_chat_layout_modern
                                    }
                                )
                            },
                            containerColor = CustomColors.listItemColors.containerColor,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.width(150.dp),
                        )
                    },
                )
                // CLEAN / MODERN share the header panel + two-row composer; their panel knobs
                // still apply. ORIGINAL hides them so the old chrome stays untouched; GEMINI keeps
                // the header height / composer inset (both still used) but drops the material
                // knobs, because its header is bare text and its composer is a solid pill.
                val layout = displaySetting.effectiveChatLayout
                // 不再用 if 直接增删：传 visible，切换布局时这些子项平滑长出 / 收起。
                val panelVisible = layout != ChatLayout.ORIGINAL
                run {
                    // Glass applies to CLEAN and MODERN; GEMINI keeps a solid pill.
                    run {
                        item(
                            visible = panelVisible && (layout == ChatLayout.CLEAN || layout == ChatLayout.MODERN),
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
                    }
                    item(
                        visible = panelVisible && layout != ChatLayout.GEMINI,
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
                        visible = panelVisible,
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
                        visible = panelVisible,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_clean_layout_bottom_padding_title)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_display_page_clean_layout_bottom_padding_desc))
                                SettingSlider(
                                    value = displaySetting.cleanLayoutBottomPadding,
                                    onValueChange = {
                                        updateDisplaySetting(displaySetting.copy(cleanLayoutBottomPadding = it))
                                    },
                                    valueRange = CleanInputPaddingMinDp..CleanInputPaddingMaxDp,
                                    title = stringResource(R.string.setting_display_page_clean_layout_bottom_padding_title),
                                    valueLabel = { "${it.roundToInt()} dp" },
                                    parseInput = { parseIntInRange(it, CleanInputPaddingMinDp..CleanInputPaddingMaxDp) },
                                    snap = { snapToInt(it, CleanInputPaddingMinDp..CleanInputPaddingMaxDp) },
                                    rangeHint = "0 – 24 dp",
                                )
                            }
                        },
                    )
                    item(
                        visible = panelVisible && layout != ChatLayout.GEMINI,
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
                    headlineContent = { Text(stringResource(R.string.setting_display_page_drawer_style_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_drawer_style_desc)) },
                    trailingContent = {
                        Select(
                            options = DrawerStyle.entries,
                            selectedOption = displaySetting.drawerStyle,
                            onOptionSelected = {
                                updateDisplaySetting(displaySetting.copy(drawerStyle = it))
                            },
                            optionToString = {
                                stringResource(
                                    when (it) {
                                        DrawerStyle.CARD -> R.string.drawer_style_card
                                        DrawerStyle.RAIL -> R.string.drawer_style_rail
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
                item(
                    headlineContent = { Text(stringResource(R.string.bot_mode_setting_title)) },
                    supportingContent = { Text(stringResource(R.string.bot_mode_setting_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.botModeEnabled,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(botModeEnabled = it))
                            }
                        )
                    },
                )
            }
            if (displaySetting.botModeEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BotModeExtrasSection(
                    settings = settings.copy(displaySetting = displaySetting),
                    displaySetting = displaySetting,
                    updateDisplaySetting = ::updateDisplaySetting,
                    updateSettings = { vm.updateSettings(it) },
                )
                val hiddenBots = settings.copy(displaySetting = displaySetting).botsHiddenForManage()
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.bot_mode_hidden_manage_title)) },
                ) {
                    if (hiddenBots.isEmpty()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.bot_mode_hidden_empty)) },
                            supportingContent = { Text(stringResource(R.string.bot_mode_hidden_manage_desc)) },
                        )
                    } else {
                        hiddenBots.forEach { bot ->
                            item(
                                headlineContent = {
                                    Text(bot.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) })
                                },
                                supportingContent = { Text(stringResource(R.string.bot_mode_hidden_manage_desc)) },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            updateDisplaySetting(displaySetting.withBotHidden(bot.id, false))
                                        }
                                    ) {
                                        Text(stringResource(R.string.bot_mode_hidden_unhide))
                                    }
                                },
                            )
                        }
                    }
                }
                }
            }
        }
    }
}
