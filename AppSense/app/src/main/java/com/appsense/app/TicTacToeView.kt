package com.appsense.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.floor
import kotlin.math.min

class TicTacToeView(
    context: Context,
    private val onFirstMove: () -> Unit,
    private val onStatusChanged: (String) -> Unit
) : View(context), InterventionInputGuard {

    private val engine = TicTacToeEngine()
    private val handler = Handler(Looper.getMainLooper())

    private val density = resources.displayMetrics.density

    private val gap = dp(8f)
    private val cornerRadius = dp(14f)

    private var firstMoveLogged = false
    private var winningProgress = 1f

    private var winningAnimator: ValueAnimator? = null
    private var inputGuardUntilElapsedRealtimeMs = 0L
    private var inputBlocked = false
    private val inputGuardRefreshRunnable = Runnable { invalidate() }

    private data class BoardLayout(
        val left: Float,
        val top: Float,
        val cellSize: Float,
        val gap: Float,
        val boardWidth: Float,
        val boardHeight: Float,
        val playAgainRect: RectF
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(20, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = InterventionTheme.PRIMARY_COLOR
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    private val opponentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(4f)
        style = Paint.Style.STROKE
    }

    private val winningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = InterventionTheme.PRIMARY_COLOR
        strokeWidth = dp(6f)
        strokeCap = Paint.Cap.ROUND
    }

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = InterventionTheme.PRIMARY_COLOR
    }

    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = InterventionTheme.PRIMARY_TEXT_COLOR
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val opponentRunnable = Runnable {
        if (inputBlocked || engine.result != TicTacToeEngine.Result.IN_PROGRESS) {
            return@Runnable
        }

        engine.playOpponentMove()

        when (engine.result) {
            TicTacToeEngine.Result.O_WINS -> {
                onStatusChanged("I win this one")
                animateWinningLine()
            }

            TicTacToeEngine.Result.DRAW -> {
                onStatusChanged("Draw")
            }

            else -> {
                onStatusChanged("Your move")
            }
        }

        invalidate()
    }

    override fun setInputGuardUntilElapsedRealtimeMs(untilMs: Long) {
        inputGuardUntilElapsedRealtimeMs = untilMs
        handler.removeCallbacks(inputGuardRefreshRunnable)
        val remaining = (untilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        handler.postDelayed(inputGuardRefreshRunnable, remaining)
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
        onStatusChanged("Your move")
    }

    private fun dp(value: Float): Float = value * density

    private fun getBoardLayout(): BoardLayout? {
        if (width <= 0 || height <= 0) return null

        val buttonHeight = dp(36f)
        val verticalGap = dp(10f)

        val availableBoardHeight =
            height - buttonHeight - verticalGap - 2f * gap

        val cellSize = min(
            min(
                (width - 2f * gap) / 3f,
                dp(96f)
            ),
            availableBoardHeight / 3f
        )

        if (cellSize <= 0f) return null

        val boardWidth = cellSize * 3f + gap * 2f
        val boardHeight = boardWidth

        val totalHeight = boardHeight + verticalGap + buttonHeight
        val top = (height - totalHeight) / 2f
        val left = (width - boardWidth) / 2f

        return BoardLayout(
            left = left,
            top = top,
            cellSize = cellSize,
            gap = gap,
            boardWidth = boardWidth,
            boardHeight = boardHeight,
            playAgainRect = RectF(
                left,
                top + boardHeight + verticalGap,
                left + boardWidth,
                top + boardHeight + verticalGap + buttonHeight
            )
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val layout = getBoardLayout() ?: return

        val cells = engine.cells

        for (index in 0..8) {
            val row = index / 3
            val column = index % 3

            val left =
                layout.left + column * (layout.cellSize + layout.gap)

            val top =
                layout.top + row * (layout.cellSize + layout.gap)

            val rect = RectF(
                left,
                top,
                left + layout.cellSize,
                top + layout.cellSize
            )

            canvas.drawRoundRect(
                rect,
                cornerRadius,
                cornerRadius,
                fillPaint
            )

            when (cells[index]) {
                TicTacToeEngine.Mark.X -> drawX(canvas, rect)
                TicTacToeEngine.Mark.O -> drawO(canvas, rect)
                null -> Unit
            }
        }

        drawWinningLine(canvas, layout)

        if (engine.result != TicTacToeEngine.Result.IN_PROGRESS) {
            val controlAlpha = if (isInputGuardActive()) 153 else 255
            buttonPaint.alpha = controlAlpha
            buttonTextPaint.alpha = controlAlpha
            canvas.drawRoundRect(
                layout.playAgainRect,
                dp(10f),
                dp(10f),
                buttonPaint
            )

            val baseline =
                layout.playAgainRect.centerY() -
                        (buttonTextPaint.ascent() + buttonTextPaint.descent()) / 2f

            canvas.drawText(
                "Play again",
                layout.playAgainRect.centerX(),
                baseline,
                buttonTextPaint
            )
            buttonPaint.alpha = 255
            buttonTextPaint.alpha = 255
        }
    }

    private fun drawX(canvas: Canvas, rect: RectF) {
        val inset = rect.width() * 0.27f

        canvas.drawLine(
            rect.left + inset,
            rect.top + inset,
            rect.right - inset,
            rect.bottom - inset,
            linePaint
        )

        canvas.drawLine(
            rect.right - inset,
            rect.top + inset,
            rect.left + inset,
            rect.bottom - inset,
            linePaint
        )
    }

    private fun drawO(canvas: Canvas, rect: RectF) {
        val radius = rect.width() * 0.27f

        canvas.drawCircle(
            rect.centerX(),
            rect.centerY(),
            radius,
            opponentPaint
        )
    }

    private fun drawWinningLine(
        canvas: Canvas,
        layout: BoardLayout
    ) {
        val line = engine.winningLine ?: return

        val first = line.first()
        val last = line.last()

        val start = cellCenter(first, layout)
        val end = cellCenter(last, layout)

        canvas.drawLine(
            start.first,
            start.second,
            start.first + (end.first - start.first) * winningProgress,
            start.second + (end.second - start.second) * winningProgress,
            winningPaint
        )
    }

    private fun cellCenter(
        index: Int,
        layout: BoardLayout
    ): Pair<Float, Float> {
        val row = index / 3
        val column = index % 3

        return Pair(
            layout.left + column * (layout.cellSize + layout.gap) +
                    layout.cellSize / 2f,
            layout.top + row * (layout.cellSize + layout.gap) +
                    layout.cellSize / 2f
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isInputGuardActive()) return true
        if (event.actionMasked != MotionEvent.ACTION_UP) {
            return true
        }

        val layout = getBoardLayout() ?: return true

        if (engine.result != TicTacToeEngine.Result.IN_PROGRESS) {
            if (layout.playAgainRect.contains(event.x, event.y)) {
                resetGame()
            }
            return true
        }

        val relativeX = event.x - layout.left
        val relativeY = event.y - layout.top

        if (relativeX < 0 || relativeY < 0) return true

        val step = layout.cellSize + layout.gap

        val column = floor(relativeX / step).toInt()
        val row = floor(relativeY / step).toInt()

        if (row !in 0..2 || column !in 0..2) return true

        val insideCellX = relativeX - column * step
        val insideCellY = relativeY - row * step

        if (insideCellX > layout.cellSize ||
            insideCellY > layout.cellSize
        ) {
            return true
        }

        val index = row * 3 + column

        if (!engine.play(index)) return true

        if (!firstMoveLogged) {
            firstMoveLogged = true
            onFirstMove()
        }

        when (engine.result) {
            TicTacToeEngine.Result.X_WINS -> {
                onStatusChanged("You win")
                animateWinningLine()
            }

            TicTacToeEngine.Result.DRAW -> {
                onStatusChanged("Draw")
            }

            else -> {
                onStatusChanged("My move...")
                handler.removeCallbacks(opponentRunnable)
                handler.postDelayed(opponentRunnable, 350L)
            }
        }

        invalidate()
        return true
    }

    private fun animateWinningLine() {
        winningAnimator?.cancel()
        winningProgress = 0f

        winningAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            interpolator = LinearInterpolator()

            addUpdateListener {
                winningProgress = it.animatedValue as Float
                invalidate()
            }

            start()
        }
    }

    private fun resetGame() {
        handler.removeCallbacks(opponentRunnable)
        winningAnimator?.cancel()

        engine.reset()
        firstMoveLogged = false
        winningProgress = 1f

        onStatusChanged("Your move")
        invalidate()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(opponentRunnable)
        handler.removeCallbacks(inputGuardRefreshRunnable)
        winningAnimator?.removeAllUpdateListeners()
        winningAnimator?.removeAllListeners()
        winningAnimator?.cancel()
        winningAnimator = null
        super.onDetachedFromWindow()
    }
}
