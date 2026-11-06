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

    /** Бот играет за обе стороны: партия должна закончиться, карт всегда 36. */
    @Test fun durakSelfPlay() {
        repeat(200) { game ->
            val g = Durak()
            var steps = 0
            while (g.over == null && steps < 5000) {
                steps++
                // ход «человека» делаем той же логикой, временно меняя местами руки
                val acted = if (g.attacker == 1 || (g.defender == 1)) g.botStep() else false
                if (!acted) {
                    // игрок 0: простая стратегия
                    val h = g.hands[0]
                    if (g.attacker == 0) {
                        val c = h.firstOrNull { g.canAttackWith(it) && (g.attack.isEmpty() || g.unbeaten().isEmpty() || g.taking) }
                        if (c != null && (g.attack.isEmpty() || g.attack.size < 3)) g.playAttack(0, c)
                        else if (g.attack.isNotEmpty() && (g.unbeaten().isEmpty() || g.taking)) g.finishRound()
                    } else {
                        val u = g.unbeaten()
                        if (u.isNotEmpty() && !g.taking) {
                            val i = u.first(); val c = h.filter { g.beats(it, g.attack[i]) }.minByOrNull { it.rank }
                            if (c != null) g.playDefense(c, i) else g.taking = true
                        }
                    }
                }
                val total = g.deck.size + g.hands[0].size + g.hands[1].size + g.attack.size + g.defense.count { it != null } + g.discarded
                assertEquals(36, total, "карт стало $total на шаге $steps")
            }
            assertNotNull(g.over, "партия $game не закончилась за $steps шагов")
        }
    }
}
