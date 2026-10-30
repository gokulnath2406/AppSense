package com.appsense.app

import kotlin.random.Random

class TicTacToeEngine(
    private val random: Random = Random.Default
) {
    enum class Mark {
        X, O
    }

    enum class Result {
        IN_PROGRESS,
        X_WINS,
        O_WINS,
        DRAW
    }

    private val board = MutableList<Mark?>(9) { null }

    var currentTurn = Mark.X
        private set

    var result = Result.IN_PROGRESS
        private set

    var winningLine: List<Int>? = null
        private set

    val cells: List<Mark?>
        get() = board.toList()

    fun play(index: Int): Boolean {
        if (index !in 0..8) return false
        if (result != Result.IN_PROGRESS) return false
        if (board[index] != null) return false

        val mark = currentTurn
        board[index] = mark

        winningLine = findWinningLine(board, mark)

        when {
            winningLine != null -> {
                result = if (mark == Mark.X) {
                    Result.X_WINS
                } else {
                    Result.O_WINS
                }
            }

            board.none { it == null } -> {
                result = Result.DRAW
            }

            else -> {
                currentTurn = if (mark == Mark.X) Mark.O else Mark.X
            }
        }

        return true
    }

    fun playOpponentMove(): Int? {
        if (result != Result.IN_PROGRESS) return null
        if (currentTurn != Mark.O) return null

        val move =
            findWinningMove(Mark.O)
                ?: findWinningMove(Mark.X)
                ?: if (board[4] == null) 4 else null
                    ?: listOf(0, 2, 6, 8)
                        .filter { board[it] == null }
                        .shuffled(random)
                        .firstOrNull()
                    ?: listOf(1, 3, 5, 7)
                        .filter { board[it] == null }
                        .shuffled(random)
                        .firstOrNull()
                    ?: return null

        return if (play(move)) move else null
    }

    private fun findWinningMove(mark: Mark): Int? {
        for (index in board.indices) {
            if (board[index] != null) continue

            val testBoard = board.toMutableList()
            testBoard[index] = mark

            if (findWinningLine(testBoard, mark) != null) {
                return index
            }
        }

        return null
    }

    fun reset() {
        board.fill(null)
        currentTurn = Mark.X
        result = Result.IN_PROGRESS
        winningLine = null
    }

    companion object {
        private val WIN_LINES = listOf(
            listOf(0, 1, 2),
            listOf(3, 4, 5),
            listOf(6, 7, 8),
            listOf(0, 3, 6),
            listOf(1, 4, 7),
            listOf(2, 5, 8),
            listOf(0, 4, 8),
            listOf(2, 4, 6)
        )

        fun findWinningLine(
            cells: List<Mark?>,
            mark: Mark
        ): List<Int>? {
            if (cells.size != 9) return null

            return WIN_LINES.firstOrNull { line ->
                line.all { cells[it] == mark }
            }
        }
    }
}