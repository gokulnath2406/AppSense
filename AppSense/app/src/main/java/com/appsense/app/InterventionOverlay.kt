package com.appsense.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object InterventionTheme {
    const val SCRIM_COLOR = -1778384896 // Color.argb(150, 0, 0, 0)
    const val CARD_COLOR = 0xF51C1C22.toInt()
    const val CARD_STROKE_COLOR = 0x1FFFFFFF
    const val CAPTION_COLOR = 0xB3FFFFFF.toInt()
    const val PRIMARY_COLOR = 0xFF4ADE80.toInt()
    const val PRIMARY_TEXT_COLOR = 0xFF0B1F14.toInt()
    const val SECONDARY_TEXT_COLOR = 0xB3FFFFFF.toInt()

    const val MAX_CARD_WIDTH_DP = 360
    const val SCREEN_MARGIN_DP = 24
    const val CARD_CORNER_RADIUS_DP = 28
    const val CARD_STROKE_WIDTH_DP = 1
    const val CARD_PADDING_DP = 24
    const val CAPTION_TEXT_SIZE_SP = 14f
    const val PRIMARY_BUTTON_HEIGHT_DP = 52
    const val CAPTION_TO_PRIMARY_BUTTON_SPACING_DP = 16
    const val BUTTON_CORNER_RADIUS_DP = 16
    const val PRIMARY_TEXT_SIZE_SP = 16f
    const val SECONDARY_BUTTON_MIN_HEIGHT_DP = 48
    const val SECONDARY_TEXT_SIZE_SP = 15f

    const val BREATHING_ACCENT_COLOR = 0xFF4ADE80.toInt()
    const val BREATHING_CIRCLE_ALPHA = 64
    const val BREATHING_RING_WIDTH_DP = 3
    const val BREATHING_MIN_SCALE = 0.55f
    const val BREATHING_MAX_SCALE = 1.0f
    const val BREATHING_PHASE_TEXT_COLOR = Color.WHITE
    const val BREATHING_COUNTDOWN_TEXT_COLOR = 0xB3FFFFFF.toInt()
    const val BREATHING_PHASE_TEXT_SIZE_SP = 22f
    const val BREATHING_COUNTDOWN_TEXT_SIZE_SP = 15f
    const val BREATHING_CONTENT_HEIGHT_DP = 260
    const val BREATHING_SKIP_TEXT_SIZE_SP = 14f
    const val BREATHING_FINISHED_TEXT_SIZE_SP = 18f
}

class InterventionOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val mainHandler: Handler
) {
    data class Model(
        val appLabel: String,
        val minutes: Int,
        val type: String,
        val continueAtElapsedRealtimeMs: Long
    )

    interface Callbacks {
        fun onClose()
        fun onContinue()
    }

    private var rootView: FrameLayout? = null
    private var cardView: MaxHeightLinearLayout? = null
    private var contentFrame: FrameLayout? = null
    private var captionView: TextView? = null
    private var closeButton: TextView? = null
    private var continueButton: TextView? = null
    private var currentModel: Model? = null
    private var currentCallbacks: Callbacks? = null
    private var addedToWindowManager = false

    val isShowing: Boolean
        get() = addedToWindowManager && rootView != null

    fun show(model: Model, callbacks: Callbacks) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(model, callbacks) }
            return
        }

        currentModel = model
        currentCallbacks = callbacks

        val existingRoot = rootView
        if (existingRoot != null && addedToWindowManager) {
            updateCard(model)
            refreshCountdown()
            return
        }

        val density = context.resources.displayMetrics.density
        val root = FrameLayout(context).apply {
            setBackgroundColor(InterventionTheme.SCRIM_COLOR)
            isClickable = true
            setOnTouchListener { _, _ -> true }
        }

        val card = MaxHeightLinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                dp(InterventionTheme.CARD_PADDING_DP),
                dp(InterventionTheme.CARD_PADDING_DP),
                dp(InterventionTheme.CARD_PADDING_DP),
                dp(InterventionTheme.CARD_PADDING_DP)
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(InterventionTheme.CARD_CORNER_RADIUS_DP).toFloat()
                setColor(InterventionTheme.CARD_COLOR)
                setStroke(dp(InterventionTheme.CARD_STROKE_WIDTH_DP), InterventionTheme.CARD_STROKE_COLOR)
            }
        }

        val caption = TextView(context).apply {
            textSize = InterventionTheme.CAPTION_TEXT_SIZE_SP
            setTextColor(InterventionTheme.CAPTION_COLOR)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = true
        }
        card.addView(
            caption,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val content = FrameLayout(context)
        card.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val close = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = InterventionTheme.PRIMARY_TEXT_SIZE_SP
            setTextColor(InterventionTheme.PRIMARY_TEXT_COLOR)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(InterventionTheme.BUTTON_CORNER_RADIUS_DP).toFloat()
                setColor(InterventionTheme.PRIMARY_COLOR)
            }
            setOnClickListener { currentCallbacks?.onClose() }
        }
        val closeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(InterventionTheme.PRIMARY_BUTTON_HEIGHT_DP)
        ).apply {
            topMargin = dp(InterventionTheme.CAPTION_TO_PRIMARY_BUTTON_SPACING_DP)
        }
        card.addView(close, closeParams)

        val continueAction = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = InterventionTheme.SECONDARY_TEXT_SIZE_SP
            setTextColor(InterventionTheme.SECONDARY_TEXT_COLOR)
            minHeight = dp(InterventionTheme.SECONDARY_BUTTON_MIN_HEIGHT_DP)
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
            }
            setOnClickListener {
                val activeModel = currentModel ?: return@setOnClickListener
                if (SystemClock.elapsedRealtime() >= activeModel.continueAtElapsedRealtimeMs) {
                    currentCallbacks?.onContinue()
                }
            }
        }
        card.addView(
            continueAction,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(InterventionTheme.SECONDARY_BUTTON_MIN_HEIGHT_DP)
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

        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            if (width != oldRight - oldLeft || height != oldBottom - oldTop) {
                updateCardBounds(width, height)
            }
        }

        rootView = root
        cardView = card
        contentFrame = content
        captionView = caption
        closeButton = close
        continueButton = continueAction
        updateCard(model)
        refreshCountdown()

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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
        } catch (_: Exception) {
            rootView = null
            cardView = null
            contentFrame = null
            captionView = null
            closeButton = null
            continueButton = null
            addedToWindowManager = false
        }
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismiss() }
            return
        }
        val root = rootView
        if (root != null && addedToWindowManager) {
            try {
                windowManager.removeView(root)
            } catch (_: Exception) {
            }
        }
        rootView = null
        cardView = null
        contentFrame = null
        captionView = null
        closeButton = null
        continueButton = null
        currentModel = null
        currentCallbacks = null
        addedToWindowManager = false
    }

    fun setContent(view: View?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setContent(view) }
            return
        }
        val frame = contentFrame ?: return
        frame.removeAllViews()
        val frameParams = frame.layoutParams as? LinearLayout.LayoutParams
        if (frameParams != null) {
            frameParams.height = if (view != null) {
                dp(InterventionTheme.BREATHING_CONTENT_HEIGHT_DP)
            } else {
                0
            }
            frameParams.weight = if (view != null) 0f else 1f
            frame.layoutParams = frameParams
        }
        if (view != null) {
            frame.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.TOP
                )
            )
        }
    }

    fun refreshCountdown() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { refreshCountdown() }
            return
        }
        val model = currentModel ?: return
        val button = continueButton ?: return
        val remainingMs = (model.continueAtElapsedRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val remainingSeconds = ((remainingMs + 999L) / 1000L).toInt()
        button.text = if (remainingSeconds > 0) "Continue in ${remainingSeconds}s" else "Continue"
        button.isEnabled = remainingMs == 0L
        button.alpha = if (button.isEnabled) 1f else 0.65f
    }

    private fun updateCard(model: Model) {
        captionView?.text = "${model.minutes} min on ${model.appLabel}"
        closeButton?.text = "Close ${model.appLabel}"
        updateCardBounds(rootView?.width ?: 0, rootView?.height ?: 0)
    }

    private fun updateCardBounds(rootWidth: Int, rootHeight: Int) {
        val card = cardView ?: return
        val screenWidth = if (rootWidth > 0) rootWidth else context.resources.displayMetrics.widthPixels
        val screenHeight = if (rootHeight > 0) rootHeight else context.resources.displayMetrics.heightPixels
        val margin = dp(InterventionTheme.SCREEN_MARGIN_DP)
        val maxWidth = dp(InterventionTheme.MAX_CARD_WIDTH_DP)
        val width = (screenWidth - margin * 2).coerceAtLeast(1).coerceAtMost(maxWidth)
        val maxHeight = (screenHeight - margin * 2).coerceAtLeast(1)
        card.maxHeightPx = maxHeight
        val params = card.layoutParams as? FrameLayout.LayoutParams
        if (params != null) {
            if (params.width != width || params.gravity != Gravity.CENTER) {
                params.width = width
                params.height = FrameLayout.LayoutParams.WRAP_CONTENT
                params.gravity = Gravity.CENTER
                card.layoutParams = params
            } else {
                card.requestLayout()
            }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private class MaxHeightLinearLayout(context: Context) : LinearLayout(context) {
        var maxHeightPx: Int = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val constrainedHeightSpec = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            } else {
                MeasureSpec.makeMeasureSpec(
                    minOf(MeasureSpec.getSize(heightMeasureSpec), maxHeightPx),
                    MeasureSpec.AT_MOST
                )
            }
            super.onMeasure(widthMeasureSpec, constrainedHeightSpec)
        }
    }
}
