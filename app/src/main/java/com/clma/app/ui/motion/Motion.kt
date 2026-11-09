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
 * 全站统一动效节拍：短、利落、有边界。
 *
 * 手感目标不是「软绵绵飘过去」，也不是硬切——是快进快出、起止清楚。
 * 新动效只用这里的时长、曲线和 spring，别再直接写裸 `fadeIn()` / `tween(xxx)`。
 */
object Motion {
    /** 换会话淡入淡出、小元素切换。 */
    const val FAST = 120

    /** 列表项显隐、内容交叉淡入。 */
    const val NORMAL = 160

    /** 页面转场、侧栏底栏互换。 */
    const val PAGE = 180

    /** 大块面板；尽量少用，优先 NORMAL。 */
    const val SLOW = 220

    /**
     * 锐利缓出：一开始就快走完大部分路程，尾部几乎不拖。
     * （比 easeOutCubic 更有「顿点」。）
     */
    val EaseOutSharp: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * 锐利缓入：退出时迅速收掉，不拖尾。
     */
    val EaseInSharp: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    fun <T> enter(durationMillis: Int = NORMAL, delayMillis: Int = 0): FiniteAnimationSpec<T> =
        tween(durationMillis, delayMillis, EaseOutSharp)

    fun <T> exit(durationMillis: Int = FAST, delayMillis: Int = 0): FiniteAnimationSpec<T> =
        tween(durationMillis, delayMillis, EaseInSharp)

    /** 位置类 spring：无回弹、偏硬（指示条、列表项让位）。 */
    fun <T> springSnappy(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** 分段开关滑块：跟手、几乎不回弹。 */
    fun <T> springGentle(): SpringSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = 700f)

    val placementSpring: FiniteAnimationSpec<IntOffset> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
            visibilityThreshold = IntOffset(1, 1),
        )

    val sizeSpec: FiniteAnimationSpec<IntSize> = tween(NORMAL, easing = EaseOutSharp)
}
