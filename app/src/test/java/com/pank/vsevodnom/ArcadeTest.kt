package com.pank.vsevodnom

import com.pank.vsevodnom.ui.games.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArcadeTest {
    @Test fun mergeSlidesAndMergesOnce() {
        val g = MergeGame(seed = 1)
        // проверяем инвариант: после любых ходов сумма значений растёт только на появившиеся плитки (2 или 4)
        var sum = g.tiles.sumOf { it.v }
        repeat(300) { k ->
            val before = g.tiles.size
            val moved = g.move(k % 4)
            val now = g.tiles.sumOf { it.v }
            if (moved) assertTrue(now - sum == 2 || now - sum == 4, "ход $k: $sum → $now")
            sum = now
            assertTrue(g.tiles.map { it.r to it.c }.toSet().size == g.tiles.size, "плитки не накладываются")
            if (!g.canMove()) return
            assertTrue(before > 0)
        }
    }

    @Test fun mazeIsConnected() {
        val m = MAZE_MAP
        assertTrue(m.all { it.length == m[0].length })
        val open = HashSet<Pair<Int, Int>>()
        m.forEachIndexed { y, row -> row.forEachIndexed { x, c -> if (c != '#' && c != 'H' && c != 'D') open += x to y } }
        val start = open.first()
        val seen = HashSet<Pair<Int, Int>>(); val q = ArrayDeque(listOf(start))
        while (q.isNotEmpty()) { val p = q.removeFirst(); if (!seen.add(p)) continue; listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).map { p.first + it.first to p.second + it.second }.filter { it in open }.forEach(q::add) }
        assertEquals(open.size, seen.size, "все проходы лабиринта связаны")
    }

    @Test fun snakeGrowsAndDies() {
        val g = SnakeGame(10, 10, walls = true)
        repeat(30) { g.step() }   // ползёт вверх и упирается в стену
        assertTrue(g.over)
    }

    @Test fun tttBotNeverLoses() {
        // бот за нолики против всех ходов крестиков (полный перебор)
        fun play(b: MutableList<Int>, turn: Int): Boolean {
            Ttt.winner(b)?.let { return it.first == 1 }
            if (b.none { it == 0 }) return false
            if (turn == 2) { val i = Ttt.best(b, 2); b[i] = 2; val r = play(b, 1); b[i] = 0; return r }
            for (i in b.indices.filter { b[it] == 0 }) { b[i] = 1; val r = play(b, 2); b[i] = 0; if (r) return true }
            return false
        }
        assertFalse(play(MutableList(9) { 0 }, 1), "крестики не должны выигрывать у сильного бота")
    }

    @Test fun territoryBotsFinish() {
        val g = TerritoryGame(players = 4, seed = 42)
        assertEquals(4, (0 until 4).count { g.alive(it) })
        var turns = 0
        while (g.winner == null && turns < 2000) {
            while (g.botAttackOnce() && g.winner == null) {}
            if (g.winner != null) break
            g.endAttack(); g.autoGrow(); g.endTurn(); turns++
            g.cells.forEach { assertTrue(it.v in 0..TerritoryGame.MAX) }
        }
        assertNotNull(g.winner, "партия ботов заканчивается за $turns ходов")
    }
}
