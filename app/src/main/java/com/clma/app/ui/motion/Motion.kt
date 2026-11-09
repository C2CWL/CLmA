package com.clma.app.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 全站统一动效节拍（对齐 Kelivo：180 / 240 / 320ms + easeOutCubic）。
 * 新动效只用这里的时长、曲线和 spring，别再直接写裸 `fadeIn()` / `tween(xxx)`。
 */
object Motion {
    /** 小元素切换、换会话淡出淡入。 */
    const val FAST = 180

    /** 默认：列表项进出、显隐、内容切换。 */
    const val NORMAL = 240

    /** 页面转场。 */
    const val PAGE = 260

    /** 大块面板 / 慢动作。 */
    const val SLOW = 320

    /** easeOutCubic：快起慢停，Kelivo 主曲线。 */
    val EaseOutCubic: Easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

    /** easeInCubic：用于退出。 */
    val EaseInCubic: Easing = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)

    fun <T> enter(durationMillis: Int = NORMAL, delayMillis: Int = 0): FiniteAnimationSpec<T> =
        tween(durationMillis, delayMillis, EaseOutCubic)

    fun <T> exit(durationMillis: Int = FAST, delayMillis: Int = 0): FiniteAnimationSpec<T> =
        tween(durationMillis, delayMillis, EaseInCubic)

    /** 位置类 spring：无回弹、跟手（指示条、滑块、列表项让位）。 */
    fun <T> springSnappy(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /** 轻回弹 spring：分段开关滑块等。 */
    fun <T> springGentle(): SpringSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = 500f)

    val placementSpring: FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))

    val sizeSpec: FiniteAnimationSpec<IntSize> = tween(NORMAL, easing = EaseOutCubic)
}
