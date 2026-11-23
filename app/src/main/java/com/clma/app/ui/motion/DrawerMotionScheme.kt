package com.clma.app.ui.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.MotionScheme

/**
 * 抽屉专用动效：保留 standard 的克制收起（FastEffects），只把 spatial（打开/关闭的位移动画）
 * 调快、去弹。standard 的 DefaultSpatial 是 spring(0.9, 700)，偏慢偏软；这里改为
 * spring(1.0, 1300)——临界阻尼无回弹，不会重现 expressive(0.8, 380) 因过冲触发的焦点竞争闪退
 * （见 #129）。
 */
object DrawerMotionScheme : MotionScheme {
    private val base = MotionScheme.standard()

    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
        spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1300f)

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = base.fastSpatialSpec()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = base.slowSpatialSpec()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = base.defaultEffectsSpec()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = base.fastEffectsSpec()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = base.slowEffectsSpec()
}
