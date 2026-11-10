package com.appsense.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TicTacToeEngineTest {

    private val winningLines = listOf(
        listOf(0, 1, 2),
        listOf(3, 4, 5),
        listOf(6, 7, 8),
        listOf(0, 3, 6),
        listOf(1, 4, 7),
        listOf(2, 5, 8),
        listOf(0, 4, 8),
        listOf(2, 4, 6)
    )

    @Test
    fun allEightWinningLinesForX() {
        winningLines.forEach { line ->
            val board = MutableList<TicTacToeEngine.Mark?>(9) { null }

            line.forEach {
                board[it] = TicTacToeEngine.Mark.X
            }

            assertEquals(
                line,
                TicTacToeEngine.findWinningLine(
                    board,
                    TicTacToeEngine.Mark.X
                )
            )
        }
    }

    @Test
    fun allEightWinningLinesForO() {
        winningLines.forEach { line ->
            val board = MutableList<TicTacToeEngine.Mark?>(9) { null }

            line.forEach {
                board[it] = TicTacToeEngine.Mark.O
            }

            assertEquals(
                line,
                TicTacToeEngine.findWinningLine(
                    board,
                    TicTacToeEngine.Mark.O
                )
            )
        }
    }

    @Test
    fun detectsDraw() {
        val engine = TicTacToeEngine()

        val moves = listOf(
            0, 1,
            2, 4,
            3, 5,
            7, 6,
            8
        )

        moves.forEach {
            assertTrue(engine.play(it))
        }

        assertEquals(
            TicTacToeEngine.Result.DRAW,
            engine.result
        )

        assertNull(engine.winningLine)
    }

    @Test
    fun occupiedCellIsIgnored() {
        val engine = TicTacToeEngine()

        assertTrue(engine.play(0))
        assertFalse(engine.play(0))

        assertEquals(
            TicTacToeEngine.Mark.X,
            engine.cells[0]
        )
    }

    @Test
    fun opponentWinsWhenPossible() {
        val engine = TicTacToeEngine()

        engine.play(0)
        engine.play(3)
        engine.play(1)
        engine.play(4)
        engine.play(8)

        val opponentMove = engine.playOpponentMove()

        assertEquals(5, opponentMove)

        assertEquals(
            TicTacToeEngine.Result.O_WINS,
            engine.result
        )
    }

    @Test
    fun opponentBlocksPlayersWinningMove() {
        val engine = TicTacToeEngine()

        engine.play(0)
        engine.play(4)
        engine.play(1)

        val opponentMove = engine.playOpponentMove()

        assertEquals(2, opponentMove)

        assertEquals(
            TicTacToeEngine.Mark.O,
            engine.cells[2]
        )
    }

    @Test
    fun opponentNeverPlaysOccupiedCell() {
        val engine = TicTacToeEngine()

        engine.play(0)
        engine.play(4)
        engine.play(1)

        val occupiedBefore = engine.cells

        val opponentMove = engine.playOpponentMove()

        assertNotNull(opponentMove)
        assertNull(occupiedBefore[opponentMove!!])
        assertEquals(
            TicTacToeEngine.Mark.O,
            engine.cells[opponentMove]
        )
    }

    @Test
    fun noMovesAfterGameCompletion() {
        val engine = TicTacToeEngine()

        engine.play(0)
        engine.play(3)
        engine.play(1)
        engine.play(4)
        engine.play(2)

        assertEquals(
            TicTacToeEngine.Result.X_WINS,
            engine.result
        )

        assertFalse(engine.play(5))
        assertNull(engine.playOpponentMove())
    }
    @Test
    fun opponentMoveMatchesOriginalLogicForAllReachableBoardsAndSeeds() {
        val totalBoards = 3 * 3 * 3 * 3 * 3 * 3 * 3 * 3 * 3

        for (encoded in 0 until totalBoards) {
            var value = encoded
            val board = MutableList<TicTacToeEngine.Mark?>(9) {
                val mark = when (value % 3) {
                    1 -> TicTacToeEngine.Mark.X
                    2 -> TicTacToeEngine.Mark.O
                    else -> null
                }
                value /= 3
                mark
            }

            val xCount = board.count { it == TicTacToeEngine.Mark.X }
            val oCount = board.count { it == TicTacToeEngine.Mark.O }

            if (xCount != oCount + 1) continue

            if (TicTacToeEngine.findWinningLine(
                    board,
                    TicTacToeEngine.Mark.X
                ) != null
            ) continue

            if (TicTacToeEngine.findWinningLine(
                    board,
                    TicTacToeEngine.Mark.O
                ) != null
            ) continue

            for (seed in 0..9) {
                val engine = TicTacToeEngine(Random(seed))

                val xIndices = board.indices.filter {
                    board[it] == TicTacToeEngine.Mark.X
                }

                val oIndices = board.indices.filter {
                    board[it] == TicTacToeEngine.Mark.O
                }

                for (index in oIndices.indices) {
                    assertTrue(engine.play(xIndices[index]))
                    assertTrue(engine.play(oIndices[index]))
                }

// X always has one extra move.
                assertTrue(engine.play(xIndices.last()))

                val expected = originalOpponentMove(
                    board,
                    Random(seed)
                )

                assertEquals(
                    "Mismatch for board=$board, seed=$seed",
                    expected,
                    engine.playOpponentMove()
                )
            }
        }
    }

    @Test
    fun opponentPrefersCenterWhenFree() {
        val engine = TicTacToeEngine()

        assertTrue(engine.play(0))

        assertEquals(4, engine.playOpponentMove())
    }

    @Test
    fun opponentChoosesCornerWhenCenterIsTaken() {
        val engine = TicTacToeEngine()

        assertTrue(engine.play(4))

        val move = engine.playOpponentMove()

        assertTrue(move in listOf(0, 2, 6, 8))
    }

    @Test
    fun opponentChoosesSideWhenNoCornerIsFree() {
        val engine = TicTacToeEngine()

        // All four corners and the center are occupied.
        assertTrue(engine.play(0))
        assertTrue(engine.play(4))
        assertTrue(engine.play(2))
        assertTrue(engine.play(8))
        assertTrue(engine.play(6))

        val move = engine.playOpponentMove()

        assertTrue(move in listOf(1, 3, 5, 7))
    }

    private fun originalOpponentMove(
        board: List<TicTacToeEngine.Mark?>,
        random: Random
    ): Int? {
        fun findWinningMove(
            mark: TicTacToeEngine.Mark
        ): Int? {
            for (index in board.indices) {
                if (board[index] != null) continue

                val testBoard = board.toMutableList()
                testBoard[index] = mark

                if (TicTacToeEngine.findWinningLine(testBoard, mark) != null) {
                    return index
                }
            }

            return null
        }

        return findWinningMove(TicTacToeEngine.Mark.O)
            ?: findWinningMove(TicTacToeEngine.Mark.X)
            ?: if (board[4] == null) 4 else null
                ?: listOf(0, 2, 6, 8)
                    .filter { board[it] == null }
                    .shuffled(random)
                    .firstOrNull()
                ?: listOf(1, 3, 5, 7)
                    .filter { board[it] == null }
                    .shuffled(random)
                    .firstOrNull()
    }
    @Test
    fun humanCannotPlayDuringOpponentTurn() {
        val engine = TicTacToeEngine()

        assertTrue(engine.playHumanMove(0))

        assertEquals(
            TicTacToeEngine.Mark.O,
            engine.currentTurn
        )

        val boardBefore = engine.cells

        assertFalse(engine.playHumanMove(1))

        assertEquals(boardBefore, engine.cells)

        assertEquals(
            TicTacToeEngine.Mark.O,
            engine.currentTurn
        )

        assertNotNull(engine.playOpponentMove())

        assertEquals(
            TicTacToeEngine.Mark.X,
            engine.currentTurn
        )

        assertTrue(engine.playHumanMove(1))
    }
}