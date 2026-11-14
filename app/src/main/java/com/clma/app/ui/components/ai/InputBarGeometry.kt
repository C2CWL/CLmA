package com.clma.app.ui.components.ai

import kotlin.math.roundToInt

/** Measured endpoints, not dimensions re-derived from the other arrangement's buttons. */
internal data class InputBarEndpoint(
    val width: Int,
    val height: Int,
    val centerX: Int,
    val centerY: Int,
    val centerWidth: Int,
    val centerHeight: Int,
    val leftWidth: Int,
    val leftY: Int,
    val rightWidth: Int,
    val rightY: Int,
)

internal fun inputBarActionBudget(expanded: Boolean, width: Int, gap: Int, minimumField: Int): Int =
    (width - (if (expanded) 0 else minimumField.coerceIn(0, width)) - gap * 2).coerceAtLeast(0)

internal fun inputBarFieldWidth(
    expanded: Boolean, width: Int, leftWidth: Int, rightWidth: Int, gap: Int,
): Int = if (expanded) width else (width - leftWidth - rightWidth - gap * 2).coerceAtLeast(0)

internal fun inputBarEndpoint(
    expanded: Boolean, width: Int, leftWidth: Int, rightWidth: Int,
    leftHeight: Int, rightHeight: Int, fieldHeight: Int, gap: Int,
): InputBarEndpoint {
    val actionHeight = maxOf(leftHeight, rightHeight)
    val height = if (expanded) fieldHeight + gap + actionHeight else maxOf(fieldHeight, actionHeight)
    return InputBarEndpoint(
        width = width, height = height,
        centerX = if (expanded) 0 else leftWidth + gap,
        centerY = if (expanded) 0 else (height - fieldHeight) / 2,
        centerWidth = inputBarFieldWidth(expanded, width, leftWidth, rightWidth, gap),
        centerHeight = fieldHeight, leftWidth = leftWidth,
        leftY = if (expanded) fieldHeight + gap + (actionHeight - leftHeight) / 2 else (height - leftHeight) / 2,
        rightWidth = rightWidth,
        rightY = if (expanded) fieldHeight + gap + (actionHeight - rightHeight) / 2 else (height - rightHeight) / 2,
    )
}

/** Plain measure-policy memory: no snapshot writes or layout -> composition feedback loop. */
internal class InputBarGeometry {
    private var collapsed: InputBarEndpoint? = null
    private var opened: InputBarEndpoint? = null
    private var width: Int? = null

    fun update(expanded: Boolean, measuredTarget: InputBarEndpoint) {
        if (width != measuredTarget.width) {
            collapsed = null
            opened = null
            width = measuredTarget.width
        }
        if (expanded) opened = measuredTarget else collapsed = measuredTarget
    }

    fun frame(progress: Float): InputBarEndpoint {
        val start = requireNotNull(collapsed ?: opened)
        val end = requireNotNull(opened ?: collapsed)
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        fun mix(a: Int, b: Int) = (a + (b - a) * p).roundToInt()
        return InputBarEndpoint(
            width = end.width, height = mix(start.height, end.height),
            centerX = mix(start.centerX, end.centerX), centerY = mix(start.centerY, end.centerY),
            centerWidth = mix(start.centerWidth, end.centerWidth),
            centerHeight = mix(start.centerHeight, end.centerHeight),
            leftWidth = mix(start.leftWidth, end.leftWidth), leftY = mix(start.leftY, end.leftY),
            rightWidth = mix(start.rightWidth, end.rightWidth), rightY = mix(start.rightY, end.rightY),
        )
    }
}
