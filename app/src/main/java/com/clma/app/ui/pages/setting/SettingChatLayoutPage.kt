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
import com.clma.app.data.datastore.ChatLayout
import com.clma.app.data.datastore.DisplaySetting
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


/** 聊天布局: chat layout (原版 / 简洁 / 暖阳), its panel knobs and the drawer capsules. */
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
                    supportingContent = { Text(stringResource(R.string.setting_display_page_chat_layout_desc)) },
                    trailingContent = {
                        Select(
                            options = ChatLayout.entries,
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
                                        ChatLayout.CLAUDE -> R.string.setting_display_page_chat_layout_claude
                                    }
                                )
                            },
                            containerColor = CustomColors.listItemColors.containerColor,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.width(150.dp),
                        )
                    },
                )
                // CLEAN / CLAUDE share the flat header + two-row composer; their panel knobs
                // still apply. ORIGINAL hides them so the old chrome stays untouched; GEMINI keeps
                // the header height / composer inset (both still used) but drops the material
                // knobs, because its header is bare text and its composer is a solid pill.
                val layout = displaySetting.effectiveChatLayout
                if (layout != ChatLayout.ORIGINAL) {
                    // Glass is a CLEAN-only material; CLAUDE keeps a solid paper card.
                    if (layout == ChatLayout.CLEAN) {
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
                    }
                    if (layout != ChatLayout.GEMINI) item(
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
                    if (layout != ChatLayout.GEMINI) item(
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
    }
}
