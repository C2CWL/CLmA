package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动跟随到底部的判定表。纯逻辑，CI 与本地工装都能跑。
 *
 * 重点是 `a quick flick is never dragged back to the bottom`：它锁住本次修复的核心 ——
 * 拖动结束、惯性滑行尚未开始的那一帧（列表仍离底部很近，但用户已经接管了滚动）必须什么都不做。
 */
class ChatAutoFollowTest {

    private fun sample(
        scrolling: Boolean = false,
        canScrollForward: Boolean = false,
        itemCount: Int = 10,
        loading: Boolean = true,
    ) = AutoFollowSample(
        scrolling = scrolling,
        canScrollForward = canScrollForward,
        itemCount = itemCount,
        loading = loading,
    )

    @Test
    fun `streaming at the bottom keeps following`() {
        // 末尾来了新内容：下方重新出现可滚动余量，而我们在跟随 ⇒ 继续跟随
        assertEquals(
            AutoFollowAction.FOLLOW,
            autoFollowAction(sample(canScrollForward = true), following = true),
        )
    }

    @Test
    fun `the first touch pauses the follow even though the list is still at the bottom`() {
        // 用户刚上手那一刻列表还在底部，但滚动已经开始 ⇒ 必须先暂停
        assertEquals(
            AutoFollowAction.PAUSE,
            autoFollowAction(sample(scrolling = true), following = true),
        )
    }

    @Test
    fun `a quick flick is never dragged back to the bottom`() {
        // 拖动结束、fling 尚未开始的那一帧：不再有滚动在跑，而视野只离开底部一点点
        //（快速滑动时拖动距离很短）。旧实现用「离底部近」当判据，这一帧会 FOLLOW ——
        // requestScrollToItem 于是把列表拽回底部、并取消同优先级的 fling。
        val gapFrame = sample(scrolling = false, canScrollForward = true)
        assertEquals(AutoFollowAction.NONE, autoFollowAction(gapFrame, following = false))
    }

    @Test
    fun `the frame between drag and fling is only unsafe if the drag was never observed`() {
        // 上一帧之所以安全，是因为拖动本身一定会被观察到（一次真实的手指滑动必然跨过至少一次
        // 快照 apply），PAUSE 已经先把 following 置为 false。若整段拖动都落在同一帧里 ——
        // 需要 <16ms 内完成「按下 → 越过 touch slop → 抬起」，真实手势不可能，A11y 注入的
        // swipe 也有 50ms 下限 —— 该样本与「底部来了新内容」在信息上无法区分，只能保守继续跟随。
        // 这是已知边界，不是回归。
        assertEquals(
            AutoFollowAction.FOLLOW,
            autoFollowAction(sample(scrolling = false, canScrollForward = true), following = true),
        )
    }

    @Test
    fun `scrolling away and stopping does not re-follow`() {
        // 暂停之后：fling 进行中 → PAUSE；fling 停在半途 → NONE
        assertEquals(
            AutoFollowAction.PAUSE,
            autoFollowAction(sample(scrolling = true, canScrollForward = true), following = false),
        )
        assertEquals(
            AutoFollowAction.NONE,
            autoFollowAction(sample(canScrollForward = true), following = false),
        )
    }

    @Test
    fun `returning to the bottom resumes the follow`() {
        assertEquals(
            AutoFollowAction.RESUME,
            autoFollowAction(sample(canScrollForward = false), following = false),
        )
        // 恢复后的下一帧增量照常跟随
        assertEquals(
            AutoFollowAction.FOLLOW,
            autoFollowAction(sample(canScrollForward = true), following = true),
        )
    }

    @Test
    fun `an unmeasured list is not a bottom`() {
        // 首次测量之前 canScrollForward 恒为 false，不能当成「已经到底了」
        assertEquals(
            AutoFollowAction.NONE,
            autoFollowAction(sample(canScrollForward = false, itemCount = 0), following = false),
        )
    }

    @Test
    fun `nothing happens once the reply is done`() {
        // 生成结束后，即使下方还有内容也不再有动作
        assertEquals(
            AutoFollowAction.NONE,
            autoFollowAction(sample(canScrollForward = true, loading = false), following = true),
        )
    }

    @Test
    fun `a settled conversation at the bottom resumes without scrolling`() {
        // 空闲且停在底部：只是恢复跟随状态，不需要滚动
        assertEquals(
            AutoFollowAction.RESUME,
            autoFollowAction(sample(canScrollForward = false, loading = false), following = false),
        )
    }
}
