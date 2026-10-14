package me.rerere.rikkahub.ui.components.message

/**
 * Long user messages collapse to a fixed preview height, with a round chevron
 * under the text (same shape as the Qwen / DeepSeek bubble). The composable
 * measures the real height; this object only decides the layout so the rule
 * can be tested without Compose.
 *
 * [likelyOverflows] is a first-frame guess so a pasted prompt does not appear
 * without a toggle for one frame. A wrong guess is corrected by [layout].
 */
internal object UserMessageCollapse {
    /** Preview cap. About eight lines of body text at the default font scale. */
    const val LIMIT_DP = 180

    private const val LIKELY_CHARS = 240

    /** 8 newlines is 9 lines, which already exceeds the preview at default scale. */
    private const val LIKELY_NEWLINES = 8

    fun likelyOverflows(text: String): Boolean {
        if (text.length >= LIKELY_CHARS) return true
        var newlines = 0
        for (ch in text) {
            if (ch == '\n') {
                newlines++
                if (newlines >= LIKELY_NEWLINES) return true
            }
        }
        return false
    }

    data class Layout(val heightPx: Int, val showToggle: Boolean)

    fun layout(fullHeightPx: Int, limitPx: Int, expanded: Boolean): Layout {
        if (limitPx <= 0 || fullHeightPx <= limitPx) {
            return Layout(heightPx = fullHeightPx.coerceAtLeast(0), showToggle = false)
        }
        return Layout(
            heightPx = if (expanded) fullHeightPx else limitPx,
            showToggle = true,
        )
    }
}
