package com.clma.app.service.chatbox

import com.clma.app.service.ChatService

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.clma.app.R
import com.clma.app.service.EdgeBarLook
import com.clma.app.service.taskcard.TaskCardPhase
import com.clma.app.service.taskcard.TaskCardStatus
import com.clma.app.service.taskcard.TaskCardStatusResolver
import com.clma.app.service.taskcard.TaskCardToolNames
import java.util.function.Consumer

/**
 * The floating chat box the edge bar opens: a small Fluid-Cloud-style card next to the bar with
 * the conversation's recent messages, a status row while the AI replies (with 停止), and an
 * input that sends (idle) or interjects (while replying).
 *
 * A [Dialog] whose window is TYPE_APPLICATION_OVERLAY, so it shows over any app and gets the
 * keyboard / back key while open; back, the collapse arrow or a tap outside close it. Being a
 * real [Window] lets it use [Window.setBackgroundBlurRadius] (API 31+) for the frosted glass;
 * when cross-window blur is unavailable or switched off at runtime, it uses the near-opaque
 * fallback colours of [ChatBoxPalette] and draws a small pointer toward the bar (with blur the
 * blurred region is the whole window, so there is no room for a pointer outside the card).
 *
 * Plain Views (no Compose): the window has no lifecycle / saved-state owner. Main thread only.
 */
