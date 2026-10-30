package com.appsense.app

import org.junit.Assert.*
import org.junit.Test

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
}