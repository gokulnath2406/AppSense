package com.appsense.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.animation.LinearInterpolator
import kotlin.math.floor
import kotlin.math.max
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
    private var currentStatus = "Your move"
    private var lastStatusAnnouncementAt = Long.MIN_VALUE
    private var lastAnnouncedStatus: String? = null
    private var accessibilityFocusedVirtualViewId = INVALID_VIRTUAL_VIEW_ID
    private var hoveredVirtualViewId = INVALID_VIRTUAL_VIEW_ID

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
        textSize = 14f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val accessibilityNodeProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == HOST_VIEW_ID) {
                return AccessibilityNodeInfo.obtain(this@TicTacToeView).apply {
                    className = "android.widget.GridLayout"
                    contentDescription = boardContentDescription()
                    for (index in 0..8) addChild(this@TicTacToeView, index)
                    if (engine.result != TicTacToeEngine.Result.IN_PROGRESS) {
                        addChild(this@TicTacToeView, PLAY_AGAIN_VIRTUAL_ID)
                    }
                }
            }

            val layout = getBoardLayout() ?: return null
            if (virtualViewId in 0..8) {
                val bounds = cellBounds(virtualViewId, layout)
                val mark = engine.cells[virtualViewId]
                val state = when (mark) {
                    TicTacToeEngine.Mark.X -> "X"
                    TicTacToeEngine.Mark.O -> "O"
                    null -> "empty"
                }
                val enabled = !isInputGuardActive() &&
                        engine.result == TicTacToeEngine.Result.IN_PROGRESS && mark == null

                return AccessibilityNodeInfo.obtain().apply {
                    setSource(this@TicTacToeView, virtualViewId)
                    setParent(this@TicTacToeView)
                    packageName = context.packageName
                    className = "android.widget.Button"
                    contentDescription = "Row ${virtualViewId / 3 + 1}, column ${virtualViewId % 3 + 1}, $state"
                    setBoundsInParent(bounds)
                    val screenLocation = IntArray(2)
                    this@TicTacToeView.getLocationOnScreen(screenLocation)
                    bounds.offset(screenLocation[0], screenLocation[1])
                    setBoundsInScreen(bounds)
                    isFocusable = true
                    isClickable = enabled
                    isEnabled = enabled
                    if (enabled) addAction(AccessibilityNodeInfo.ACTION_CLICK)
                    setAccessibilityFocused(accessibilityFocusedVirtualViewId == virtualViewId)
                }
            }

            if (
                virtualViewId == PLAY_AGAIN_VIRTUAL_ID &&
                engine.result != TicTacToeEngine.Result.IN_PROGRESS
            ) {
                return AccessibilityNodeInfo.obtain().apply {
                    setSource(this@TicTacToeView, virtualViewId)
                    setParent(this@TicTacToeView)
                    packageName = context.packageName
                    className = "android.widget.Button"
                    contentDescription = "Play again"
                    val bounds = Rect(
                        layout.playAgainRect.left.toInt(),
                        layout.playAgainRect.top.toInt(),
                        layout.playAgainRect.right.toInt(),
                        layout.playAgainRect.bottom.toInt()
                    )
                    setBoundsInParent(bounds)
                    val screenLocation = IntArray(2)
                    this@TicTacToeView.getLocationOnScreen(screenLocation)
                    bounds.offset(screenLocation[0], screenLocation[1])
                    setBoundsInScreen(bounds)
                    isFocusable = true
                    isClickable = !isInputGuardActive()
                    isEnabled = !isInputGuardActive()
                    if (!isInputGuardActive()) addAction(AccessibilityNodeInfo.ACTION_CLICK)
                    setAccessibilityFocused(accessibilityFocusedVirtualViewId == virtualViewId)
                }
            }
            return null
        }

        override fun performAction(
            virtualViewId: Int,
            action: Int,
            arguments: android.os.Bundle?
        ): Boolean {
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                if (virtualViewId !in 0..8 && virtualViewId != PLAY_AGAIN_VIRTUAL_ID) return false
                accessibilityFocusedVirtualViewId = virtualViewId
                sendVirtualViewEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                return true
            }
            if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                if (accessibilityFocusedVirtualViewId != virtualViewId) return false
                accessibilityFocusedVirtualViewId = INVALID_VIRTUAL_VIEW_ID
                sendVirtualViewEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                return true
            }
            if (action != AccessibilityNodeInfo.ACTION_CLICK || isInputGuardActive()) return false

            return when (virtualViewId) {
                in 0..8 -> playCell(virtualViewId)
                PLAY_AGAIN_VIRTUAL_ID -> {
                    if (engine.result == TicTacToeEngine.Result.IN_PROGRESS) false
                    else {
                        resetGame()
                        true
                    }
                }
                else -> false
            }
        }
    }

    private val opponentRunnable = Runnable {
        if (inputBlocked || engine.result != TicTacToeEngine.Result.IN_PROGRESS) {
            return@Runnable
        }

        engine.playOpponentMove()
        when (engine.result) {
            TicTacToeEngine.Result.O_WINS -> {
                updateStatus("I win this one")
                animateWinningLine()
            }
            TicTacToeEngine.Result.DRAW -> updateStatus("Draw")
            else -> updateStatus("Your move")
        }
        invalidate()
        notifyAccessibilityContentChanged()
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Tic-tac-toe board"
        onStatusChanged(currentStatus)
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider =
        accessibilityNodeProvider

    override fun setInputGuardUntilElapsedRealtimeMs(untilMs: Long) {
        inputGuardUntilElapsedRealtimeMs = untilMs
        handler.removeCallbacks(inputGuardRefreshRunnable)
        val remaining = (untilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        handler.postDelayed(inputGuardRefreshRunnable, remaining)
        invalidate()
        notifyAccessibilityContentChanged()
    }

    override fun setInputBlocked(blocked: Boolean) {
        inputBlocked = blocked
        if (blocked) handler.removeCallbacks(opponentRunnable)
        invalidate()
        notifyAccessibilityContentChanged()
    }

    private fun isInputGuardActive(): Boolean =
        inputBlocked || SystemClock.elapsedRealtime() < inputGuardUntilElapsedRealtimeMs

    private fun dp(value: Float): Float = value * density

    private fun getBoardLayout(): BoardLayout? {
        if (width <= 0 || height <= 0) return null

        val buttonHeight = dp(48f)
        val verticalGap = dp(10f)
        val availableBoardHeight = height - buttonHeight - verticalGap - 2f * gap
        val calculatedCellSize = min(
            min((width - 2f * gap) / 3f, dp(96f)),
            availableBoardHeight / 3f
        )
        val cellSize = max(dp(48f), calculatedCellSize)
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

    private fun cellBounds(index: Int, layout: BoardLayout): Rect {
        val row = index / 3
        val column = index % 3
        val left = layout.left + column * (layout.cellSize + layout.gap)
        val top = layout.top + row * (layout.cellSize + layout.gap)
        return Rect(
            left.toInt(),
            top.toInt(),
            (left + layout.cellSize).toInt(),
            (top + layout.cellSize).toInt()
        )
    }

    private fun boardContentDescription(): String {
        val cells = (0..8).joinToString("; ") { index ->
            val state = when (engine.cells[index]) {
                TicTacToeEngine.Mark.X -> "X"
                TicTacToeEngine.Mark.O -> "O"
                null -> "empty"
            }
            "Row ${index / 3 + 1}, column ${index % 3 + 1}, $state"
        }
        return "Tic-tac-toe board. $currentStatus. $cells"
    }

    private fun updateStatus(status: String) {
        currentStatus = status
        onStatusChanged(status)
        contentDescription = boardContentDescription()

        val now = SystemClock.elapsedRealtime()
        if (
            lastAnnouncedStatus != status &&
            (lastStatusAnnouncementAt == Long.MIN_VALUE ||
                    now - lastStatusAnnouncementAt >= ACCESSIBILITY_ANNOUNCEMENT_INTERVAL_MS)
        ) {
            lastAnnouncedStatus = status
            lastStatusAnnouncementAt = now
            if (isAttachedToWindow) announceForAccessibility(status)
        }
        notifyAccessibilityContentChanged()
    }

    private fun notifyAccessibilityContentChanged() {
        if (isAttachedToWindow) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    private fun sendVirtualViewEvent(virtualViewId: Int, eventType: Int) {
        val event = AccessibilityEvent.obtain(eventType)
        event.packageName = context.packageName
        event.className = "android.widget.Button"
        event.setSource(this, virtualViewId)
        event.contentDescription = if (virtualViewId == PLAY_AGAIN_VIRTUAL_ID) {
            "Play again"
        } else {
            val state = when (engine.cells[virtualViewId]) {
                TicTacToeEngine.Mark.X -> "X"
                TicTacToeEngine.Mark.O -> "O"
                null -> "empty"
            }
            "Row ${virtualViewId / 3 + 1}, column ${virtualViewId % 3 + 1}, $state"
        }
        parent?.requestSendAccessibilityEvent(this, event)
    }

    private fun virtualViewAt(x: Float, y: Float): Int {
        val layout = getBoardLayout() ?: return INVALID_VIRTUAL_VIEW_ID
        if (engine.result != TicTacToeEngine.Result.IN_PROGRESS &&
            layout.playAgainRect.contains(x, y)
        ) return PLAY_AGAIN_VIRTUAL_ID

        for (index in 0..8) {
            if (cellBounds(index, layout).contains(x.toInt(), y.toInt())) return index
        }
        return INVALID_VIRTUAL_VIEW_ID
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (accessibilityManager?.isTouchExplorationEnabled == true) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_HOVER_MOVE -> {
                    val virtualViewId = virtualViewAt(event.x, event.y)
                    if (virtualViewId != hoveredVirtualViewId) {
                        if (hoveredVirtualViewId != INVALID_VIRTUAL_VIEW_ID) {
                            sendVirtualViewEvent(
                                hoveredVirtualViewId,
                                AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
                            )
                        }
                        hoveredVirtualViewId = virtualViewId
                        if (virtualViewId != INVALID_VIRTUAL_VIEW_ID) {
                            sendVirtualViewEvent(
                                virtualViewId,
                                AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
                            )
                        }
                    }
                    return virtualViewId != INVALID_VIRTUAL_VIEW_ID
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    if (hoveredVirtualViewId != INVALID_VIRTUAL_VIEW_ID) {
                        sendVirtualViewEvent(
                            hoveredVirtualViewId,
                            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
                        )
                        hoveredVirtualViewId = INVALID_VIRTUAL_VIEW_ID
                        return true
                    }
                }
            }
        }
        return super.dispatchHoverEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = getBoardLayout() ?: return
        val cells = engine.cells

        for (index in 0..8) {
            val rect = RectF(cellBounds(index, layout))
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
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
            val baseline = layout.playAgainRect.centerY() -
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
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, opponentPaint)
    }

    private fun drawWinningLine(canvas: Canvas, layout: BoardLayout) {
        val line = engine.winningLine ?: return
        val first = cellCenter(line.first(), layout)
        val last = cellCenter(line.last(), layout)
        canvas.drawLine(
            first.first,
            first.second,
            first.first + (last.first - first.first) * winningProgress,
            first.second + (last.second - first.second) * winningProgress,
            winningPaint
        )
    }

    private fun cellCenter(index: Int, layout: BoardLayout): Pair<Float, Float> {
        val row = index / 3
        val column = index % 3
        return Pair(
            layout.left + column * (layout.cellSize + layout.gap) + layout.cellSize / 2f,
            layout.top + row * (layout.cellSize + layout.gap) + layout.cellSize / 2f
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isInputGuardActive()) return true
        if (event.actionMasked != MotionEvent.ACTION_UP) return true

        val layout = getBoardLayout() ?: return true
        if (engine.result != TicTacToeEngine.Result.IN_PROGRESS) {
            if (layout.playAgainRect.contains(event.x, event.y)) resetGame()
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
        if (insideCellX > layout.cellSize || insideCellY > layout.cellSize) return true

        playCell(row * 3 + column)
        return true
    }

    private fun playCell(index: Int): Boolean {
        if (isInputGuardActive() || !engine.play(index)) return false

        if (!firstMoveLogged) {
            firstMoveLogged = true
            onFirstMove()
        }

        when (engine.result) {
            TicTacToeEngine.Result.X_WINS -> {
                updateStatus("You win")
                animateWinningLine()
            }
            TicTacToeEngine.Result.DRAW -> updateStatus("Draw")
            else -> {
                updateStatus("My move...")
                handler.removeCallbacks(opponentRunnable)
                handler.postDelayed(opponentRunnable, 350L)
            }
        }
        invalidate()
        notifyAccessibilityContentChanged()
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
        updateStatus("Your move")
        invalidate()
        notifyAccessibilityContentChanged()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateStatus(currentStatus)
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

    private companion object {
        const val PLAY_AGAIN_VIRTUAL_ID = 9
        const val INVALID_VIRTUAL_VIEW_ID = -1
        const val ACCESSIBILITY_ANNOUNCEMENT_INTERVAL_MS = 2_000L
    }
}
