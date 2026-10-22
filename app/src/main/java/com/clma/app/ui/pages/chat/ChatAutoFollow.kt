package me.rerere.rikkahub.ui.pages.chat

/**
 * 「流式输出时自动跟随到底部」的纯逻辑状态机。
 *
 * 为什么不能判「离底部近不近」：Compose 的拖动和惯性滑行是两个独立的滚动会话
 * （`onDragStopped` 里用 `coroutineScope.launch` 启动 fling），手指抬起与 fling 开始之间
 * 存在一帧 `isScrollInProgress == false` 的空档。快速滑动时拖动距离很短，那一帧列表仍在
 * 底部附近 ⇒「离底部近」判据照常通过，于是触发 `requestScrollToItem`；而
 * `requestScrollToItem` 内部会 `scroll {}`（与 fling 同优先级）取消正在进行的滚动 ⇒
 * **既把列表拽回底部、又当场掐掉整个惯性滑行**，用户看到的就是「快速滑动被拽回底部」。
 *
 * 所以判据必须是「我们本来就在跟随、且用户没有接管过滚动」：一旦观察到滚动
 * （拖动 / fling / 程序滚动）就暂停跟随，只有列表真正停在末尾才恢复。
 */
internal data class AutoFollowSample(
    /** 正在驱动滚动：拖动、惯性滑行、`scrollToItem`、音量键翻页、输入法顶起都算。 */
    val scrolling: Boolean,
    /** 列表下方是否还有内容（`LazyListState.canScrollForward`）。 */
    val canScrollForward: Boolean,
    /** 列表项数量：用来排除「还没测量过」时恒为 false 的假 `canScrollForward`。 */
    val itemCount: Int,
    /** 是否正在生成流式回复。 */
    val loading: Boolean,
)

internal enum class AutoFollowAction {
    /** 什么都不做。 */
    NONE,

    /** 暂停跟随：用户接管了滚动。 */
    PAUSE,

    /** 恢复跟随：列表真正回到了末尾。 */
    RESUME,

    /** 继续跟随：末尾来了新内容。 */
    FOLLOW,
}

internal fun autoFollowAction(sample: AutoFollowSample, following: Boolean): AutoFollowAction = when {
    // 1) 任何滚动一开始就暂停跟随。必须排在 at-bottom 之前：用户刚上手时列表还在底部。
    sample.scrolling -> AutoFollowAction.PAUSE
    // 2) 只有真正停在末尾（且已经测量过）才恢复跟随。
    !sample.canScrollForward && sample.itemCount > 0 -> AutoFollowAction.RESUME
    // 3) 本来就在跟随，底部又来了新内容（流式增量、新消息、错误卡片…）⇒ 继续保持跟随。
    following && sample.loading -> AutoFollowAction.FOLLOW
    else -> AutoFollowAction.NONE
}
