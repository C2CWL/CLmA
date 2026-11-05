package com.clma.app.ui.pages.setting

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Tick01
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.LoadingIndicatorStyle
import com.clma.app.data.datastore.PopupMenuStyle
import com.clma.app.data.datastore.effectiveLoadingIndicatorStyle
import com.clma.app.data.datastore.withLoadingIndicatorStyle
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.components.ui.Select
import com.clma.app.ui.context.LocalNavController
import com.clma.app.ui.theme.CustomColors
import com.clma.app.ui.theme.LocalDarkMode
import com.clma.app.ui.theme.VisualStyle
import com.clma.app.ui.theme.findPresetTheme
import com.clma.app.utils.AppIconStyle
import com.clma.app.utils.applyAppIcon
import com.clma.app.utils.currentAppIcon
import org.koin.androidx.compose.koinViewModel

/**
 * 视觉风格 (route kept as [Screen.SettingPreferencesTheme] so old deep links still land here):
 * the Claude / Gemini palette cards, dynamic colour, the preset / custom theme page, and the
 * 「界面细节」 rows that change how the app looks (popup menus, loading animation, input-bar blur,
 * launcher icon). Colour mode moved to its own page ([SettingColorModePage]).
 */
@Composable
fun SettingPreferencesThemePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    var appIcon by remember(context) { mutableStateOf(currentAppIcon(context)) }
    val currentStyle = VisualStyle.current(settings.themeId, settings.dynamicColor)

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    SettingSubPageScaffold(title = stringResource(R.string.setting_visual_style_title)) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_visual_style_section_palette),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VisualStyle.selectable.forEach { style ->
                        PaletteCard(
                            style = style,
                            selected = style == currentStyle,
                            onClick = {
                                style.themeId?.let { themeId ->
                                    // Same rule as #38: a palette is a preset theme, and dynamic
                                    // colour would hide it, so picking one turns dynamic colour off.
                                    vm.updateSettings(settings.copy(themeId = themeId, dynamicColor = false))
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.setting_visual_style_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        item {
            CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_page_dynamic_color)) },
                    supportingContent = { Text(stringResource(R.string.setting_page_dynamic_color_desc)) },
                    trailingContent = {
                        Switch(
                            checked = settings.dynamicColor,
                            onCheckedChange = { vm.updateSettings(settings.copy(dynamicColor = it)) },
                        )
                    },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingTheme) },
                    headlineContent = { Text(stringResource(R.string.setting_page_theme_setting)) },
                    supportingContent = { Text(stringResource(R.string.setting_page_theme_setting_desc)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                )
            }
        }

        item {
            CardGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = { Text(stringResource(R.string.setting_visual_style_section_details)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_popup_menu_style_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_popup_menu_style_desc)) },
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
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_loading_indicator_style_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_loading_indicator_style_desc)) },
                    trailingContent = {
                        Select(
                            options = LoadingIndicatorStyle.entries,
                            selectedOption = displaySetting.effectiveLoadingIndicatorStyle,
                            onOptionSelected = {
                                updateDisplaySetting(displaySetting.withLoadingIndicatorStyle(it))
                            },
                            optionToString = {
                                stringResource(
                                    when (it) {
                                        LoadingIndicatorStyle.APP_ICON -> R.string.setting_display_page_loading_indicator_style_app_icon
                                        LoadingIndicatorStyle.ATOM_DOTS -> R.string.setting_display_page_loading_indicator_style_atom_dots
                                        LoadingIndicatorStyle.BREATHING_ORB -> R.string.setting_display_page_loading_indicator_style_breathing_orb
                                        LoadingIndicatorStyle.TWIN_ORBIT -> R.string.setting_display_page_loading_indicator_style_twin_orbit
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
                    headlineContent = { Text(stringResource(R.string.setting_display_page_enable_blur_effect_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_enable_blur_effect_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableBlurEffect,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableBlurEffect = it))
                            },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_app_icon_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_app_icon_desc)) },
                    trailingContent = {
                        Select(
                            options = AppIconStyle.entries,
                            selectedOption = appIcon,
                            onOptionSelected = { style ->
                                if (applyAppIcon(context, style)) {
                                    appIcon = style
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.setting_display_page_app_icon_applied),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            optionToString = {
                                stringResource(
                                    when (it) {
                                        AppIconStyle.DEFAULT -> R.string.setting_display_page_app_icon_default
                                        AppIconStyle.RING -> R.string.setting_display_page_app_icon_ring
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
    }
}

/**
 * One palette: four swatches taken from the preset's own colour scheme (light or dark, matching
 * the current mode), its name and a one-line description. The selected card gets a primary border.
 */
@Composable
private fun PaletteCard(
    style: VisualStyle,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val themeId = style.themeId ?: return
    val scheme = findPresetTheme(themeId).getColorScheme(LocalDarkMode.current)
    val shape = RoundedCornerShape(20.dp)
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, borderColor, shape)
            .clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(scheme.background, scheme.surfaceContainer, scheme.primary, scheme.onSurface).forEach { swatch ->
                    Spacer(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(swatch)
                            .border(1.dp, Color.Black.copy(alpha = 0.08f), CircleShape),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(style.nameRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Icon(
                        HugeIcons.Tick01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(16.dp),
                    )
                }
            }
            Text(
                text = stringResource(
                    when (style) {
                        VisualStyle.GEMINI -> R.string.setting_visual_style_palette_gemini_desc
                        else -> R.string.setting_visual_style_palette_claude_desc
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
