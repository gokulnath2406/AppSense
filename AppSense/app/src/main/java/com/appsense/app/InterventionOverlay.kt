package com.appsense.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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

/** Implemented only by interactive content hosted inside InterventionOverlay. */
interface InterventionInputGuard {
    fun setInputGuardUntilElapsedRealtimeMs(untilMs: Long)
    fun setInputBlocked(blocked: Boolean)
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
    private var contentScrollView: ScrollView? = null
    private var captionView: TextView? = null
    private var closeButton: TextView? = null
    private var continueButton: TextView? = null
    private var currentModel: Model? = null
    private var currentCallbacks: Callbacks? = null
    private var addedToWindowManager = false

    private var showAnimator: AnimatorSet? = null
    private var dismissAnimator: AnimatorSet? = null
    private var dismissInProgress = false
    private var inputGuardUntilElapsedRealtimeMs = 0L

    private val inputGuardEndRunnable = Runnable {
        if (rootView != null && addedToWindowManager && !dismissInProgress) {
            closeButton?.alpha = 1f
            refreshCountdown()
        }
    }

    private companion object {
        const val INPUT_GUARD_MS = 700L
        const val SCRIM_ANIMATION_MS = 180L
        const val CARD_ANIMATION_MS = 220L
        const val DISMISS_ANIMATION_MS = 150L
    }

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
            if (dismissInProgress) {
                // A new show request supersedes an in-progress dismissal.
                val animator = dismissAnimator
                dismissAnimator = null
                animator?.removeAllListeners()
                animator?.cancel()
                dismissInProgress = false
                setContentInputBlocked(false)
                beginInputGuard()
                cardView?.let { startEntranceAnimation(existingRoot, it) }
            }
            updateCard(model)
            refreshCountdown()
            return
        }

        val root = FrameLayout(context).apply {
            background = ColorDrawable(Color.TRANSPARENT)
            isClickable = true
            contentDescription = "Take a break"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                accessibilityPaneTitle = "Take a break"
            }
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
                setStroke(
                    dp(InterventionTheme.CARD_STROKE_WIDTH_DP),
                    InterventionTheme.CARD_STROKE_COLOR
                )
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

        val contentScroll = ScrollView(context).apply {
            isFillViewport = false
            clipToPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val content = FrameLayout(context)
        contentScroll.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        card.addView(
            contentScroll,
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
            contentDescription = "Close"
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
            setOnClickListener {
                if (canAcceptInput()) currentCallbacks?.onClose()
            }
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
            contentDescription = "Continue"
            minHeight = dp(InterventionTheme.SECONDARY_BUTTON_MIN_HEIGHT_DP)
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
            }
            setOnClickListener {
                if (!canAcceptInput()) return@setOnClickListener
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
        contentScrollView = contentScroll
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
            beginInputGuard()
            card.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            startEntranceAnimation(root, card)
        } catch (_: Exception) {
            removeOverlay(root)
        }
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismiss() }
            return
        }

        val root = rootView ?: return
        if (!addedToWindowManager) {
            removeOverlay(root)
            return
        }
        if (dismissInProgress) return

        dismissInProgress = true
        mainHandler.removeCallbacks(inputGuardEndRunnable)
        showAnimator?.cancel()
        showAnimator = null
        setContentInputBlocked(true)

        if (isAnimationsDisabled()) {
            removeOverlay(root)
            return
        }

        val card = cardView
        if (card == null) {
            removeOverlay(root)
            return
        }

        val scrimStartAlpha = (root.background as? ColorDrawable)?.alpha ?: 150
        val interpolator = DecelerateInterpolator()
        val scrimAnimator = ValueAnimator.ofInt(scrimStartAlpha, 0).apply {
            duration = DISMISS_ANIMATION_MS
            this.interpolator = interpolator
            addUpdateListener {
                if (rootView === root && dismissInProgress) {
                    root.setBackgroundColor(Color.argb(it.animatedValue as Int, 0, 0, 0))
                }
            }
        }
        val alphaAnimator = ObjectAnimator.ofFloat(card, View.ALPHA, card.alpha, 0f).apply {
            duration = DISMISS_ANIMATION_MS
            this.interpolator = interpolator
        }
        val scaleXAnimator = ObjectAnimator.ofFloat(card, View.SCALE_X, card.scaleX, 0.96f).apply {
            duration = DISMISS_ANIMATION_MS
            this.interpolator = interpolator
        }
        val scaleYAnimator = ObjectAnimator.ofFloat(card, View.SCALE_Y, card.scaleY, 0.96f).apply {
            duration = DISMISS_ANIMATION_MS
            this.interpolator = interpolator
        }

        dismissAnimator = AnimatorSet().apply {
            playTogether(scrimAnimator, alphaAnimator, scaleXAnimator, scaleYAnimator)
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && rootView === root && dismissInProgress) {
                        dismissAnimator = null
                        removeOverlay(root)
                    }
                }
            })
            start()
        }
    }

    fun setContent(view: View?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setContent(view) }
            return
        }
        val frame = contentFrame ?: return
        frame.removeAllViews()
        val frameParams = frame.layoutParams as? FrameLayout.LayoutParams
        if (frameParams != null) {
            frameParams.height = if (view != null) {
                dp(InterventionTheme.BREATHING_CONTENT_HEIGHT_DP)
            } else {
                0
            }
            frame.layoutParams = frameParams
        }
        contentScrollView?.scrollTo(0, 0)
        if (view != null) {
            frame.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.TOP
                )
            )
            applyInputGuardRecursively(view, inputGuardUntilElapsedRealtimeMs)
            if (dismissInProgress) applyInputBlockedRecursively(view, true)
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
        button.contentDescription = button.text.toString()
        button.isEnabled = remainingMs == 0L
        button.alpha = when {
            dismissInProgress || SystemClock.elapsedRealtime() < inputGuardUntilElapsedRealtimeMs -> 0.6f
            button.isEnabled -> 1f
            else -> 0.65f
        }
    }

    private fun isAnimationsDisabled(): Boolean {
        return try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) {
            false
        }
    }

    private fun canAcceptInput(): Boolean {
        return rootView != null &&
                addedToWindowManager &&
                !dismissInProgress &&
                SystemClock.elapsedRealtime() >= inputGuardUntilElapsedRealtimeMs
    }

    private fun beginInputGuard() {
        inputGuardUntilElapsedRealtimeMs = SystemClock.elapsedRealtime() + INPUT_GUARD_MS
        closeButton?.alpha = 0.6f
        refreshCountdown()
        setContentInputGuard(inputGuardUntilElapsedRealtimeMs)
        mainHandler.removeCallbacks(inputGuardEndRunnable)
        mainHandler.postDelayed(inputGuardEndRunnable, INPUT_GUARD_MS)
    }

    private fun setContentInputGuard(untilMs: Long) {
        applyInputGuardRecursively(contentFrame, untilMs)
    }

    private fun setContentInputBlocked(blocked: Boolean) {
        applyInputBlockedRecursively(contentFrame, blocked)
    }

    private fun applyInputGuardRecursively(view: View?, untilMs: Long) {
        if (view == null) return
        if (view is InterventionInputGuard) {
            view.setInputGuardUntilElapsedRealtimeMs(untilMs)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyInputGuardRecursively(view.getChildAt(index), untilMs)
            }
        }
    }

    private fun applyInputBlockedRecursively(view: View?, blocked: Boolean) {
        if (view == null) return
        if (view is InterventionInputGuard) {
            view.setInputBlocked(blocked)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyInputBlockedRecursively(view.getChildAt(index), blocked)
            }
        }
    }

    private fun startEntranceAnimation(root: FrameLayout, card: View) {
        showAnimator?.cancel()
        showAnimator = null

        if (isAnimationsDisabled()) {
            root.setBackgroundColor(InterventionTheme.SCRIM_COLOR)
            card.alpha = 1f
            card.scaleX = 1f
            card.scaleY = 1f
            return
        }

        root.setBackgroundColor(Color.TRANSPARENT)
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f

        val interpolator = DecelerateInterpolator()
        val scrimAnimator = ValueAnimator.ofInt(0, 150).apply {
            duration = SCRIM_ANIMATION_MS
            this.interpolator = interpolator
            addUpdateListener {
                if (rootView === root && !dismissInProgress) {
                    root.setBackgroundColor(Color.argb(it.animatedValue as Int, 0, 0, 0))
                }
            }
        }
        val alphaAnimator = ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f).apply {
            duration = CARD_ANIMATION_MS
            this.interpolator = interpolator
        }
        val scaleXAnimator = ObjectAnimator.ofFloat(card, View.SCALE_X, 0.96f, 1f).apply {
            duration = CARD_ANIMATION_MS
            this.interpolator = interpolator
        }
        val scaleYAnimator = ObjectAnimator.ofFloat(card, View.SCALE_Y, 0.96f, 1f).apply {
            duration = CARD_ANIMATION_MS
            this.interpolator = interpolator
        }

        showAnimator = AnimatorSet().apply {
            playTogether(scrimAnimator, alphaAnimator, scaleXAnimator, scaleYAnimator)
            start()
        }
    }

    private fun removeOverlay(root: FrameLayout) {
        if (rootView !== root) return

        showAnimator?.cancel()
        showAnimator = null
        val runningDismissAnimator = dismissAnimator
        dismissAnimator = null
        runningDismissAnimator?.removeAllListeners()
        runningDismissAnimator?.cancel()

        mainHandler.removeCallbacks(inputGuardEndRunnable)

        val shouldRemoveFromWindow = addedToWindowManager
        addedToWindowManager = false
        if (shouldRemoveFromWindow) {
            try {
                windowManager.removeView(root)
            } catch (_: Exception) {
                // The window may already have been removed by the system.
            }
        }

        rootView = null
        cardView = null
        contentFrame = null
        contentScrollView = null
        captionView = null
        closeButton = null
        continueButton = null
        currentModel = null
        currentCallbacks = null
        inputGuardUntilElapsedRealtimeMs = 0L
        dismissInProgress = false
    }

    private fun updateCard(model: Model) {
        captionView?.text = "${model.minutes} min on ${model.appLabel}"
        closeButton?.text = "Close ${model.appLabel}"
        closeButton?.contentDescription = "Close ${model.appLabel}"
        continueButton?.contentDescription = continueButton?.text?.toString() ?: "Continue"
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

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

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
