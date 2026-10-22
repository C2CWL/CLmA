package com.clma.app.ui.theme

import androidx.annotation.StringRes
import com.clma.app.R

/**
 * 视觉风格：设置页「视觉风格」里可选的两套完整配色。它不是一套新的主题系统，只是现有预设主题
 * ([PresetThemes]) 的快捷入口——选中后写入 `Settings.themeId` 并关闭动态取色，其余照旧由
 * [RikkahubTheme] 处理。[OTHER] 表示当前用的是别的预设/自定义主题或动态取色，仅用于显示。
 */
enum class VisualStyle(val themeId: String?, @StringRes val nameRes: Int) {
    CLAUDE("claude", R.string.theme_name_claude),
    GEMINI("gemini", R.string.theme_name_gemini),
    OTHER(null, R.string.setting_visual_style_other);

    companion object {
        /** The two styles shown in the picker. */
        val selectable: List<VisualStyle> = listOf(CLAUDE, GEMINI)

        /** What the app currently looks like. Dynamic colour overrides any preset, so it reads as [OTHER]. */
        fun current(themeId: String, dynamicColor: Boolean): VisualStyle = when {
            dynamicColor -> OTHER
            themeId == CLAUDE.themeId -> CLAUDE
            themeId == GEMINI.themeId -> GEMINI
            else -> OTHER
        }
    }
}
