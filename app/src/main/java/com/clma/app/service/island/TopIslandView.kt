package com.clma.app.service.island

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
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

/**
 * The top island overlay window: a dark capsule under the status bar that expands into a card.
 *
 * Plain Views (no Compose) because it lives in a bare `TYPE_APPLICATION_OVERLAY` window with no
 * lifecycle / saved-state owner. All methods must be called on the main thread.
 *
 * Focus: the window is `FLAG_NOT_FOCUSABLE` (never steals the keyboard or back key from the app
 * underneath) until the user taps the input field of the expanded card; it drops focusability
 * again when the card collapses or the island hides.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
internal class TopIslandView(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onStop()
        fun onOpen()
        fun onSend(text: String)
        fun onDoneTapped()
    }

    sealed interface Mode {
        /** A conversation is generating. */
        data class Running(
            val title: String,
            val status: TopIslandStatus,
            val startedAtMs: Long,
        ) : Mode

        /** A turn not tied to a visible conversation (automation, Telegram…): status only. */
        data class Generic(val startedAtMs: Long) : Mode

        data class Done(val elapsedMs: Long) : Mode
        data object Stopped : Mode
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()
    private fun dp(v: Int): Int = dp(v.toFloat())

    private var attached = false
    private var expanded = false
    private var windowFocusable = false
    private var mode: Mode? = null

    // ---- collapsed capsule ----
    private val pillIcon = appIconView(26)
    private val pillCheck = TextView(context).apply {
        text = "✓"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = oval(COLOR_GREEN)
        visibility = View.GONE
    }
    private val pillStatus = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        typeface = Typeface.DEFAULT_BOLD
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(170)
    }
    private val dots = List(3) {
        View(context).apply { background = oval(COLOR_ACCENT) }
    }
    private val dotsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        dots.forEachIndexed { i, dot ->
            addView(dot, LinearLayout.LayoutParams(dp(4), dp(4)).apply {
                if (i > 0) leftMargin = dp(3)
            })
        }
    }
    private val pillTimer = TextView(context).apply {
        setTextColor(COLOR_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        fontFeatureSettings = "tnum"
        setSingleLine(true)
    }
    private val pill = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(COLOR_PILL, dp(18).toFloat())
        setPadding(dp(4), 0, dp(14), 0)
        minimumHeight = dp(34)
        addView(pillIcon, LinearLayout.LayoutParams(dp(26), dp(26)))
        addView(pillCheck, LinearLayout.LayoutParams(dp(26), dp(26)))
        addView(pillStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(8) })
        addView(dotsRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(6) })
        addView(pillTimer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(8) })
        setOnClickListener { onPillClicked() }
    }

    // ---- expanded card ----
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
        contentDescription = context.getString(R.string.top_island_collapse)
        setOnClickListener { setExpanded(false) }
    }
    private val stepSpinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
        isIndeterminate = true
        indeterminateTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
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
        hint = context.getString(R.string.top_island_input_hint)
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
        contentDescription = context.getString(R.string.top_island_send)
        addView(ArrowView(context), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        setOnClickListener { submitInput() }
    }
    private val inputNote = TextView(context).apply {
        text = context.getString(R.string.top_island_input_note)
        setTextColor(COLOR_HINT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    }
    private val inputGroup = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(8) })
        })
        addView(inputNote, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6); leftMargin = dp(6) })
    }
    private val stopButton = pillButton(R.string.top_island_stop, COLOR_RED) { callbacks.onStop() }
    private val openButton = pillButton(R.string.top_island_open, 0x1FFFFFFF) { callbacks.onOpen() }

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(COLOR_CARD, dp(28).toFloat())
        setPadding(dp(18), dp(16), dp(18), dp(14))
        // Header
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(appIconView(42), LinearLayout.LayoutParams(dp(42), dp(42)))
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
        addView(inputGroup, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(stopButton, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(openButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(10) })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) })
        // Swallow taps on the card body so they don't fall through to the outside handler.
        isClickable = true
    }

    private val root = object : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && expanded) {
                setExpanded(false)
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            // FLAG_WATCH_OUTSIDE_TOUCH: a tap anywhere else on screen collapses the card.
            // Handled before the children see it, since an outside event carries no position.
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                if (expanded) setExpanded(false)
                return true
            }
            return super.dispatchTouchEvent(event)
        }
    }.apply {
        addView(pill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL,
        ))
        addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        card.visibility = View.GONE
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
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = topOffset()
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // y is measured from the very top of the screen, not below the status bar inset.
            setFitInsetsTypes(0)
        }
        title = "TopIsland"
    }

    private var dotPhase = 0

    val isShowing: Boolean get() = attached

    /** Shows the window if needed and renders [newMode]. */
    fun render(newMode: Mode) {
        mode = newMode
        if (!attached) attach()
        if (newMode !is Mode.Running && expanded) setExpanded(false)
        renderPill(newMode)
        if (newMode is Mode.Running) renderCard(newMode)
        tick()
    }

    /** Called about every 400 ms by the controller: timer text and the dot animation. */
    fun tick() {
        val m = mode ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        when (m) {
            is Mode.Running -> {
                val text = TopIslandStatusResolver.formatElapsed(now - m.startedAtMs)
                pillTimer.text = text
                elapsed.text = context.getString(R.string.top_island_elapsed, text)
            }
            is Mode.Generic -> pillTimer.text = TopIslandStatusResolver.formatElapsed(now - m.startedAtMs)
            else -> Unit
        }
        dotPhase = (dotPhase + 1) % 3
        dots.forEachIndexed { i, dot ->
            dot.alpha = if (i == dotPhase) 1f else 0.35f
        }
    }

    fun hide() {
        if (!attached) return
        if (expanded) setExpanded(false)
        attached = false
        mode = null
        try {
            wm.removeViewImmediate(root)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView failed", t)
        }
    }

    private fun attach() {
        params.y = topOffset()
        try {
            wm.addView(root, params)
            attached = true
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed", t)
        }
    }

    private fun renderPill(m: Mode) {
        val running = m is Mode.Running || m is Mode.Generic
        pillIcon.visibility = if (m is Mode.Done) View.GONE else View.VISIBLE
        pillCheck.visibility = if (m is Mode.Done) View.VISIBLE else View.GONE
        dotsRow.visibility = if (running) View.VISIBLE else View.GONE
        pillTimer.visibility = if (m is Mode.Stopped) View.GONE else View.VISIBLE
        pillStatus.text = when (m) {
            is Mode.Running -> pillStatusText(m.status.phase)
            is Mode.Generic -> context.getString(R.string.top_island_status_running)
            is Mode.Done -> context.getString(R.string.top_island_status_done)
            Mode.Stopped -> context.getString(R.string.top_island_status_stopped)
        }
        if (m is Mode.Done) {
            pillTimer.text = context.getString(
                R.string.top_island_done_elapsed,
                TopIslandStatusResolver.formatElapsed(m.elapsedMs),
            )
        }
    }

    private fun renderCard(m: Mode.Running) {
        cardTitle.text = if (m.title.isBlank()) {
            context.getString(R.string.top_island_title_untitled)
        } else {
            context.getString(R.string.top_island_title, m.title)
        }
        cardSubtitle.text = if (m.status.step > 1) {
            context.getString(R.string.top_island_subtitle_step, m.status.step)
        } else {
            context.getString(R.string.top_island_subtitle)
        }
        stepText.text = when (val phase = m.status.phase) {
            TopIslandPhase.Thinking -> context.getString(R.string.top_island_step_thinking)
            TopIslandPhase.Writing -> context.getString(R.string.top_island_step_writing)
            is TopIslandPhase.Tool -> context.getString(R.string.top_island_step_tool, toolName(phase.rawName))
        }
        preview.text = m.status.preview
        preview.visibility = if (m.status.preview.isBlank()) View.GONE else View.VISIBLE
    }

    private fun pillStatusText(phase: TopIslandPhase): String = when (phase) {
        TopIslandPhase.Thinking -> context.getString(R.string.top_island_status_thinking)
        TopIslandPhase.Writing -> context.getString(R.string.top_island_status_writing)
        is TopIslandPhase.Tool -> context.getString(R.string.top_island_status_tool, toolName(phase.rawName))
    }

    private fun toolName(raw: String): String =
        TopIslandToolNames.displayName(context, raw)

    private fun onPillClicked() {
        when (mode) {
            is Mode.Running -> setExpanded(true)
            is Mode.Done -> callbacks.onDoneTapped()
            else -> Unit
        }
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value || !attached) return
        expanded = value
        pill.visibility = if (value) View.GONE else View.VISIBLE
        card.visibility = if (value) View.VISIBLE else View.GONE
        if (value) {
            params.width = context.resources.displayMetrics.widthPixels - dp(20)
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
            imm().hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }
        if (!value && windowFocusable) {
            setWindowFocusable(false)
        } else {
            updateLayout()
        }
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

    /** Clears the field after a successful send and drops the keyboard. */
    fun clearInput() {
        input.setText("")
        imm().hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        if (windowFocusable) setWindowFocusable(false)
    }

    private fun imm(): InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun topOffset(): Int {
        @SuppressLint("DiscouragedApi", "InternalInsetResource")
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (id > 0) context.resources.getDimensionPixelSize(id) else dp(28)
        return statusBar + dp(4)
    }

    private fun appIconView(sizeDp: Int): ImageView = ImageView(context).apply {
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
        minimumWidth = dp(sizeDp)
        minimumHeight = dp(sizeDp)
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

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Up-pointing chevron (collapse). */
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
        const val TAG = "TopIslandView"
        val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val COLOR_PILL = 0xFF0B0B0D.toInt()
        val COLOR_CARD = 0xF5121216.toInt()
        val COLOR_ACCENT = 0xFF8FB4FF.toInt()
        val COLOR_ACCENT_START = 0xFF6F8CFF.toInt()
        val COLOR_ACCENT_END = 0xFF9B7BFF.toInt()
        val COLOR_GREEN = 0xFF30D158.toInt()
        val COLOR_RED = 0xFFFF3B30.toInt()
        val COLOR_MUTED = 0xFF9AA0AD.toInt()
        val COLOR_HINT = 0xFF7D828E.toInt()
        val COLOR_STEP = 0xFFCFD6FF.toInt()
        val COLOR_PREVIEW = 0xFFB8BCC6.toInt()
    }
}
