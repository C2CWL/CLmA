package me.rerere.rikkahub.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The optional "status capsule" face of the floating ball (FloatingBallStyle.CAPSULE).
 *
 * A rounded bar in the app's own surface colours: the existing [HaloBallView] as its icon (so
 * the idle halo, a picked image or the assistant avatar, and the orbiting-dot "working"
 * animation are exactly the ones the round ball uses), the assistant's name, a status line with
 * the turn timer, and a small close glyph that tucks the capsule away.
 *
 * Tucked away it collapses to a slim vertical handle on the screen edge instead of a
 * half-hidden ball: a capsule cut in half would show a meaningless sliver of text.
 *
 * The root keeps a transparent margin around the bar so its elevation shadow is not clipped by
 * the overlay window's bounds.
 */
@SuppressLint("ViewConstructor")
internal class CapsuleBallView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    /** Shadow room around the bar, so the window does not clip it. */
    val shadowInset: Int = dp(SHADOW_INSET_DP)

    val halo = HaloBallView(context)

    private val title = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(TITLE_MAX_WIDTH_DP)
        includeFontPadding = false
    }

    private val status = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(TITLE_MAX_WIDTH_DP)
        includeFontPadding = false
    }

    /** Tucks the capsule away. Clickable, so its touches never reach the drag / tap handler. */
    val closeButton = CloseGlyphView(context).apply {
        isClickable = true
        isFocusable = false
    }

    private val barBackground = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(BAR_HEIGHT_DP).toFloat() / 2f
    }

    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = barBackground
        elevation = dp(BAR_ELEVATION_DP).toFloat()
        minimumHeight = dp(BAR_HEIGHT_DP)
        setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        addView(halo, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(status, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(3f) })
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(10f)
                marginEnd = dp(8f)
            },
        )
        addView(closeButton, LinearLayout.LayoutParams(dp(CLOSE_DP), dp(CLOSE_DP)))
    }

    private val handleBackground = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(HANDLE_WIDTH_DP).toFloat() / 2f
    }

    private val handle = View(context).apply {
        background = handleBackground
        visibility = GONE
    }

    init {
        clipToPadding = false
        clipChildren = false
        setPadding(shadowInset, shadowInset, shadowInset, shadowInset)
        addView(bar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(handle, LayoutParams(dp(HANDLE_WIDTH_DP), dp(HANDLE_HEIGHT_DP)))
    }

    /** Colours resolved by the service from the app theme (never hard-coded here). */
    fun applyColors(container: Int, outline: Int, onSurface: Int, onSurfaceVariant: Int, accent: Int) {
        barBackground.setColor(container)
        barBackground.setStroke(dp(1f).coerceAtLeast(1), outline)
        title.setTextColor(onSurface)
        status.setTextColor(onSurfaceVariant)
        closeButton.glyphColor = onSurfaceVariant
        handleBackground.setColor(accent)
        halo.accentColor = accent
    }

    fun setTexts(titleText: String, statusText: String) {
        if (title.text != titleText) title.text = titleText
        if (status.text != statusText) status.text = statusText
    }

    /** Slim edge handle (tucked) vs the full capsule (awake). */
    var handleMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            bar.visibility = if (value) GONE else VISIBLE
            handle.visibility = if (value) VISIBLE else GONE
            // The orbit animation has nothing to draw on while only the handle shows.
            requestLayout()
        }

    /** Measured size of the awake capsule, for edge maths before the next layout pass. */
    fun measuredCapsuleWidth(): Int {
        val spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        measure(spec, spec)
        return measuredWidth
    }

    /** A small "×" drawn from two strokes: no icon asset needed in the overlay. */
    internal class CloseGlyphView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 1.6f * context.resources.displayMetrics.density
        }
        var glyphColor: Int = 0xFF777777.toInt()
            set(value) {
                field = value
                paint.color = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val s = minOf(width, height).toFloat()
            val arm = s * 0.22f
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, paint)
            canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, paint)
        }
    }

    companion object {
        const val SHADOW_INSET_DP = 8f
        const val BAR_HEIGHT_DP = 48f
        const val BAR_ELEVATION_DP = 4f
        const val ICON_DP = 36f
        const val CLOSE_DP = 28f
        const val TITLE_MAX_WIDTH_DP = 150f
        const val HANDLE_WIDTH_DP = 6f
        const val HANDLE_HEIGHT_DP = 44f
    }
}
