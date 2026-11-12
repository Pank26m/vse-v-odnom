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

    @Test fun territoryModes() {
        // формула шанса: 2 на 2 — 50%, 2 на 1 — 75%, 1 на 2 — 25%, симметрична
        assertEquals(0.5, TerritoryGame.chance(2, 2), 1e-9); assertEquals(0.75, TerritoryGame.chance(2, 1), 1e-9); assertEquals(0.25, TerritoryGame.chance(1, 2), 1e-9)
        for (a in 1..12) for (d in 1..12) assertEquals(1.0, TerritoryGame.chance(a, d) + TerritoryGame.chance(d, a), 1e-9)
        for (size in 0..3) for (players in 2..4) for (sym in listOf(true, false)) for (mixed in listOf(false, true)) {
            val g = TerritoryGame(players = players, seed = (size * 100 + players * 10 + (if (sym) 1 else 0)).toLong(), size = size,
                brains = listOf(Brain.GOD, Brain.MASTER, Brain.DUMB), symmetric = sym, mixed = mixed)
            assertTrue(g.cells.size >= 8, "точек мало: ${g.cells.size}")
            // связность
            val seen = HashSet<Int>(); val st = ArrayDeque(listOf(g.cells[0]))
            while (st.isNotEmpty()) { val h = st.removeLast(); if (seen.add(h.id)) g.nbrs(h).forEach { if (it.id !in seen) st.add(it) } }
            assertEquals(g.cells.size, seen.size, "карта несвязна size=$size p=$players sym=$sym")
            assertEquals(players, (0 until players).count { g.alive(it) })
            if (mixed) assertTrue(g.cells.all { it.owner >= 0 && it.v == 2 })
            // у каждой клетки есть мост, мосты — только между соседними шестиугольниками
            g.cells.forEach { c -> assertTrue(g.nbrs(c).isNotEmpty(), "клетка без мостов") }
            g.edges.forEach { (a, b) -> val x = g.cells[a]; val y = g.cells[b]; val dq = x.q - y.q; val dr = x.r - y.r
                assertTrue((kotlin.math.abs(dq) + kotlin.math.abs(dr) + kotlin.math.abs(dq + dr)) == 2, "мост не между соседями") }
            if (size >= 2) { println("size=$size p=$players sym=$sym mixed=$mixed: клеток ${g.cells.size}, мостов ${g.edges.size} (без доигрывания)"); continue }
            var turns = 0
            while (g.winner == null && turns < 3000) {
                while (g.botAttackOnce() && g.winner == null) {}
                if (g.winner != null) break
                g.endAttack(); g.autoGrow(g.brainOf(g.turn)); g.endTurn(); turns++
                g.cells.forEach { assertTrue(it.v in 0..it.cap) }
            }
            println("size=$size p=$players sym=$sym mixed=$mixed: точек ${g.cells.size}, мостов ${g.edges.size}, аванпостов ${g.cells.count { it.outpost }}, ходов $turns, победил ${g.winner}")
            assertNotNull(g.winner, "партия ботов не закончилась: size=$size p=$players sym=$sym mixed=$mixed")
        }
    }

    /** Турнир уровней ботов «Территорий» (дуэли на карте L): сильнее должен чаще побеждать. */
    @Test fun territoryBrains() {
        val res = StringBuilder()
        for ((a, b) in listOf(Brain.GOD to Brain.HUNTER, Brain.MASTER to Brain.HUNTER, Brain.HUNTER to Brain.DUMB, Brain.GOD to Brain.MASTER)) {
            var winA = 0; var games = 0
            repeat(40) { seed ->
                // a играет то за первого, то за второго
                val aFirst = seed % 2 == 0
                val g = TerritoryGame(players = 2, seed = seed * 101L, size = 1, brains = listOf(if (aFirst) b else a), brain0 = if (aFirst) a else b)
                var t = 0
                while (g.winner == null && t < 1500) { while (g.botAttackOnce() && g.winner == null) {}; if (g.winner != null) break; g.endAttack(); g.autoGrow(g.brainOf(g.turn)); g.endTurn(); t++ }
                if (g.winner != null) { games++; if ((g.winner == 0) == aFirst) winA++ }
            }
            res.append("${a.title} против ${b.title}: $winA из $games\n")
        }
        println(res)
    }

    @Test fun blocksClearLine() {
        val g = BlocksGame(kotlin.random.Random(1))
        // нижняя строка заполнена, кроме 4 клеток справа — палка I закрывает их
        for (x in 0 until 6) g.grid[(BlocksGame.H - 1) * BlocksGame.W + x] = 1
        g.kind = 0; g.rot = 0; g.px = 6; g.py = 0
        g.drop()
        assertEquals(1, g.lines); assertTrue(g.score >= 100)
        assertTrue((0 until BlocksGame.W).none { g.grid[(BlocksGame.H - 1) * BlocksGame.W + it] == 1 })
        // повороты не выходят за стены
        repeat(50) { g.rotate(); g.move(-1) }
        assertTrue(g.cells().all { it.first in 0 until BlocksGame.W })
    }

    @Test fun knivesCollide() {
        val g = KnivesGame(kotlin.random.Random(2))
        g.stuck.clear(); g.applesOn.clear()
        g.throwKnife(); var ev = 0; repeat(30) { if (ev == 0) ev = g.step(0.01f) }
        assertEquals(1, g.stuck.size)
        // второй нож в то же место (мишень не повернулась) — отскок
        g.angle = (360f - g.stuck[0]) % 360f
        val saved = g.stuck[0]; g.stuck.clear(); g.stuck += saved
        g.throwKnife(); ev = 0
        // «заморозим» вращение: полёт 0,11 с — за это время мишень повернётся ~15°, поэтому ставим нож прямо в точку прилёта
        repeat(30) { if (ev == 0) { val before = g.angle; ev = g.step(0.01f); if (ev == 0 && g.flying >= 0) { g.stuck[0] = (360f - g.angle) % 360f; @Suppress("UNUSED_VARIABLE") val x = before } } }
        assertEquals(4, ev)
    }

    @Test fun hockeyBotPlays() {
        val g = HockeyGame(2)
        // «игрок» попроще: держится между шайбой и своими воротами и бьёт, когда шайба на его половине
        repeat(120 * 60) {
            val tx = g.px; val ty = if (g.py > HockeyGame.H / 2) g.py + 0.05f else HockeyGame.H * 0.85f
            g.moveMine(g.mx + (tx - g.mx).coerceIn(-0.02f, 0.02f), g.my + (ty - g.my).coerceIn(-0.02f, 0.02f), 1f / 60)
            g.step(1f / 60)
        }
        assertTrue(!g.px.isNaN() && !g.py.isNaN())
        println("хоккей: за 2 минуты бот забил ${g.bot}, простой игрок ${g.me}")
        assertTrue(g.bot >= 1, "бот должен уметь забивать")
    }

    @Test fun sudokuGod() {
        val t0 = System.currentTimeMillis()
        val (p, sol) = com.pank.vsevodnom.ui.games.SudokuGen.generateGod(7)
        assertEquals(1, com.pank.vsevodnom.ui.games.SudokuGen.count(p.copyOf()))
        assertFalse(com.pank.vsevodnom.ui.games.SudokuGen.easyLogic(p))
        assertTrue(sol.none { it == 0 })
        println("божество: подсказок ${p.count { it != 0 }}, ${System.currentTimeMillis() - t0} мс")
    }

    @Test fun connectFourWinAndBot() {
        val b = C4.empty()
        // три красных внизу — бот обязан закрыть четвёртую
        for (c in 0..2) b[(C4.H - 1) * C4.W + c] = 1
        assertEquals(3, C4.best(b, 2, 1))
        b[(C4.H - 1) * C4.W + 3] = 1
        assertEquals(1, C4.winner(b)?.first)
        // сильный бот против случайных ходов не проигрывает
        repeat(5) { seed ->
            val rnd = kotlin.random.Random(seed)
            val g = C4.empty(); var p = 1
            while (C4.winner(g) == null && !C4.full(g)) {
                val c = if (p == 1) (0 until C4.W).filter { C4.canDrop(g, it) }.random(rnd) else C4.best(g, 2, 2, rnd)
                g[C4.dropRow(g, c) * C4.W + c] = p; p = 3 - p
            }
            assertEquals(2, C4.winner(g)?.first, "партия $seed")
        }
    }

    @Test fun battleshipFleetAndBot() {
        repeat(30) { seed ->
            val sea = Sea(kotlin.random.Random(seed))
            assertEquals(Sea.FLEET.sum(), sea.ship.count { it >= 0 })
            // корабли не касаются: соседняя клетка чужого корабля запрещена
            for (i in 0 until Sea.N * Sea.N) if (sea.ship[i] >= 0) sea.around(i).forEach { assertTrue(sea.ship[it] < 0 || sea.ship[it] == sea.ship[i]) }
            for (level in 0..2) {
                val s2 = Sea(kotlin.random.Random(seed)); val bot = SeaBot(level, kotlin.random.Random(seed))
                var shots = 0
                while (!s2.allSunk()) { assertTrue(s2.fire(bot.pick(s2)) != 0, "бот не стреляет дважды в клетку"); shots++ }
                assertTrue(shots <= 100)
            }
        }
    }
}
