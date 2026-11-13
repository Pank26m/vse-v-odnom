package com.pank.vsevodnom

import com.pank.vsevodnom.ui.games.CheckersEngine
import com.pank.vsevodnom.ui.games.CheckersEngine.parse
import com.pank.vsevodnom.ui.games.CheckersGame
import com.pank.vsevodnom.ui.games.CkMove
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CheckersTest {
    /** Доска из списка «поле=фигура»: w — белая простая, W — белая дамка, b/B — чёрные. */
    private fun board(vararg cells: String): IntArray {
        val b = IntArray(64)
        for (c in cells) {
            val (sq, p) = c.split("=")
            b[parse(sq)] = when (p) { "w" -> 1; "W" -> 2; "b" -> -1; "B" -> -2; else -> error(p) }
        }
        return b
    }
    private fun names(ms: List<CkMove>) = ms.map { it.toString() }.sorted()

    @Test fun initialPosition() {
        val b = CheckersEngine.start()
        assertEquals(12, CheckersEngine.DARK.count { b[it] == 1 })
        assertEquals(12, CheckersEngine.DARK.count { b[it] == -1 })
        val ms = CheckersEngine.moves(b, 1)
        assertEquals(7, ms.size, names(ms).toString())
        assertEquals(7, CheckersEngine.moves(b, -1).size)
        assertTrue(ms.none { it.isCapture })
    }

    @Test fun captureIsMandatory() {
        val b = board("c3=w", "g3=w", "d4=b", "h8=b")
        val ms = CheckersEngine.moves(b, 1)
        assertEquals(listOf("c3:e5"), names(ms))
        assertEquals(listOf(parse("d4")), ms[0].caps.toList())
        // правила большинства нет: можно бить и одну, и две
        val b2 = board("c3=w", "d4=b", "f6=b", "b4=b")
        val ms2 = CheckersEngine.moves(b2, 1)
        assertEquals(listOf("c3:a5", "c3:e5:g7"), names(ms2))
    }

    @Test fun manCapturesBackward() {
        val ms = CheckersEngine.moves(board("e5=w", "d4=b", "a7=b"), 1)
        assertEquals(listOf("e5:c3"), names(ms))
        // а тихо назад не ходит
        val q = CheckersEngine.moves(board("e5=w", "a7=b"), 1)
        assertEquals(listOf("e5-d6", "e5-f6"), names(q))
    }

    @Test fun multiJump() {
        val b = board("b2=w", "c3=b", "e5=b")
        val ms = CheckersEngine.moves(b, 1)
        assertEquals(listOf("b2:d4:f6"), names(ms))
        assertEquals(listOf(parse("c3"), parse("e5")), ms[0].caps.toList())
        val after = CheckersEngine.applied(b, ms[0])
        assertEquals(1, after[parse("f6")])
        assertEquals(0, after[parse("c3")]); assertEquals(0, after[parse("e5")]); assertEquals(0, after[parse("b2")])
    }

    @Test fun promotionDuringCaptureContinuesAsKing() {
        // b6:d8 — стала дамкой и бьёт f6 издалека (простая так не может)
        val b = board("b6=w", "c7=b", "f6=b")
        val ms = CheckersEngine.moves(b, 1)
        assertEquals(listOf("b6:d8:g5", "b6:d8:h4"), names(ms))
        assertTrue(ms.all { it.promo && it.caps.size == 2 })
        val after = CheckersEngine.applied(b, ms[0])
        assertEquals(2, after[ms[0].to])
        // простой ход на последний ряд — тоже дамка
        val q = CheckersEngine.moves(board("b6=w", "h4=b"), 1)
        assertTrue(q.isNotEmpty()); assertFalse(q.any { it.promo })
        val q2 = CheckersEngine.moves(board("c7=w", "h4=b"), 1)
        assertTrue(q2.all { it.promo })
    }

    @Test fun flyingKingLandingChoice() {
        // одно взятие: можно встать на любое поле за сбитой
        val b = board("a1=W", "d4=b")
        assertEquals(listOf("a1:e5", "a1:f6", "a1:g7", "a1:h8"), names(CheckersEngine.moves(b, 1)))
        // если с какого-то поля бой продолжается — вставать только туда
        val b2 = board("a1=W", "d4=b", "d6=b")
        val ms = CheckersEngine.moves(b2, 1)
        assertEquals(listOf("a1:e5:b8", "a1:e5:c7"), names(ms))
        assertTrue(ms.none { it.promo })
        // дамка ходит на любое расстояние
        val quiet = CheckersEngine.moves(board("a1=W", "h4=b"), 1)
        assertEquals(7, quiet.size)
    }

    @Test fun turkishStrike() {
        // после b2:f6:h4 бой через g3 приводит на f2, дальше по диагонали стоит уже сбитая d4 —
        // перепрыгнуть её второй раз нельзя, поэтому c5 не бьётся (сбитые снимаются после хода)
        val b = board("b2=W", "d4=b", "g5=b", "g3=b", "c5=b")
        val ms = CheckersEngine.moves(b, 1)
        assertEquals(listOf("b2:e5:h2", "b2:f6:h4:e1", "b2:f6:h4:f2"), names(ms))
        assertTrue(ms.none { parse("c5") in it.caps })
        // сбитые не повторяются
        assertTrue(ms.all { it.caps.toSet().size == it.caps.size })
        // без d4 на пути — c5 бьётся
        val b2 = board("f2=W", "c5=b")
        assertEquals(listOf("f2:a7", "f2:b6"), names(CheckersEngine.moves(b2, 1)))
    }

    @Test fun noMovesLoses() {
        assertEquals(null, CheckersGame().result())
        // чёрная простая b8 заперта: a7 бить некуда, за c7 занято — ходов нет, белые выиграли
        val g = CheckersGame()
        g.setup(board("b8=b", "a7=w", "c7=w", "d6=w"), -1)
        assertTrue(g.legal().isEmpty())
        assertEquals(1, g.result())
        // все шашки сбиты — тоже проигрыш
        g.setup(board("a1=W"), -1)
        assertEquals(1, g.result())
    }

    @Test fun repetitionDraw() {
        val g = CheckersGame()
        g.setup(board("a1=W", "h6=B"), 1)
        fun mv(n: String) { val m = g.legal().first { it.toString() == n }; g.play(m) }
        repeat(2) { mv("a1-b2"); mv("h6-g5"); mv("b2-a1"); mv("g5-h6") }
        assertEquals(0, g.result())
        assertNotNull(g.drawReason())
        assertEquals(8, g.quietKing)
        g.undo(1)
        assertEquals(null, g.result())
    }

    @Test fun undoTakesBackTwo() {
        val g = CheckersGame()
        g.play(g.legal()[0]); g.play(g.legal()[0])
        assertEquals(1, g.side)
        assertTrue(g.undo(1))
        assertEquals(0, g.plies)
        assertEquals(CheckersEngine.start().toList(), g.board.toList())
        // бот (белые) сходил, игрок за чёрных ещё нет — отменять нечего
        g.play(g.legal()[0])
        assertFalse(g.undo(-1))
    }

    @Test fun botFindsWinningCapture() {
        // белая дамка может сбить две последние чёрные — бот обязан (единственный путь к победе)
        val b = board("a1=W", "c3=b", "e5=b", "h8=b")
        val m = CheckersEngine.best(b, 1, 3, Random(1), timeMs = 300)
        assertNotNull(m)
        assertTrue(m.isCapture)
    }

    @Test fun botSelfPlayTerminates() {
        val rnd = Random(42)
        var decisive = 0
        repeat(20) { gi ->
            val g = CheckersGame()
            val lw = gi % 3; val lb = (gi + 1) % 3
            var plies = 0
            while (g.result() == null) {
                val before = CheckersEngine.DARK.count { g.board[it] != 0 }
                val m = CheckersEngine.best(g.board, g.side, if (g.side == 1) lw else lb, rnd, g.pastKeys())
                assertNotNull(m)
                assertTrue(g.legal().any { it.path.contentEquals(m.path) })
                g.play(m)
                assertEquals(before - m.caps.size, CheckersEngine.DARK.count { g.board[it] != 0 })
                plies++
                assertTrue(plies < 3000, "партия $gi не кончается")
            }
            if (g.result() != 0) decisive++
        }
        assertTrue(decisive > 0)
    }

    @Test fun strongBeatsNovice() {
        var strong = 0
        repeat(4) { gi ->
            val g = CheckersGame()
            val strongSide = if (gi % 2 == 0) 1 else -1
            val rnd = Random(gi)
            while (g.result() == null && g.plies < 400) {
                val lv = if (g.side == strongSide) 2 else 0
                g.play(CheckersEngine.best(g.board, g.side, lv, rnd, g.pastKeys())!!)
            }
            if (g.result() == strongSide) strong++
        }
        assertTrue(strong >= 3, "мастер выиграл $strong из 4")
    }

}
