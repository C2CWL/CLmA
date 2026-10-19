package me.rerere.rikkahub.ui.components.message

/** 设置里“折叠时显示的步骤数”的上限，滑块数字档是 0..这个值，再往上一档是“全部”。 */
internal const val THINKING_STEPS_VISIBLE_MAX = 10

/** 存储用的哨兵值：不折叠，所有步骤都显示。 */
internal const val THINKING_STEPS_VISIBLE_ALL = -1

/**
 * 一组思考/工具步骤折叠时，尾部保留几步可见。
 *
 * [setting] 来自 DisplaySetting.thinkingStepsVisibleCount，默认 2，和原来的行为一致。
 * 0 表示整组只剩一行“已执行 N 步”的标题。正在生成的最后一组至少留一步，
 * 不然模型在跑工具时界面上看不到任何进展；生成结束后再按设置收起。
 * [THINKING_STEPS_VISIBLE_ALL] 返回 Int.MAX_VALUE：步骤数永远不超过它，ChainOfThought 就不折叠。
 */
internal fun thinkingStepsCollapsedVisibleCount(setting: Int, isStreamingTail: Boolean): Int {
    if (setting == THINKING_STEPS_VISIBLE_ALL) return Int.MAX_VALUE
    val count = setting.coerceIn(0, THINKING_STEPS_VISIBLE_MAX)
    return if (isStreamingTail) count.coerceAtLeast(1) else count
}

/** 滑块位置（0..MAX+1，最上面一档是“全部”）→ 存储值。 */
internal fun thinkingStepsVisibleFromSlider(position: Int): Int =
    if (position > THINKING_STEPS_VISIBLE_MAX) THINKING_STEPS_VISIBLE_ALL
    else position.coerceIn(0, THINKING_STEPS_VISIBLE_MAX)

/** 存储值 → 滑块位置。 */
internal fun thinkingStepsVisibleToSlider(setting: Int): Int =
    if (setting == THINKING_STEPS_VISIBLE_ALL) THINKING_STEPS_VISIBLE_MAX + 1
    else setting.coerceIn(0, THINKING_STEPS_VISIBLE_MAX)
