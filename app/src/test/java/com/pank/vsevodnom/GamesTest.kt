package com.pank.vsevodnom

import com.pank.vsevodnom.ui.games.Durak
import com.pank.vsevodnom.ui.games.SudokuGen
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GamesTest {
    @Test fun sudokuUnique() {
        repeat(5) {
            val t = System.currentTimeMillis()
            val (p, s) = SudokuGen.generate(25)
            assertEquals(1, SudokuGen.count(p.copyOf()))
            assertTrue((0 until 81).all { p[it] == 0 || p[it] == s[it] })
            println("clues=${p.count { it != 0 }} ms=${System.currentTimeMillis() - t}")
        }
    }

    /** Боты играют за всех (2–3 игрока, подкидной и переводной): партия заканчивается, карт всегда 36. */
    @Test fun durakSelfPlay() {
        for (players in 2..3) for (tr in listOf(false, true)) repeat(150) { game ->
            val g = Durak(players = players, transfer = tr, rnd = kotlin.random.Random(game * 7 + players))
            var steps = 0
            while (g.over == null && steps < 8000) {
                steps++
                if (g.roundCanEnd()) { g.finishRound() }
                else {
                    val order = listOf(g.defender) + g.throwers()
                    var acted = false
                    for (p in order) if (g.botStep(p)) { acted = true; break }
                    if (!acted) error("никто не ходит: players=$players tr=$tr шаг $steps")
                }
                val total = g.deck.size + g.hands.sumOf { it.size } + g.attack.size + g.defense.count { it != null } + g.discarded
                assertEquals(36, total, "карт стало $total на шаге $steps")
            }
            assertNotNull(g.over, "партия $game ($players игр., перевод=$tr) не закончилась за $steps шагов")
        }
    }
}
