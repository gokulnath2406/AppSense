package com.appsense.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.min
import kotlin.math.roundToInt

class BreathingView(
    context: Context,
    private val onFinished: (completedFully: Boolean) -> Unit
) : View(context), InterventionInputGuard {

    private enum class Phase {
        INHALE,
        EXHALE,
        FINISHED
    }

    private val density = resources.displayMetrics.density
    private val phasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = InterventionTheme.BREATHING_PHASE_TEXT_COLOR
        textSize = InterventionTheme.BREATHING_PHASE_TEXT_SIZE_SP * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.NORMAL
        )
    }
    private val countdownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = InterventionTheme.BREATHING_COUNTDOWN_TEXT_COLOR
        textSize = InterventionTheme.BREATHING_COUNTDOWN_TEXT_SIZE_SP * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val skipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = InterventionTheme.SECONDARY_TEXT_COLOR
        textSize = InterventionTheme.BREATHING_SKIP_TEXT_SIZE_SP * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = InterventionTheme.BREATHING_ACCENT_COLOR
        alpha = InterventionTheme.BREATHING_CIRCLE_ALPHA
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = InterventionTheme.BREATHING_ACCENT_COLOR
        style = Paint.Style.STROKE
        strokeWidth = InterventionTheme.BREATHING_RING_WIDTH_DP * density
    }

    private var phase = Phase.INHALE
    private var phaseScale = InterventionTheme.BREATHING_MIN_SCALE
    private var phaseStartedAt = 0L
    private var phaseDurationMs = 4_000L
    private var completedCycles = 0
    private var hasStarted = false
    private var detached = false
    private var callbackSent = false
    private var animationDisabled = false
    private var preparationComplete = false
    private var phaseAnimator: ValueAnimator? = null
    private var skipBounds = RectF()
    private var skipVisible = false
    private var inputGuardUntilElapsedRealtimeMs = 0L
    private var inputBlocked = false
    private val inputGuardRefreshRunnable = Runnable { invalidate() }

    private val startBreathingAfterPreparation = Runnable {
        if (detached || phase == Phase.FINISHED) return@Runnable
        preparationComplete = true
        phase = Phase.INHALE
        phaseScale = InterventionTheme.BREATHING_MIN_SCALE
        phaseStartedAt = SystemClock.elapsedRealtime()
        phaseDurationMs = 4_000L
        if (animationDisabled) {
            post(fallbackTick)
        } else {
            startPhaseAnimator()
        }
        invalidate()
    }

    private val fallbackTick = object : Runnable {
        override fun run() {
            if (detached || phase == Phase.FINISHED) return
            advanceFallbackPhaseIfNeeded()
            invalidate()
            if (!detached && phase != Phase.FINISHED) {
                postDelayed(this, 200L)
            }
        }
    }

    override fun setInputGuardUntilElapsedRealtimeMs(untilMs: Long) {
        inputGuardUntilElapsedRealtimeMs = untilMs
        removeCallbacks(inputGuardRefreshRunnable)
        val remaining = (untilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        postDelayed(inputGuardRefreshRunnable, remaining)
        invalidate()
    }

    override fun setInputBlocked(blocked: Boolean) {
        inputBlocked = blocked
        invalidate()
    }

    private fun isInputGuardActive(): Boolean =
        inputBlocked || SystemClock.elapsedRealtime() < inputGuardUntilElapsedRealtimeMs

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Breathing exercise"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detached = false
        if (!hasStarted) {
            hasStarted = true
            animationDisabled = try {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                ) == 0f
            } catch (_: Exception) {
                false
            }
            preparationComplete = false
            phase = Phase.INHALE
            phaseScale = InterventionTheme.BREATHING_MIN_SCALE
            postDelayed(startBreathingAfterPreparation, 3_000L)
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        detached = true
        phaseAnimator?.removeAllUpdateListeners()
        phaseAnimator?.removeAllListeners()
        phaseAnimator?.cancel()
        phaseAnimator = null
        removeCallbacks(fallbackTick)
        removeCallbacks(startBreathingAfterPreparation)
        removeCallbacks(inputGuardRefreshRunnable)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (300f * density).roundToInt()
        val desiredHeight = (InterventionTheme.BREATHING_CONTENT_HEIGHT_DP * density).roundToInt()
        val measuredWidth = resolveSize(desiredWidth, widthMeasureSpec)
        val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (phase == Phase.FINISHED) {
            phasePaint.textSize = InterventionTheme.BREATHING_FINISHED_TEXT_SIZE_SP * resources.displayMetrics.scaledDensity
            canvas.drawText(
                "Nice. Take your time.",
                width / 2f,
                height / 2f,
                phasePaint
            )
            return
        }

        if (!preparationComplete) {
            val centerX = width / 2f
            val centerY = height * 0.32f
            val maxRadius = min(width * 0.23f, height * 0.23f)
            canvas.drawCircle(centerX, centerY, maxRadius * InterventionTheme.BREATHING_MIN_SCALE, fillPaint)
            canvas.drawCircle(centerX, centerY, maxRadius * InterventionTheme.BREATHING_MIN_SCALE, ringPaint)
            phasePaint.textSize = InterventionTheme.BREATHING_PHASE_TEXT_SIZE_SP * resources.displayMetrics.scaledDensity
            canvas.drawText(
                "Get ready to relax",
                centerX,
                centerY + maxRadius + 42f * density,
                phasePaint
            )
            return
        }

        val centerX = width / 2f
        val centerY = height * 0.32f
        val maxRadius = min(width * 0.23f, height * 0.23f)
        val radius = maxRadius * phaseScale
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        canvas.drawCircle(centerX, centerY, radius, ringPaint)

        val labelY = centerY + maxRadius + 42f * density
        canvas.drawText(
            if (phase == Phase.INHALE) "Breathe in" else "Breathe out",
            centerX,
            labelY,
            phasePaint
        )

        val remainingMs = (phaseDurationMs - (SystemClock.elapsedRealtime() - phaseStartedAt)).coerceAtLeast(0L)
        val remainingSeconds = ((remainingMs + 999L) / 1000L).toInt()
        canvas.drawText(
            remainingSeconds.toString(),
            centerX,
            labelY + 28f * density,
            countdownPaint
        )

        skipVisible = completedCycles >= 1
        if (skipVisible) {
            val skipY = height - 12f * density
            skipBounds.set(
                centerX - 52f * density,
                skipY - 30f * density,
                centerX + 52f * density,
                skipY + 8f * density
            )
            skipPaint.alpha = if (isInputGuardActive()) 153 else 255
            canvas.drawText("Skip", centerX, skipY, skipPaint)
            skipPaint.alpha = 255
        } else {
            skipBounds.setEmpty()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isInputGuardActive()) return true
        if (event.actionMasked == MotionEvent.ACTION_UP && skipVisible &&
            skipBounds.contains(event.x, event.y)
        ) {
            finishExercise(completedFully = false)
            performClick()
            return true
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_UP -> {
                performClick()
                true
            }
            MotionEvent.ACTION_CANCEL -> true
            else -> true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun startPhaseAnimator() {
        if (detached || phase == Phase.FINISHED) return
        val from = if (phase == Phase.INHALE) {
            InterventionTheme.BREATHING_MIN_SCALE
        } else {
            InterventionTheme.BREATHING_MAX_SCALE
        }
        val to = if (phase == Phase.INHALE) {
            InterventionTheme.BREATHING_MAX_SCALE
        } else {
            InterventionTheme.BREATHING_MIN_SCALE
        }
        phaseScale = from
        phaseStartedAt = SystemClock.elapsedRealtime()
        phaseDurationMs = if (phase == Phase.INHALE) 4_000L else 6_000L
        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = phaseDurationMs
            interpolator = if (phase == Phase.INHALE) {
                DecelerateInterpolator()
            } else {
                AccelerateDecelerateInterpolator()
            }
            addUpdateListener {
                if (detached || phase == Phase.FINISHED) return@addUpdateListener
                phaseScale = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || detached || phase == Phase.FINISHED) return
                    if (phase == Phase.INHALE) {
                        phase = Phase.EXHALE
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        startPhaseAnimator()
                    } else {
                        completedCycles++
                        if (completedCycles >= 3) {
                            finishExercise(completedFully = true)
                        } else {
                            phase = Phase.INHALE
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            startPhaseAnimator()
                        }
                    }
                }
            })
        }
        phaseAnimator?.cancel()
        phaseAnimator = animator
        animator.start()
    }

    private fun advanceFallbackPhaseIfNeeded() {
        if (detached || phase == Phase.FINISHED) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - phaseStartedAt
        val fraction = (elapsed.toFloat() / phaseDurationMs.toFloat()).coerceIn(0f, 1f)
        phaseScale = if (phase == Phase.INHALE) {
            InterventionTheme.BREATHING_MIN_SCALE +
                    (InterventionTheme.BREATHING_MAX_SCALE - InterventionTheme.BREATHING_MIN_SCALE) * fraction
        } else {
            InterventionTheme.BREATHING_MAX_SCALE -
                    (InterventionTheme.BREATHING_MAX_SCALE - InterventionTheme.BREATHING_MIN_SCALE) * fraction
        }

        if (elapsed < phaseDurationMs) return
        if (phase == Phase.INHALE) {
            phase = Phase.EXHALE
            phaseStartedAt = now
            phaseDurationMs = 6_000L
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        } else {
            completedCycles++
            if (completedCycles >= 3) {
                finishExercise(completedFully = true)
                return
            }
            phase = Phase.INHALE
            phaseStartedAt = now
            phaseDurationMs = 4_000L
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun finishExercise(completedFully: Boolean) {
        if (detached || phase == Phase.FINISHED || callbackSent || inputBlocked) return
        phase = Phase.FINISHED
        callbackSent = true
        phaseAnimator?.removeAllUpdateListeners()
        phaseAnimator?.removeAllListeners()
        phaseAnimator?.cancel()
        phaseAnimator = null
        removeCallbacks(fallbackTick)
        invalidate()
        if (!detached) {
            onFinished(completedFully)
        }
    }
}

