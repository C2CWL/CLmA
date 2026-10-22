package com.clma.app.service.taskcard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.clma.app.R
import com.clma.app.service.EdgeBarLook

/**
 * The task card the edge bar opens while a reply is generating: title, current step, streamed
 * preview, elapsed time, a "补充一句" input (→ ChatService.interjectMessage), 停止 and 打开.
 *
 * Its own small TYPE_APPLICATION_OVERLAY window, placed next to the bar: below it when the bar
 * is on the top edge, beside it when the bar is on the left / right edge, with a small pointer
 * aimed at the bar.
 *
 * Plain Views (no Compose): a bare overlay window has no lifecycle / saved-state owner. All
 * methods must be called on the main thread.
 *
 * Focus: the window is FLAG_NOT_FOCUSABLE (never takes the keyboard or back key from the app
 * underneath) until the user touches the input field; it drops focusability again after a send
 * or when the card closes. Back, the collapse arrow or a tap outside the card close it.
 */
@SuppressLint("ViewConstructor", "SetTextI18n", "RtlHardcoded", "ClickableViewAccessibility")
internal class EdgeBarTaskCardView(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onStop()
        fun onOpen()
        fun onSend(text: String)

        /** The card closed itself (arrow, back key, tap outside). */
        fun onDismissed()
    }

    data class Content(
        val title: String,
        val status: TaskCardStatus,
        val startedAtMs: Long,
    )

    /** Where the bar is, in screen pixels (its window bounds), and the screen it is on. */
    data class Anchor(
        val edge: EdgeBarLook.Edge,
        val barLeft: Int,
        val barTop: Int,
        val barWidth: Int,
        val barHeight: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val topInset: Int,
        val bottomInset: Int,
    )

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()
    private fun dp(v: Int): Int = dp(v.toFloat())

    private var attached = false
    private var windowFocusable = false
    private var content: Content? = null
    private var anchor: Anchor? = null

    /** The card moved to the top of the screen so the keyboard does not cover it. */
    private var liftedForKeyboard = false

    private val cardBackground = PointerCardBackground(COLOR_CARD, dp(24).toFloat(), dp(9).toFloat())

    private val cardTitle = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        typeface = Typeface.DEFAULT_BOLD
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val cardSubtitle = TextView(context).apply {
        setTextColor(COLOR_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val collapseButton = ChevronView(context).apply {
        contentDescription = context.getString(R.string.task_card_collapse)
        setOnClickListener { dismiss() }
    }
    private val stepSpinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(COLOR_ACCENT)
    }
    private val stepText = TextView(context).apply {
        setTextColor(COLOR_STEP)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val preview = TextView(context).apply {
        setTextColor(COLOR_PREVIEW)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setLineSpacing(dp(2).toFloat(), 1f)
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        background = rounded(0x10FFFFFF, dp(14).toFloat())
        setPadding(dp(12), dp(9), dp(12), dp(9))
    }
    private val elapsed = TextView(context).apply {
        setTextColor(COLOR_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        fontFeatureSettings = "tnum"
    }
    private val input = EditText(context).apply {
        hint = context.getString(R.string.task_card_input_hint)
        setHintTextColor(COLOR_HINT)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        background = rounded(0x14FFFFFF, dp(20).toFloat()).apply {
            setStroke(dp(1), 0x1AFFFFFF)
        }
        setPadding(dp(15), dp(9), dp(15), dp(9))
        minHeight = dp(40)
        maxLines = 3
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_SEND
        setHorizontallyScrolling(false)
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitInput()
                true
            } else {
                false
            }
        }
        setOnTouchListener { _, event ->
            // The window is not focusable while idle; make it focusable on the first touch so
            // the tap can focus the field and the keyboard can come up.
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !windowFocusable) {
                setWindowFocusable(true)
                liftForKeyboard(true)
                post {
                    requestFocus()
                    imm().showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            false
        }
    }
    private val sendButton = FrameLayout(context).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(COLOR_ACCENT_START, COLOR_ACCENT_END),
        ).apply { shape = GradientDrawable.OVAL }
        contentDescription = context.getString(R.string.task_card_send)
        addView(ArrowView(context), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        setOnClickListener { submitInput() }
    }
    private val inputNote = TextView(context).apply {
        text = context.getString(R.string.task_card_input_note)
        setTextColor(COLOR_HINT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    }
    private val stopButton = pillButton(R.string.task_card_stop, COLOR_RED) { callbacks.onStop() }
    private val openButton = pillButton(R.string.task_card_open, 0x1FFFFFFF) { callbacks.onOpen() }

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBackground
        // Header
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(appIconView(), LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(cardTitle)
                addView(cardSubtitle, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
            })
            addView(collapseButton, LinearLayout.LayoutParams(dp(36), dp(36)))
        })
        // Current step
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stepSpinner, LinearLayout.LayoutParams(dp(14), dp(14)))
            addView(stepText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) })
        addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        addView(elapsed, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        // Input + send, with the hint under it
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
        addView(inputNote, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6); leftMargin = dp(6) })
        // 停止 / 打开
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(stopButton, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(openButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(10) })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) })
        // Swallow taps on the card body so they don't reach anything else.
        isClickable = true
    }

    private val root = object : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) dismiss()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            // FLAG_WATCH_OUTSIDE_TOUCH: a tap anywhere else on screen closes the card. Handled
            // before the children see it, since an outside event carries no position.
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismiss()
                return true
            }
            return super.dispatchTouchEvent(event)
        }
    }.apply {
        addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }

    private val params = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // x / y are measured from the screen's top-left corner, like the bar's own window.
            setFitInsetsTypes(0)
        }
        title = "EdgeBarTaskCard"
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!attached) return
            renderElapsed()
            root.postDelayed(this, TICK_MS)
        }
    }

    val isShowing: Boolean get() = attached

    /** Opens the card next to the bar at [anchor] (or re-renders it if it is already open). */
    fun show(newContent: Content, newAnchor: Anchor) {
        content = newContent
        anchor = newAnchor
        render(newContent)
        if (!attached) {
            liftedForKeyboard = false
            place()
            try {
                wm.addView(root, params)
                attached = true
            } catch (t: Throwable) {
                Log.w(TAG, "addView failed", t)
                return
            }
            root.removeCallbacks(ticker)
            root.postDelayed(ticker, TICK_MS)
        } else {
            place()
            updateLayout()
        }
    }

    /** New status for the open card (no-op while closed). */
    fun update(newContent: Content) {
        if (!attached) return
        content = newContent
        render(newContent)
        // The preview may have grown a line: keep the card centred on a side bar.
        place()
        updateLayout()
    }

    /** Closes the card without telling [Callbacks.onDismissed]. */
    fun hide() {
        if (!attached) return
        root.removeCallbacks(ticker)
        imm().hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        windowFocusable = false
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        attached = false
        liftedForKeyboard = false
        try {
            wm.removeViewImmediate(root)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView failed", t)
        }
    }

    /** Clears the field after a successful send and drops the keyboard. */
    fun clearInput() {
        input.setText("")
        imm().hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        if (windowFocusable) setWindowFocusable(false)
        liftForKeyboard(false)
    }

    private fun dismiss() {
        if (!attached) return
        hide()
        callbacks.onDismissed()
    }

    private fun render(c: Content) {
        cardTitle.text = if (c.title.isBlank()) {
            context.getString(R.string.task_card_title_untitled)
        } else {
            context.getString(R.string.task_card_title, c.title)
        }
        cardSubtitle.text = if (c.status.step > 1) {
            context.getString(R.string.task_card_subtitle_step, c.status.step)
        } else {
            context.getString(R.string.task_card_subtitle)
        }
        stepText.text = when (val phase = c.status.phase) {
            TaskCardPhase.Thinking -> context.getString(R.string.task_card_step_thinking)
            TaskCardPhase.Writing -> context.getString(R.string.task_card_step_writing)
            is TaskCardPhase.Tool -> context.getString(
                R.string.task_card_step_tool,
                TaskCardToolNames.displayName(context, phase.rawName),
            )
        }
        preview.text = c.status.preview
        preview.visibility = if (c.status.preview.isBlank()) View.GONE else View.VISIBLE
        renderElapsed()
    }

    private fun renderElapsed() {
        val c = content ?: return
        val text = TaskCardStatusResolver.formatElapsed(SystemClock.elapsedRealtime() - c.startedAtMs)
        elapsed.text = context.getString(R.string.task_card_elapsed, text)
    }

    /**
     * Works out the window bounds and the pointer from [anchor]:
     *  - top bar: full-ish width, just below the bar, pointer up at the bar's centre;
     *  - side bar: beside the bar, vertically centred on it (kept on screen), pointer sideways.
     * While the keyboard is up (input focused) a side card moves to the top of the screen and
     * drops its pointer, so the keyboard does not cover it.
     */
    private fun place() {
        val a = anchor ?: return
        val margin = dp(MARGIN_DP)
        val gap = dp(GAP_DP)
        val pointer = cardBackground.pointerSize.toInt()
        val pad = dp(PADDING_DP)
        val pillThin = dp(EdgeBarLook.BAR_WIDTH_DP)
        val barCenterX = a.barLeft + a.barWidth / 2
        val barCenterY = a.barTop + a.barHeight / 2

        val side: PointerCardBackground.Side
        val width: Int
        val x: Int
        when (a.edge) {
            EdgeBarLook.Edge.TOP -> {
                side = PointerCardBackground.Side.TOP
                width = minOf(a.screenWidth - 2 * margin, dp(MAX_WIDTH_DP))
                x = (barCenterX - width / 2).coerceIn(margin, (a.screenWidth - margin - width).coerceAtLeast(margin))
            }
            EdgeBarLook.Edge.RIGHT -> {
                side = PointerCardBackground.Side.RIGHT
                val right = a.screenWidth - pillThin - gap
                width = minOf(right - margin, dp(MAX_WIDTH_DP))
                x = right - width
            }
            EdgeBarLook.Edge.LEFT -> {
                side = PointerCardBackground.Side.LEFT
                width = minOf(a.screenWidth - pillThin - gap - margin, dp(MAX_WIDTH_DP))
                x = pillThin + gap
            }
        }
        val effectiveSide = if (liftedForKeyboard && a.edge != EdgeBarLook.Edge.TOP) {
            PointerCardBackground.Side.NONE
        } else {
            side
        }
        card.setPadding(
            pad + if (effectiveSide == PointerCardBackground.Side.LEFT) pointer else 0,
            pad + if (effectiveSide == PointerCardBackground.Side.TOP) pointer else 0,
            pad + if (effectiveSide == PointerCardBackground.Side.RIGHT) pointer else 0,
            pad - dp(2),
        )
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = root.measuredHeight
        val minY = a.topInset + margin
        val maxY = (a.screenHeight - a.bottomInset - margin - height).coerceAtLeast(minY)
        val y = when {
            a.edge == EdgeBarLook.Edge.TOP -> a.barTop + pillThin + gap
            liftedForKeyboard -> minY
            else -> (barCenterY - height / 2).coerceIn(minY, maxY)
        }
        cardBackground.side = effectiveSide
        cardBackground.pointerAt = when (effectiveSide) {
            PointerCardBackground.Side.TOP -> (barCenterX - x).toFloat()
            PointerCardBackground.Side.LEFT, PointerCardBackground.Side.RIGHT -> (barCenterY - y).toFloat()
            PointerCardBackground.Side.NONE -> 0f
        }
        collapseButton.rotation = when (a.edge) {
            EdgeBarLook.Edge.TOP -> 0f
            EdgeBarLook.Edge.RIGHT -> 90f
            EdgeBarLook.Edge.LEFT -> -90f
        }
        params.width = width
        params.x = x
        params.y = y
    }

    private fun liftForKeyboard(lift: Boolean) {
        val a = anchor ?: return
        if (a.edge == EdgeBarLook.Edge.TOP || liftedForKeyboard == lift) return
        liftedForKeyboard = lift
        place()
        updateLayout()
    }

    private fun setWindowFocusable(value: Boolean) {
        windowFocusable = value
        params.flags = if (value) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        updateLayout()
    }

    private fun updateLayout() {
        if (!attached) return
        try {
            wm.updateViewLayout(root, params)
        } catch (t: Throwable) {
            Log.w(TAG, "updateViewLayout failed", t)
        }
    }

    private fun submitInput() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        callbacks.onSend(text)
    }

    private fun imm(): InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun appIconView(): ImageView = ImageView(context).apply {
        val icon = runCatching { context.packageManager.getApplicationIcon(context.packageName) }
            .getOrNull()
        if (icon != null) setImageDrawable(icon) else setImageDrawable(ColorDrawable(COLOR_ACCENT))
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
    }

    private fun pillButton(textRes: Int, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = context.getString(textRes)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(color, dp(21).toFloat())
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    /**
     * Rounded dark card with a small triangular pointer on one side, pointing at the bar.
     * [pointerAt] is the pointer's centre along that side, in pixels from the card's left (top
     * side) or top (left / right side); it is clamped so it never runs into a corner.
     */
    private class PointerCardBackground(
        color: Int,
        private val radius: Float,
        val pointerSize: Float,
    ) : Drawable() {
        enum class Side { NONE, TOP, LEFT, RIGHT }

        var side: Side = Side.NONE
            set(value) {
                field = value
                invalidateSelf()
            }
        var pointerAt: Float = 0f
            set(value) {
                field = value
                invalidateSelf()
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        private val body = RectF()
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            body.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            when (side) {
                Side.TOP -> body.top += pointerSize
                Side.LEFT -> body.left += pointerSize
                Side.RIGHT -> body.right -= pointerSize
                Side.NONE -> Unit
            }
            canvas.drawRoundRect(body, radius, radius, paint)
            if (side == Side.NONE) return
            val half = pointerSize
            path.reset()
            when (side) {
                Side.TOP -> {
                    val c = pointerAt.coerceIn(body.left + radius + half, body.right - radius - half)
                    path.moveTo(c - half, body.top + 1f)
                    path.lineTo(c, b.top.toFloat())
                    path.lineTo(c + half, body.top + 1f)
                }
                Side.LEFT -> {
                    val c = pointerAt.coerceIn(body.top + radius + half, body.bottom - radius - half)
                    path.moveTo(body.left + 1f, c - half)
                    path.lineTo(b.left.toFloat(), c)
                    path.lineTo(body.left + 1f, c + half)
                }
                Side.RIGHT -> {
                    val c = pointerAt.coerceIn(body.top + radius + half, body.bottom - radius - half)
                    path.moveTo(body.right - 1f, c - half)
                    path.lineTo(b.right.toFloat(), c)
                    path.lineTo(body.right - 1f, c + half)
                }
                Side.NONE -> Unit
            }
            path.close()
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** Up-pointing chevron (collapse); rotated to point at a side bar. */
    private class ChevronView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.2f * context.resources.displayMetrics.density
        }
        private val path = Path()

        init {
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val s = minOf(w, h) * 0.22f
            path.reset()
            path.moveTo(w / 2 - s, h / 2 + s / 2)
            path.lineTo(w / 2, h / 2 - s / 2)
            path.lineTo(w / 2 + s, h / 2 + s / 2)
            canvas.drawPath(path, paint)
        }
    }

    /** Up arrow for the send button. */
    private class ArrowView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.4f * context.resources.displayMetrics.density
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            path.reset()
            path.moveTo(w / 2, h * 0.85f)
            path.lineTo(w / 2, h * 0.15f)
            path.moveTo(w * 0.22f, h * 0.42f)
            path.lineTo(w / 2, h * 0.15f)
            path.lineTo(w * 0.78f, h * 0.42f)
            canvas.drawPath(path, paint)
        }
    }

    private companion object {
        const val TAG = "EdgeBarTaskCard"
        const val TICK_MS = 500L
        const val MARGIN_DP = 10f
        const val GAP_DP = 4f
        const val PADDING_DP = 16f
        const val MAX_WIDTH_DP = 400f

        val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val COLOR_CARD = 0xF5121216.toInt()
        val COLOR_ACCENT = 0xFF8FB4FF.toInt()
        val COLOR_ACCENT_START = 0xFF6F8CFF.toInt()
        val COLOR_ACCENT_END = 0xFF9B7BFF.toInt()
        val COLOR_RED = 0xFFFF3B30.toInt()
        val COLOR_MUTED = 0xFF9AA0AD.toInt()
        val COLOR_HINT = 0xFF7D828E.toInt()
        val COLOR_STEP = 0xFFCFD6FF.toInt()
        val COLOR_PREVIEW = 0xFFB8BCC6.toInt()
    }
}
