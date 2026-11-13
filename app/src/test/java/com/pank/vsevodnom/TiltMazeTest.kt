package com.pank.vsevodnom

import com.pank.vsevodnom.ui.games.TiltBall
import com.pank.vsevodnom.ui.games.TiltLevel
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TiltMazeTest {
    @Test fun allLevelsGenerateAndAreSolvable() {
        for (n in 1..TiltLevel.LEVELS) {
            val lv = TiltLevel.generate(n)
            assertTrue(lv.holes.size >= 3, "уровень $n: лунок ${lv.holes.size}")
            assertTrue(lv.path.first() == lv.start && lv.path.last() == lv.finish, "уровень $n: путь")
            assertTrue(lv.path.size >= lv.rows, "уровень $n: короткий путь ${lv.path.size}")
            assertTrue(lv.gridSolvable(), "уровень $n: по клеткам не пройти")
            assertTrue(lv.freeSpaceSolvable(), "уровень $n: шарик не проходит")
            // лунки не ближе друг к другу, чем проход в margin диаметров
            val need = 2 * lv.holeR + 2 * lv.margin * lv.ballR - 1e-3f
            for (i in lv.holes.indices) for (j in i + 1 until lv.holes.size) {
                val a = lv.holes[i]; val b = lv.holes[j]
                assertTrue(hypot(a.x - b.x, a.y - b.y) >= need, "уровень $n: лунки $i и $j слишком близко")
            }
            // лунка не залезает в стену
            for (h in lv.holes) assertTrue(lv.wallDist(h.x, h.y) >= lv.holeR - 1e-3f, "уровень $n: лунка в стене")
        }
    }

    @Test fun levelsAreDeterministic() {
        for (n in listOf(1, 2, 37, 64, 100)) {
            val a = TiltLevel.generate(n); val b = TiltLevel.generate(n)
            assertEquals(a.walls, b.walls); assertEquals(a.holes, b.holes); assertEquals(a.path, b.path)
            assertEquals(a.start, b.start); assertEquals(a.finish, b.finish)
        }
        assertNotEquals(TiltLevel.generate(5).holes, TiltLevel.generate(6).holes)
    }

    @Test fun difficultyRises() {
        val early = (1..10).map { TiltLevel.generate(it) }
        val late = (91..100).map { TiltLevel.generate(it) }
        assertTrue(late.sumOf { it.holes.size } > early.sumOf { it.holes.size } * 2, "лунок к концу больше")
        assertTrue(late.all { it.cols > early.first().cols })
        assertTrue(late.first().margin < early.first().margin)
    }

    @Test fun randomLevelsSolvable() {
        val r = Random(42)
        repeat(30) {
            val lv = TiltLevel.generate(0, r.nextLong(), TiltLevel.RANDOM_LEVEL)
            assertTrue(lv.gridSolvable() && lv.freeSpaceSolvable(), "случайный $it")
        }
    }

    @Test fun ballNeverEntersWalls() {
        for (n in listOf(1, 50, 100)) {
            val lv = TiltLevel.generate(n)
            val ball = TiltBall(lv)
            val r = Random(n)
            var gx = 0f; var gy = 0f
            repeat(6000) { i ->
                if (i % 40 == 0) { gx = (r.nextFloat() * 2 - 1) * 9f; gy = (r.nextFloat() * 2 - 1) * 9f }
                ball.step(1f / 60f, gx, gy)
                if (!ball.falling && !ball.done)
                    assertTrue(lv.wallDist(ball.x, ball.y) >= lv.ballR * 0.97f, "уровень $n шаг $i: шарик в стене")
                assertTrue(ball.x in 0f..lv.cols.toFloat() && ball.y in 0f..lv.rows.toFloat())
            }
        }
    }

    @Test fun fallsIntoHoleAndRespawns() {
        val lv = TiltLevel.generate(10)
        val ball = TiltBall(lv)
        val h = lv.holes.first()
        ball.x = h.x + lv.holeR * 0.9f; ball.y = h.y
        if (lv.wallDist(ball.x, ball.y) < lv.ballR) ball.x = h.x - lv.holeR * 0.9f
        var fell = false; var respawn = false
        repeat(120) { when (ball.step(1f / 60f, 0f, 0f)) { TiltBall.EV_FALL -> fell = true; TiltBall.EV_RESPAWN -> respawn = true } }
        assertTrue(fell, "затянуло в лунку"); assertTrue(respawn, "вернулся на старт")
        assertEquals(lv.startX, ball.x); assertEquals(lv.startY, ball.y)
    }

    @Test fun reachesFinish() {
        val lv = TiltLevel.generate(3)
        val ball = TiltBall(lv)
        ball.x = lv.finishX; ball.y = lv.finishY
        var win = false; var done = false
        repeat(60) { when (ball.step(1f / 60f, 0f, 0f)) { TiltBall.EV_WIN -> win = true; TiltBall.EV_DONE -> done = true } }
        assertTrue(win && done && ball.done)
    }

    @Test fun ballRestsOnFlatBoard() {
        val lv = TiltLevel.generate(1)
        val ball = TiltBall(lv)
        repeat(300) { ball.step(1f / 60f, 0.03f, -0.02f) }
        assertEquals(lv.startX, ball.x); assertEquals(lv.startY, ball.y)
    }
}
