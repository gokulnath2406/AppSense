package com.appsense.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class IntentionOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val mainHandler: Handler
) {

    data class Model(
        val appLabel: String,
        val pauseSeconds: Int
    )

    interface Callbacks {
        fun onSelected(kind: IntentionKind)
        fun onNotNow()
        fun onTimeout()
    }

    private object Style {
        const val SCRIM_COLOR = 0xF2101015.toInt()
        const val CARD_COLOR = 0xFF1C1C22.toInt()
        const val CARD_STROKE_COLOR = 0x1FFFFFFF
        const val CAPTION_COLOR = 0xB3FFFFFF.toInt()
        const val PRIMARY_COLOR = 0xFF4ADE80.toInt()
        const val PRIMARY_TEXT_COLOR = 0xFF0B1F14.toInt()

        const val MAX_CARD_WIDTH_DP = 360
        const val SCREEN_MARGIN_DP = 24
        const val CARD_CORNER_RADIUS_DP = 24
        const val CARD_STROKE_WIDTH_DP = 1
        const val CARD_PADDING_DP = 24

        const val TITLE_SIZE_SP = 22f
        const val CAPTION_SIZE_SP = 14f
        const val SUBTITLE_SIZE_SP = 15f
        const val BUTTON_SIZE_SP = 16f

        const val ANSWER_BUTTON_HEIGHT_DP = 56
        const val NOT_NOW_BUTTON_HEIGHT_DP = 48
        const val BUTTON_CORNER_RADIUS_DP = 16
        const val BUTTON_GAP_DP = 8

        const val INPUT_GUARD_MS = 700L
        const val TIMEOUT_MS = 60_000L
        const val COUNTDOWN_INTERVAL_MS = 1_000L
    }

    private var rootView: FrameLayout? = null
    private var cardView: MaxHeightLinearLayout? = null
    private var subtitleView: TextView? = null
    private var boredButton: TextView? = null
    private var quickButton: TextView? = null
    private var notNowButton: TextView? = null

    private var callbacks: Callbacks? = null
    private var addedToWindowManager = false
    private var actionFired = false

    @Volatile
    private var dismissRequested = false

    private var showElapsedRealtimeMs = 0L
    private var inputAvailableAtMs = 0L
    private var countdownEndAtMs = 0L

    private var initialPauseSeconds = 0

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (!canUpdate()) return

            updateCountdown()

            if (remainingPauseSeconds() > 0) {
                mainHandler.postDelayed(
                    this,
                    Style.COUNTDOWN_INTERVAL_MS
                )
            }
        }
    }

    private val timeoutRunnable = Runnable {
        if (!canUpdate() || actionFired) return@Runnable

        actionFired = true
        callbacks?.onTimeout()
    }

    val isShowing: Boolean
        get() = addedToWindowManager && rootView != null

    fun show(model: Model, callbacks: Callbacks) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "IntentionOverlay.show() must be called on the main thread"
        }

        if (isShowing || rootView != null) return

        dismissRequested = false
        actionFired = false
        this.callbacks = callbacks

        val now = SystemClock.elapsedRealtime()
        showElapsedRealtimeMs = now
        inputAvailableAtMs = now + Style.INPUT_GUARD_MS

        initialPauseSeconds = model.pauseSeconds.coerceAtLeast(0)
        countdownEndAtMs =
            now + initialPauseSeconds.toLong() * Style.COUNTDOWN_INTERVAL_MS

        val root = FrameLayout(context).apply {
            background = android.graphics.drawable.ColorDrawable(
                Style.SCRIM_COLOR
            )
            isClickable = true
            isFocusable = false
            contentDescription = "Why are you here?"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                accessibilityPaneTitle = "Why are you here?"
            }

            setOnTouchListener { _, _ -> true }
        }

        val card = MaxHeightLinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            setPadding(
                dp(Style.CARD_PADDING_DP),
                dp(Style.CARD_PADDING_DP),
                dp(Style.CARD_PADDING_DP),
                dp(Style.CARD_PADDING_DP)
            )

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius =
                    dp(Style.CARD_CORNER_RADIUS_DP).toFloat()
                setColor(Style.CARD_COLOR)
                setStroke(
                    dp(Style.CARD_STROKE_WIDTH_DP),
                    Style.CARD_STROKE_COLOR
                )
            }
        }

        val scrollView = ScrollView(context).apply {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val appCaption = createTextView(
            text = model.appLabel,
            sizeSp = Style.CAPTION_SIZE_SP,
            color = Style.CAPTION_COLOR
        ).apply {
            gravity = Gravity.CENTER
            contentDescription = "Tracked app: ${model.appLabel}"
        }

        content.addView(
            appCaption,
            matchWrapParams()
        )

        val title = createTextView(
            text = "Why are you here?",
            sizeSp = Style.TITLE_SIZE_SP,
            color = Color.WHITE
        ).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
        }

        content.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )

        val subtitle = createTextView(
            text = "",
            sizeSp = Style.SUBTITLE_SIZE_SP,
            color = Style.CAPTION_COLOR
        ).apply {
            gravity = Gravity.CENTER
        }

        content.addView(
            subtitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(20)
            }
        )

        val bored = createAnswerButton("Just bored").apply {
            contentDescription = "Just bored"
            setOnClickListener {
                selectAnswer(IntentionKind.BORED)
            }
        }

        content.addView(
            bored,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(Style.ANSWER_BUTTON_HEIGHT_DP)
            )
        )

        val quick = createAnswerButton("Only 1-2 minutes").apply {
            contentDescription = "Only 1-2 minutes"
            setOnClickListener {
                selectAnswer(IntentionKind.QUICK)
            }
        }

        content.addView(
            quick,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(Style.ANSWER_BUTTON_HEIGHT_DP)
            ).apply {
                topMargin = dp(Style.BUTTON_GAP_DP)
            }
        )

        val notNow = createTextView(
            text = "Not now",
            sizeSp = Style.SUBTITLE_SIZE_SP,
            color = Style.CAPTION_COLOR
        ).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minHeight = dp(Style.NOT_NOW_BUTTON_HEIGHT_DP)
            contentDescription = "Not now"

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
            }

            setOnClickListener {
                if (!canAcceptNotNow()) return@setOnClickListener
                fireAction {
                    it.onNotNow()
                }
            }
        }

        content.addView(
            notNow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(Style.NOT_NOW_BUTTON_HEIGHT_DP)
            ).apply {
                topMargin = dp(4)
            }
        )

        scrollView.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        card.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        root.addOnLayoutChangeListener {
                _, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom ->

            val width = right - left
            val height = bottom - top

            if (
                width != oldRight - oldLeft ||
                height != oldBottom - oldTop
            ) {
                updateCardBounds(width, height)
            }
        }

        rootView = root
        cardView = card
        subtitleView = subtitle
        boredButton = bored
        quickButton = quick
        notNowButton = notNow

        updateCountdown()
        updateAnswerButtonState()

        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(root, params)
            addedToWindowManager = true

            updateCardBounds(root.width, root.height)

            mainHandler.postDelayed(
                countdownRunnable,
                Style.COUNTDOWN_INTERVAL_MS
            )

            mainHandler.postDelayed(
                timeoutRunnable,
                Style.TIMEOUT_MS
            )
        } catch (_: Exception) {
            cleanup(root)
        }
    }

    fun dismiss() {
        dismissRequested = true

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                dismissOnMainThread()
            }
            return
        }

        dismissOnMainThread()
    }

    private fun dismissOnMainThread() {
        val root = rootView ?: return
        cleanup(root)
    }

    private fun selectAnswer(kind: IntentionKind) {
        if (!canAcceptAnswer()) return

        fireAction {
            it.onSelected(kind)
        }
    }

    private inline fun fireAction(
        action: (Callbacks) -> Unit
    ) {
        if (!canUpdate() || actionFired) return

        val activeCallbacks = callbacks ?: return

        actionFired = true
        action(activeCallbacks)
    }

    private fun canAcceptAnswer(): Boolean {
        return canUpdate() &&
                !actionFired &&
                remainingPauseSeconds() == 0
    }

    private fun canAcceptNotNow(): Boolean {
        return canUpdate() &&
                !actionFired &&
                SystemClock.elapsedRealtime() >= inputAvailableAtMs
    }

    private fun canUpdate(): Boolean {
        return !dismissRequested &&
                addedToWindowManager &&
                rootView != null
    }

    private fun remainingPauseSeconds(): Int {
        val remainingMs =
            (countdownEndAtMs - SystemClock.elapsedRealtime())
                .coerceAtLeast(0L)

        return ((remainingMs + 999L) / 1000L).toInt()
    }

    private fun updateCountdown() {
        if (!canUpdate()) return

        val remaining = remainingPauseSeconds()

        subtitleView?.text = if (remaining > 0) {
            "Take a breath. Choose in $remaining"
        } else {
            "Choose one"
        }

        updateAnswerButtonState()
    }

    private fun updateAnswerButtonState() {
        val enabled = remainingPauseSeconds() == 0 &&
                !actionFired &&
                canUpdate()

        boredButton?.apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
        }

        quickButton?.apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
        }
    }

    private fun createAnswerButton(text: String): TextView {
        return createTextView(
            text = text,
            sizeSp = Style.BUTTON_SIZE_SP,
            color = Style.PRIMARY_TEXT_COLOR
        ).apply {
            gravity = Gravity.CENTER
            minHeight = dp(Style.ANSWER_BUTTON_HEIGHT_DP)
            isClickable = true
            isFocusable = true

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius =
                    dp(Style.BUTTON_CORNER_RADIUS_DP).toFloat()
                setColor(Style.PRIMARY_COLOR)
            }
        }
    }

    private fun createTextView(
        text: String,
        sizeSp: Float,
        color: Int
    ): TextView {
        val textView = TextView(context).apply {
            this.text = text
            setTextColor(color)
            includeFontPadding = true
        }

        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sizeSp,
            context.resources.displayMetrics
        )

        textView.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            textSizePx
        )

        return textView
    }

    private fun updateCardBounds(
        rootWidth: Int,
        rootHeight: Int
    ) {
        val card = cardView ?: return

        val screenWidth = if (rootWidth > 0) {
            rootWidth
        } else {
            context.resources.displayMetrics.widthPixels
        }

        val screenHeight = if (rootHeight > 0) {
            rootHeight
        } else {
            context.resources.displayMetrics.heightPixels
        }

        val margin = dp(Style.SCREEN_MARGIN_DP)
        val maxWidth = dp(Style.MAX_CARD_WIDTH_DP)

        val width = (screenWidth - margin * 2)
            .coerceAtLeast(1)
            .coerceAtMost(maxWidth)

        val maxHeight = (screenHeight - margin * 2)
            .coerceAtLeast(1)

        card.maxHeightPx = maxHeight

        val params = card.layoutParams as? FrameLayout.LayoutParams
            ?: return

        if (
            params.width != width ||
            params.gravity != Gravity.CENTER
        ) {
            params.width = width
            params.height = FrameLayout.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.CENTER
            card.layoutParams = params
        } else {
            card.requestLayout()
        }
    }

    private fun cleanup(root: FrameLayout) {
        if (rootView !== root) return

        mainHandler.removeCallbacks(countdownRunnable)
        mainHandler.removeCallbacks(timeoutRunnable)

        val shouldRemove = addedToWindowManager
        addedToWindowManager = false

        if (shouldRemove) {
            try {
                windowManager.removeView(root)
            } catch (_: Exception) {
                // Window may already have been removed by the system.
            }
        }

        rootView = null
        cardView = null
        subtitleView = null
        boredButton = null
        quickButton = null
        notNowButton = null
        callbacks = null

        actionFired = true
        dismissRequested = true
    }

    private fun matchWrapParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun dp(value: Int): Int {
        return (
                value * context.resources.displayMetrics.density + 0.5f
                ).toInt()
    }

    private class MaxHeightLinearLayout(
        context: Context
    ) : LinearLayout(context) {

        var maxHeightPx: Int = Int.MAX_VALUE

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int
        ) {
            val constrainedHeightSpec =
                MeasureSpec.makeMeasureSpec(
                    minOf(
                        MeasureSpec.getSize(heightMeasureSpec)
                            .takeIf {
                                MeasureSpec.getMode(heightMeasureSpec) !=
                                        MeasureSpec.UNSPECIFIED
                            } ?: maxHeightPx,
                        maxHeightPx
                    ),
                    MeasureSpec.AT_MOST
                )

            super.onMeasure(
                widthMeasureSpec,
                constrainedHeightSpec
            )
        }
    }
}