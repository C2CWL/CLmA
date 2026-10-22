package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.ui.components.ui.FrostedPlateSpec
import me.rerere.rikkahub.ui.components.ui.frostedPlateSpec

/*
 * 抽屉里的磨砂卡片（顶部身份/搜索卡、底部助手卡、备份提醒）。
 *
 * 抽屉面板本身已经是一整块磨砂（HazeBlurStyle.Material3，surface 底色、几乎不叠色），
 * 卡片若再按全局的 FROSTED_PLATE_TINT_ALPHA (0.70) 叠一层 surfaceContainerHigh，
 * 浅色下发白、深色下发黑，看起来像两块实心色块。抽屉里改用更淡的 surface 叠色，
 * 让卡片和面板融在一起，主要靠一圈细描边区分层次。全局常量不动，标题胶囊等其它磨砂不受影响。
 */

/** 深色主题下抽屉卡片的叠色透明度。 */
internal const val DRAWER_FROST_TINT_ALPHA_DARK = 0.40f

/** 浅色主题下抽屉卡片的叠色透明度。 */
internal const val DRAWER_FROST_TINT_ALPHA_LIGHT = 0.45f

/** 磨砂卡片描边（outlineVariant）的透明度。 */
internal const val DRAWER_FROST_OUTLINE_ALPHA = 0.5f

/** 抽屉卡片的磨砂参数：判定规则与 [frostedPlateSpec] 相同，只是叠色更淡；不磨砂时为 null。 */
internal fun drawerFrostedPlateSpec(photoShown: Boolean, sdkInt: Int, darkTheme: Boolean): FrostedPlateSpec? =
    frostedPlateSpec(photoShown = photoShown, sdkInt = sdkInt)?.copy(
        tintAlpha = if (darkTheme) DRAWER_FROST_TINT_ALPHA_DARK else DRAWER_FROST_TINT_ALPHA_LIGHT,
    )