@SuppressLint("ViewConstructor", "SetTextI18n", "RtlHardcoded")
internal class EdgeBarChatBoxView(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onNewConversation()
        fun onOpen()
        fun onStop()
        fun onSend(text: String)
        fun onToolApproval(
            toolCallId: String,
            approved: Boolean,
            reason: String,
            scope: ChatService.ApprovalScope,
            toolName: String,
        )
        fun onToolAnswer(toolCallId: String, answer: String)

        /** The box was closed by the user (arrow, back, tap outside) or by [dismiss]. */
        fun onDismissed()
    }

    sealed interface Mode {
        data class Idle(val assistantName: String, val updatedAtMs: Long) : Mode
        data class Working(val status: TaskCardStatus, val startedAtMs: Long) : Mode
        data class Done(val elapsedMs: Long) : Mode
    }

    data class State(
        val title: String,
        val mode: Mode,
        val items: List<ChatBoxItem>,
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

    private var dialog: Dialog? = null
    private var state: State? = null
    private var anchor: Anchor? = null
    private var dark = false
    private var glass = false
    private var imeBottom = 0
    private var blurListener: Consumer<Boolean>? = null

    // Views, (re)built by [buildContent] for the current palette.
    private var palette = ChatBoxPalette.of(dark = false, glass = false)
    private var chatTypeface: Typeface? = null
    private lateinit var root: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var newButton: View
    private lateinit var statusRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusElapsed: TextView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var input: EditText

    private var windowBackground: Drawable? = null

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (dialog == null) return
            renderTimes()
            handler.postDelayed(this, TICK_MS)
        }
    }

    val isShowing: Boolean get() = dialog != null

    /** Opens the box next to the bar at [newAnchor] (re-renders it if it is already open). */
    fun show(
        newState: State,
        newAnchor: Anchor,
        darkMode: Boolean,
        customChatTypeface: Typeface? = chatTypeface,
    ) {
        anchor = newAnchor
        chatTypeface = customChatTypeface
        if (dialog != null) {
            if (darkMode != dark) setDarkMode(darkMode)
            update(newState)
            place()
            return
        }
        dark = darkMode
        glass = blurAvailable()
        imeBottom = 0
        buildContent()
        val d = Dialog(context, R.style.Theme_EdgeBarChatBox)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(root)
        d.setCanceledOnTouchOutside(true)
        // Dialog posts this listener; ignore a late call for an older dialog.
        d.setOnDismissListener { if (dialog === d) onDismissInternal() }
        val w = d.window
        if (w == null) {
            Log.w(TAG, "dialog has no window")
            return
        }
        w.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        )
        w.setFormat(PixelFormat.TRANSLUCENT)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        w.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // We move the box above the keyboard ourselves (see the IME insets below).
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                } else {
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                }
        )
        dialog = d
        state = newState
        render(newState)
        applyBackground()
        place()
        try {
            d.show()
        } catch (t: Throwable) {
            Log.w(TAG, "show failed", t)
            dialog = null
            return
        }
        ViewCompat.setOnApplyWindowInsetsListener(w.decorView) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (ime != imeBottom) {
                imeBottom = ime
                place()
            }
            ViewCompat.onApplyWindowInsets(v, insets)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) registerBlurListener()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
        scrollToBottom()
    }

    /** New content for the open box (no-op while closed). */
    fun update(newState: State) {
        if (dialog == null) return
        val stick = isNearBottom()
        state = newState
        render(newState)
        if (stick) scrollToBottom()
    }

    /** Light / dark changed while open: rebuild with the new palette, keeping the typed text. */
    fun setDarkMode(darkMode: Boolean) {
        if (darkMode == dark) return
        dark = darkMode
        rebuild()
    }

    /** Updates the typeface used for user/AI chat bubbles (and input field). */
    fun setChatTypeface(typeface: Typeface?) {
        if (chatTypeface == typeface) return
        chatTypeface = typeface
        rebuild()
    }

    /** Closes the box ([Callbacks.onDismissed] is still called once). */
    fun dismiss() {
        val d = dialog ?: return
        runCatching { d.dismiss() }.onFailure { Log.w(TAG, "dismiss failed", it) }
        // onDismissListener does the cleanup; make sure it ran even if dismiss threw.
        if (dialog != null) onDismissInternal()
    }

    /** Clears the field after a successful send and drops the keyboard. */
    fun clearInput() {
        if (dialog == null) return
        input.setText("")
        imm().hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun onDismissInternal() {
        if (dialog == null) return
        handler.removeCallbacks(ticker)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) unregisterBlurListener()
        dialog = null
        state = null
        callbacks.onDismissed()
    }

    // ---- Content ----

    private fun rebuild() {
        val d = dialog ?: return
        val typed = input.text?.toString().orEmpty()
        palette = ChatBoxPalette.of(dark, glass)
        buildContent()
        d.setContentView(root)
        input.setText(typed)
        state?.let(::render)
        applyBackground()
        place()
        scrollToBottom()
    }

    private fun buildContent() {
        palette = ChatBoxPalette.of(dark, glass)
        val p = palette

        titleView = TextView(context).apply {
            setTextColor(p.title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleView = TextView(context).apply {
            setTextColor(p.subtitle)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        newButton = iconButton(IconView.Kind.PLUS, R.string.chat_box_new_conversation, filled = true) {
            callbacks.onNewConversation()
        }
        val openButton = iconButton(IconView.Kind.OPEN, R.string.chat_box_open, filled = true) {
            callbacks.onOpen()
        }
        val collapseButton = iconButton(collapseKind(), R.string.chat_box_collapse, filled = false) {
            dismiss()
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(appIconView(), LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(subtitleView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(1) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10)
                rightMargin = dp(6)
            })
            addView(newButton, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(6) })
            addView(openButton, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(2) })
            addView(collapseButton, LinearLayout.LayoutParams(dp(30), dp(30)))
        }

        statusText = TextView(context).apply {
            setTextColor(p.statusText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        statusElapsed = TextView(context).apply {
            setTextColor(p.subtitle)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            fontFeatureSettings = "tnum"
            setSingleLine(true)
        }
        val stopPill = TextView(context).apply {
            text = context.getString(R.string.chat_box_stop)
            setTextColor(p.stopText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            background = rounded(p.stopBg, dp(13).toFloat())
            setOnClickListener { callbacks.onStop() }
        }
        statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(p.statusBg, dp(12).toFloat())
            setPadding(dp(10), dp(5), dp(6), dp(5))
            addView(ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(p.accent)
            }, LinearLayout.LayoutParams(dp(13), dp(13)))
            addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(7)
            })
            addView(statusElapsed, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(6); rightMargin = dp(6) })
            addView(stopPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)))
        }

        list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Few messages sit at the bottom, next to the input, like a chat.
            gravity = Gravity.BOTTOM
            setPadding(0, dp(24), 0, dp(2))
        }
        scroll = object : ScrollView(context) {
            // Fade only at the top (older messages); the newest message sits flush at the bottom.
            override fun getBottomFadingEdgeStrength(): Float = 0f
        }.apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(28))
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(list, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        input = EditText(context).apply {
            setTextColor(p.inputText)
            setHintTextColor(p.hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = chatTypeface ?: Typeface.DEFAULT
            background = rounded(p.inputBg, dp(19).toFloat()).apply { setStroke(dp(1), p.inputBorder) }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            minHeight = dp(38)
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
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
        }
        val sendButton = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(ChatBoxPalette.SEND_START, ChatBoxPalette.SEND_END),
            ).apply { shape = GradientDrawable.OVAL }
            contentDescription = context.getString(R.string.chat_box_send)
            addView(IconView(context, IconView.Kind.SEND, 0xFFFFFFFF.toInt()), FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER))
            setOnClickListener { submitInput() }
        }
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton, LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(8) })
        }

        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(statusRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) })
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(4)
            })
            addView(inputRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) })
        }
    }

    private fun render(s: State) {
        titleView.text = s.title.ifBlank { context.getString(R.string.chat_box_untitled) }
        val working = s.mode is Mode.Working
        statusRow.visibility = if (working) View.VISIBLE else View.GONE
        newButton.visibility = if (working) View.GONE else View.VISIBLE
        input.hint = context.getString(if (working) R.string.chat_box_interject_hint else R.string.chat_box_input_hint)
        when (val m = s.mode) {
            is Mode.Working -> {
                subtitleView.setTextColor(palette.subtitle)
                statusText.text = when (val phase = m.status.phase) {
                    TaskCardPhase.Thinking -> context.getString(R.string.chat_box_status_thinking)
                    TaskCardPhase.Writing -> context.getString(R.string.chat_box_status_writing)
                    is TaskCardPhase.Tool -> context.getString(R.string.chat_box_status_tool, toolName(phase.rawName))
                }
            }
            is Mode.Done -> {
                subtitleView.setTextColor(palette.doneText)
                subtitleView.text = context.getString(
                    R.string.chat_box_subtitle_done,
                    TaskCardStatusResolver.formatElapsed(m.elapsedMs),
                )
            }
            is Mode.Idle -> subtitleView.setTextColor(palette.subtitle)
        }
        renderTimes()
        renderItems(s.items)
    }

    /** The parts that change with the clock: elapsed time, relative "updated" time, step. */
    private fun renderTimes() {
        val s = state ?: return
        when (val m = s.mode) {
            is Mode.Working -> {
                subtitleView.text = if (m.status.step > 1) {
                    context.getString(R.string.chat_box_subtitle_working_step, m.status.step)
                } else {
                    context.getString(R.string.chat_box_subtitle_working)
                }
                statusElapsed.text = context.getString(
                    R.string.chat_box_elapsed,
                    TaskCardStatusResolver.formatElapsed(SystemClock.elapsedRealtime() - m.startedAtMs),
                )
            }
            is Mode.Idle -> {
                subtitleView.text = context.getString(
                    R.string.chat_box_subtitle_idle,
                    m.assistantName,
                    relativeTime(m.updatedAtMs),
                )
            }
            is Mode.Done -> Unit
        }
    }

    private fun relativeTime(atMs: Long): String {
        val now = System.currentTimeMillis()
        if (atMs <= 0L || now - atMs < DateUtils.MINUTE_IN_MILLIS) return context.getString(R.string.chat_box_just_now)
        return DateUtils.getRelativeTimeSpanString(atMs, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }

    /** Updates the message rows in place (only changed texts are touched while streaming). */
    private fun renderItems(items: List<ChatBoxItem>) {
        val maxBubble = ((anchor?.screenWidth ?: context.resources.displayMetrics.widthPixels) * 0.62f).toInt()
        for ((i, item) in items.withIndex()) {
            val kind = kindOf(item)
            val existing = list.getChildAt(i) as? TextView
            val view = if (existing != null && existing.tag == kind) {
                existing
            } else {
                if (existing != null) list.removeViewAt(i)
                newRow(kind, maxBubble).also { list.addView(it, i, rowParams(kind)) }
            }
            val content = formatContent(item)
            val raw = rawTextOf(item)
            if (view.getTag(R.id.chat_box_row_raw) != raw) {
                view.setTag(R.id.chat_box_row_raw, raw)
                view.text = content
            }
        }
        while (list.childCount > items.size) list.removeViewAt(list.childCount - 1)
    }

    private fun kindOf(item: ChatBoxItem): Int = when (item) {
        is ChatBoxItem.Bubble -> if (item.fromUser) KIND_USER else KIND_AI
        is ChatBoxItem.ToolChip -> KIND_TOOL
        is ChatBoxItem.RequestSecretCard -> KIND_TOOL
    }

    private fun rawTextOf(item: ChatBoxItem): String = when (item) {
        is ChatBoxItem.Bubble -> buildString {
            append(item.text)
            repeat(item.images) {
                if (isNotEmpty()) append(' ')
                append(context.getString(R.string.chat_box_image))
            }
            repeat(item.files) {
                if (isNotEmpty()) append(' ')
                append(context.getString(R.string.chat_box_file))
            }
        }
        is ChatBoxItem.ToolChip -> context.getString(
            if (item.done) R.string.chat_box_tool_done else R.string.chat_box_tool_running,
            toolName(item.rawName),
        )
        is ChatBoxItem.RequestSecretCard -> when {
            item.isAnswered -> "✓ 已录入密钥：${item.storedName ?: item.secretName}"
            item.isDenied -> "✕ 已拒绝录入密钥：${item.secretName}"
            else -> "🔑 请求安全输入密钥：${item.secretName}"
        }
    }

    private fun formatContent(item: ChatBoxItem): CharSequence = when (item) {
        is ChatBoxItem.Bubble -> {
            val formatted = ChatBoxMarkdown.format(item.text, codeBgColor = palette.toolChip)
            if (item.images == 0 && item.files == 0) {
                formatted
            } else {
                android.text.SpannableStringBuilder(formatted).apply {
                    repeat(item.images) {
                        if (isNotEmpty()) append(' ')
                        append(context.getString(R.string.chat_box_image))
                    }
                    repeat(item.files) {
                        if (isNotEmpty()) append(' ')
                        append(context.getString(R.string.chat_box_file))
                    }
                }
            }
        }
        is ChatBoxItem.ToolChip -> rawTextOf(item)
        is ChatBoxItem.RequestSecretCard -> rawTextOf(item)
    }

    private fun newRow(kind: Int, maxBubble: Int): TextView = TextView(context).apply {
        tag = kind
        val p = palette
        when (kind) {
            KIND_USER -> {
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setLineSpacing(dp(1.5f).toFloat(), 1f)
                typeface = chatTypeface ?: Typeface.DEFAULT
                maxWidth = maxBubble
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(ChatBoxPalette.USER_BUBBLE_START, ChatBoxPalette.USER_BUBBLE_END),
                ).apply { cornerRadii = corners(14f, 14f, 4f, 14f) }
            }
            KIND_AI -> {
                setTextColor(p.aiText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setLineSpacing(dp(1.5f).toFloat(), 1f)
                typeface = chatTypeface ?: Typeface.DEFAULT
                maxWidth = maxBubble
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = GradientDrawable().apply {
                    setColor(p.aiBubble)
                    cornerRadii = corners(14f, 14f, 14f, 4f)
                }
            }
            else -> {
                setTextColor(p.toolText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = maxBubble
                setPadding(dp(9), dp(3), dp(9), dp(3))
                background = rounded(p.toolChip, dp(9).toFloat())
            }
        }
    }

    private fun rowParams(kind: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = if (kind == KIND_USER) Gravity.END else Gravity.START
        topMargin = dp(7)
    }

    /** Corner radii in dp: top-left, top-right, bottom-right, bottom-left. */
    private fun corners(tl: Float, tr: Float, br: Float, bl: Float): FloatArray {
        val a = dp(tl).toFloat()
        val b = dp(tr).toFloat()
        val c = dp(br).toFloat()
        val d = dp(bl).toFloat()
        return floatArrayOf(a, a, b, b, c, c, d, d)
    }

    private fun isNearBottom(): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        return scroll.scrollY + scroll.height >= child.height - dp(48)
    }

    private fun scrollToBottom() {
        scroll.post {
            val child = scroll.getChildAt(0) ?: return@post
            scroll.scrollTo(0, (child.height - scroll.height).coerceAtLeast(0))
        }
    }

    private fun toolName(raw: String): String = TaskCardToolNames.displayName(context, raw)

    private fun submitInput() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        callbacks.onSend(text)
    }

    // ---- Window: glass / fallback background and placement ----

    private fun blurAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerBlurListener() {
        val listener = Consumer<Boolean> { enabled ->
            handler.post {
                if (dialog == null || enabled == glass) return@post
                glass = enabled
                rebuild()
            }
        }
        runCatching { wm.addCrossWindowBlurEnabledListener(listener) }
            .onSuccess { blurListener = listener }
            .onFailure { Log.w(TAG, "blur listener failed", it) }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun unregisterBlurListener() {
        val listener = blurListener ?: return
        blurListener = null
        runCatching { wm.removeCrossWindowBlurEnabledListener(listener) }
    }

    private fun applyBackground() {
        val w = dialog?.window ?: return
        val p = palette
        val radius = dp(CORNER_DP).toFloat()
        val bg: Drawable = if (glass) {
            // The blurred region takes the bounds and rounded corners of this drawable.
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(p.surface)
                setStroke(dp(1), p.rim)
            }
        } else {
            PointerCardBackground(p.surface, p.rim, dp(1).toFloat(), radius, dp(POINTER_DP).toFloat())
        }
        windowBackground = bg
        w.setBackgroundDrawable(bg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { w.setBackgroundBlurRadius(if (glass) dp(BLUR_DP) else 0) }
        }
    }

    /**
     * Window bounds from [anchor]: ~88% of the screen wide and ~55% tall; below a top bar,
     * beside a side bar (vertically centred on it, kept on screen). With the keyboard up the
     * box moves up so its bottom stays above the keyboard.
     */
    private fun place() {
        val d = dialog ?: return
        val w = d.window ?: return
        val a = anchor ?: return
        val margin = dp(MARGIN_DP)
        val gap = dp(GAP_DP)
        val pad = dp(PADDING_DP)
        val pointer = if (glass) 0 else dp(POINTER_DP)
        val pillThin = dp(EdgeBarLook.BAR_WIDTH_DP)
        val barCenterX = a.barLeft + a.barWidth / 2
        val barCenterY = a.barTop + a.barHeight / 2
        val usableTop = a.topInset + margin
        val usableBottom = a.screenHeight - a.bottomInset - margin

        var side = when (a.edge) {
            EdgeBarLook.Edge.TOP -> PointerCardBackground.Side.TOP
            EdgeBarLook.Edge.RIGHT -> PointerCardBackground.Side.RIGHT
            EdgeBarLook.Edge.LEFT -> PointerCardBackground.Side.LEFT
        }
        val cardWidth: Int
        val cardHeight = (a.screenHeight * HEIGHT_FRACTION).toInt()
            .coerceAtMost(usableBottom - usableTop - pointer)
        val x: Int
        var y: Int
        when (a.edge) {
            EdgeBarLook.Edge.TOP -> {
                cardWidth = (a.screenWidth * WIDTH_FRACTION).toInt().coerceAtMost(a.screenWidth - 2 * margin)
                x = (barCenterX - cardWidth / 2).coerceIn(margin, (a.screenWidth - margin - cardWidth).coerceAtLeast(margin))
                y = a.barTop + pillThin + gap
            }
            EdgeBarLook.Edge.RIGHT -> {
                val right = a.screenWidth - pillThin - gap
                cardWidth = (a.screenWidth * WIDTH_FRACTION).toInt().coerceAtMost(right - margin - pointer)
                x = right - cardWidth - pointer
                y = (barCenterY - cardHeight / 2).coerceIn(usableTop, (usableBottom - cardHeight).coerceAtLeast(usableTop))
            }
            EdgeBarLook.Edge.LEFT -> {
                cardWidth = (a.screenWidth * WIDTH_FRACTION).toInt()
                    .coerceAtMost(a.screenWidth - pillThin - gap - margin - pointer)
                x = pillThin + gap
                y = (barCenterY - cardHeight / 2).coerceIn(usableTop, (usableBottom - cardHeight).coerceAtLeast(usableTop))
            }
        }
        val winWidth = cardWidth + if (side == PointerCardBackground.Side.TOP) 0 else pointer
        val winHeight = cardHeight + if (side == PointerCardBackground.Side.TOP) pointer else 0

        // Keyboard: keep the box's bottom above it (and drop the pointer, it no longer lines up).
        if (imeBottom > 0) {
            val limit = a.screenHeight - imeBottom - margin
            if (y + winHeight > limit) {
                y = (limit - winHeight).coerceAtLeast(usableTop)
                side = PointerCardBackground.Side.NONE
            }
        }

        root.setPadding(
            pad + if (side == PointerCardBackground.Side.LEFT) pointer else 0,
            pad - dp(2) + if (side == PointerCardBackground.Side.TOP) pointer else 0,
            pad + if (side == PointerCardBackground.Side.RIGHT) pointer else 0,
            pad - dp(4),
        )
        (windowBackground as? PointerCardBackground)?.let { bg ->
            bg.side = if (pointer == 0) PointerCardBackground.Side.NONE else side
            bg.pointerAt = when (side) {
                PointerCardBackground.Side.TOP -> (barCenterX - x).toFloat()
                PointerCardBackground.Side.LEFT, PointerCardBackground.Side.RIGHT -> (barCenterY - y).toFloat()
                PointerCardBackground.Side.NONE -> 0f
            }
            bg.inset = pointer.toFloat()
            bg.anchorSide = when (a.edge) {
                EdgeBarLook.Edge.TOP -> PointerCardBackground.Side.TOP
                EdgeBarLook.Edge.RIGHT -> PointerCardBackground.Side.RIGHT
                EdgeBarLook.Edge.LEFT -> PointerCardBackground.Side.LEFT
            }
        }

        val lp = w.attributes
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.x = x
        lp.y = y
        lp.width = winWidth
        lp.height = winHeight
        lp.dimAmount = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp.setFitInsetsTypes(0)
        lp.title = "EdgeBarChatBox"
        w.attributes = lp
    }

    private fun collapseKind(): IconView.Kind = when (anchor?.edge) {
        EdgeBarLook.Edge.RIGHT -> IconView.Kind.CHEVRON_RIGHT
        EdgeBarLook.Edge.LEFT -> IconView.Kind.CHEVRON_LEFT
        else -> IconView.Kind.CHEVRON_UP
    }

    private fun imm(): InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun iconButton(kind: IconView.Kind, label: Int, filled: Boolean, onClick: () -> Unit): View =
        FrameLayout(context).apply {
            if (filled) background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.control)
            }
            contentDescription = context.getString(label)
            isClickable = true
            isFocusable = true
            val iconColor = if (filled) palette.controlIcon else palette.subtitle
            addView(IconView(context, kind, iconColor), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
            setOnClickListener { onClick() }
        }

    private fun appIconView(): ImageView = ImageView(context).apply {
        val icon = runCatching { context.packageManager.getApplicationIcon(context.packageName) }.getOrNull()
        if (icon != null) setImageDrawable(icon) else setImageDrawable(ColorDrawable(palette.accent))
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    /** Small stroked icons: plus, open (↗), chevrons, send arrow. */
    private class IconView(context: Context, private val kind: Kind, color: Int) : View(context) {
        enum class Kind { PLUS, OPEN, CHEVRON_UP, CHEVRON_LEFT, CHEVRON_RIGHT, SEND }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.2f * context.resources.displayMetrics.density
        }
        private val path = Path()

        init {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            path.reset()
            when (kind) {
                Kind.PLUS -> {
                    path.moveTo(w / 2, h * 0.12f); path.lineTo(w / 2, h * 0.88f)
                    path.moveTo(w * 0.12f, h / 2); path.lineTo(w * 0.88f, h / 2)
                }
                Kind.OPEN -> {
                    path.moveTo(w * 0.55f, h * 0.12f); path.lineTo(w * 0.88f, h * 0.12f); path.lineTo(w * 0.88f, h * 0.45f)
                    path.moveTo(w * 0.88f, h * 0.12f); path.lineTo(w * 0.45f, h * 0.55f)
                    path.moveTo(w * 0.75f, h * 0.6f); path.lineTo(w * 0.75f, h * 0.88f); path.lineTo(w * 0.12f, h * 0.88f)
                    path.lineTo(w * 0.12f, h * 0.25f); path.lineTo(w * 0.4f, h * 0.25f)
                }
                Kind.CHEVRON_UP -> {
                    path.moveTo(w * 0.2f, h * 0.65f); path.lineTo(w / 2, h * 0.35f); path.lineTo(w * 0.8f, h * 0.65f)
                }
                Kind.CHEVRON_RIGHT -> {
                    path.moveTo(w * 0.35f, h * 0.2f); path.lineTo(w * 0.65f, h / 2); path.lineTo(w * 0.35f, h * 0.8f)
                }
                Kind.CHEVRON_LEFT -> {
                    path.moveTo(w * 0.65f, h * 0.2f); path.lineTo(w * 0.35f, h / 2); path.lineTo(w * 0.65f, h * 0.8f)
                }
                Kind.SEND -> {
                    path.moveTo(w / 2, h * 0.85f); path.lineTo(w / 2, h * 0.15f)
                    path.moveTo(w * 0.22f, h * 0.42f); path.lineTo(w / 2, h * 0.15f); path.lineTo(w * 0.78f, h * 0.42f)
                }
            }
            canvas.drawPath(path, paint)
        }
    }

    /**
     * Fallback (no blur) background: a rounded card with a thin rim and a small triangular
     * pointer toward the bar. The window is [inset] px larger than the card on the bar's side
     * ([anchorSide]); the pointer is drawn there at [pointerAt] (along that side), unless
     * [side] is NONE (keyboard up), in which case that strip stays empty.
     */
    private class PointerCardBackground(
        color: Int,
        rimColor: Int,
        private val rimWidth: Float,
        private val radius: Float,
        private val pointerSize: Float,
    ) : Drawable() {
        enum class Side { NONE, TOP, LEFT, RIGHT }

        var side: Side = Side.NONE
            set(value) { field = value; invalidateSelf() }
        var anchorSide: Side = Side.NONE
            set(value) { field = value; invalidateSelf() }
        var pointerAt: Float = 0f
            set(value) { field = value; invalidateSelf() }
        var inset: Float = 0f
            set(value) { field = value; invalidateSelf() }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = rimColor
            style = Paint.Style.STROKE
            strokeWidth = rimWidth
        }
        private val body = RectF()
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            body.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            when (anchorSide) {
                Side.TOP -> body.top += inset
                Side.LEFT -> body.left += inset
                Side.RIGHT -> body.right -= inset
                Side.NONE -> Unit
            }
            path.reset()
            path.addRoundRect(body, radius, radius, Path.Direction.CW)
            val half = pointerSize
            if (side != Side.NONE && inset > 0f) {
                val tri = Path()
                when (side) {
                    Side.TOP -> {
                        val c = pointerAt.coerceIn(body.left + radius + half, body.right - radius - half)
                        tri.moveTo(c - half, body.top + 1f); tri.lineTo(c, body.top - inset); tri.lineTo(c + half, body.top + 1f)
                    }
                    Side.LEFT -> {
                        val c = pointerAt.coerceIn(body.top + radius + half, body.bottom - radius - half)
                        tri.moveTo(body.left + 1f, c - half); tri.lineTo(body.left - inset, c); tri.lineTo(body.left + 1f, c + half)
                    }
                    Side.RIGHT -> {
                        val c = pointerAt.coerceIn(body.top + radius + half, body.bottom - radius - half)
                        tri.moveTo(body.right - 1f, c - half); tri.lineTo(body.right + inset, c); tri.lineTo(body.right - 1f, c + half)
                    }
                    Side.NONE -> Unit
                }
                tri.close()
                path.op(tri, Path.Op.UNION)
            }
            canvas.drawPath(path, fill)
            canvas.drawPath(path, rim)
        }

        override fun getOutline(outline: Outline) {
            outline.setRoundRect(
                body.left.toInt(), body.top.toInt(), body.right.toInt(), body.bottom.toInt(), radius,
            )
        }

        override fun setAlpha(alpha: Int) {
            fill.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fill.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val TAG = "EdgeBarChatBox"
        const val TICK_MS = 1000L
        const val KIND_USER = 1
        const val KIND_AI = 2
        const val KIND_TOOL = 3
        const val MARGIN_DP = 10f
        const val GAP_DP = 4f
        const val PADDING_DP = 14f
        const val POINTER_DP = 8f
        const val CORNER_DP = 24f
        const val BLUR_DP = 30f
        const val WIDTH_FRACTION = 0.88f
        const val HEIGHT_FRACTION = 0.55f
    }
}
