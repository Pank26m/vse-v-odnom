package com.pank.vsevodnom

import com.pank.vsevodnom.ui.games.DuelGame
import com.pank.vsevodnom.ui.games.RocketGame
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RocketDuelTest {
    // ── Ракета ──
    @Test fun rocketJudge() {
        assertEquals(RocketGame.Judge.PERFECT, RocketGame.judge(0.5f, 0.5f, 0.2f))
        assertEquals(RocketGame.Judge.GOOD, RocketGame.judge(0.58f, 0.5f, 0.2f))
        assertEquals(RocketGame.Judge.MISS, RocketGame.judge(0.62f, 0.5f, 0.2f))
        assertEquals(RocketGame.Judge.MISS, RocketGame.judge(0.1f, 0.5f, 0.2f))
        // выше — уже зона и быстрее стрелка
        assertTrue(RocketGame.zoneWidth(10) < RocketGame.zoneWidth(1))
        assertTrue(RocketGame.needleSpeed(10) > RocketGame.needleSpeed(1))
        // топлива хватает хотя бы на один проход стрелки по всей шкале
        for (s in 0..60) assertTrue(RocketGame.fuelTime(s) * RocketGame.needleSpeed(s) > 2f, "ступень $s")
    }

    @Test fun rocketStagesAndMiss() {
        val g = RocketGame(Random(1))
        assertEquals(RocketGame.Phase.PAD, g.phase)
        g.needle = g.zoneC
        assertEquals(RocketGame.Judge.PERFECT, g.tap())
        assertEquals(1, g.stage); assertEquals(RocketGame.Phase.BURN, g.phase)
        repeat(20) { g.step(0.02f) }
        assertTrue(g.altitude > 0f)
        g.needle = g.zoneC + g.zoneW * 0.4f
        assertEquals(RocketGame.Judge.GOOD, g.tap())
        assertEquals(2, g.stage)
        assertTrue(g.debris.isNotEmpty(), "ступень отделилась")
        val alt = g.score
        g.needle = if (g.zoneC > 0.5f) 0f else 1f
        assertEquals(RocketGame.Judge.MISS, g.tap())
        assertEquals(RocketGame.Phase.FALL, g.phase)
        assertNull(g.tap(), "в падении нажатия не считаются")
        repeat(200) { g.step(0.02f) }
        assertTrue(g.over)
        assertTrue(g.score >= alt)
    }

    @Test fun rocketFuelRunsOut() {
        val g = RocketGame(Random(2))
        g.needle = g.zoneC; g.tap()
        var ev = 0
        repeat(1000) { if (ev == 0) ev = g.step(0.02f) }
        assertEquals(RocketGame.EV_FAIL, ev)
        repeat(200) { g.step(0.02f) }
        assertTrue(g.over)
    }

    @Test fun rocketPerfectBonus() {
        val a = RocketGame(Random(3)); val b = RocketGame(Random(3))
        a.needle = a.zoneC; a.tap(); b.needle = b.zoneC; b.tap()
        repeat(10) { a.step(0.02f); b.step(0.02f) }
        a.needle = a.zoneC; a.tap()
        b.needle = b.zoneC + b.zoneW * 0.45f; b.tap()
        assertTrue(a.altitude > b.altitude, "«идеально» даёт прибавку к высоте")
    }

    // ── Дуэль ──
    @Test fun rayHits() {
        assertNotNull(DuelGame.rayBox(0f, 0f, 1f, 0f, 2f, -1f, 3f, 1f))
        assertEquals(2f, DuelGame.rayBox(0f, 0f, 1f, 0f, 2f, -1f, 3f, 1f)!!, 1e-5f)
        assertNull(DuelGame.rayBox(0f, 0f, -1f, 0f, 2f, -1f, 3f, 1f), "коробка позади луча")
        assertNull(DuelGame.rayBox(0f, 0f, 1f, 1f, 2f, -1f, 3f, 1f), "луч проходит выше")
        assertNotNull(DuelGame.rayBox(0f, 0f, 0f, 1f, -1f, 2f, 1f, 3f), "вертикальный луч")
        val e = DuelGame.Fighter(3f, 1f, -1, 1, false, 1)
        val sy = 0.47f
        fun angleTo(y: Float): Pair<Float, Float> { val dx = 3f; val dy = y - sy; val l = kotlin.math.sqrt(dx * dx + dy * dy); return dx / l to dy / l }
        val (hx, hy) = angleTo(1f + 0.9f)
        assertEquals(DuelGame.Hit.HEAD, DuelGame.classify(0f, sy, hx, hy, e).first)
        val (bx, by) = angleTo(1f + 0.3f)
        assertEquals(DuelGame.Hit.BODY, DuelGame.classify(0f, sy, bx, by, e).first)
        val (mx, my) = angleTo(1f + 2f)
        assertEquals(DuelGame.Hit.MISS, DuelGame.classify(0f, sy, mx, my, e).first)
    }

    private fun waitAim(g: DuelGame) { var n = 0; while (g.phase != DuelGame.Phase.AIM && n++ < 500) g.step(0.02f); assertEquals(DuelGame.Phase.AIM, g.phase) }

    @Test fun duelMissThenBotShoots() {
        val g = DuelGame(Random(4))
        waitAim(g)
        g.aim = DuelGame.MIN_A   // ствол вниз — мимо
        assertEquals(DuelGame.Hit.MISS, g.shoot())
        assertEquals(DuelGame.Phase.ENEMY, g.phase)
        assertNull(g.shoot(), "второй выстрел нельзя")
        var ev = 0; var t = 0f
        while (ev == 0 && t < 3f) { ev = g.step(0.02f); t += 0.02f }
        assertEquals(DuelGame.EV_ENEMY_SHOT, ev)
        assertTrue(t >= g.enemyDelay() - 0.03f, "ответ — после паузы")
        repeat(100) { g.step(0.02f) }
        assertTrue(g.over)
    }

    @Test fun duelKillsClimbAndBoss() {
        val g = DuelGame(Random(5))
        repeat(9) { i ->
            waitAim(g)
            val ex = g.enemy.x; val ey = g.enemy.y
            g.aim = g.angleTo(g.enemy, head = true)
            assertEquals(DuelGame.Hit.HEAD, g.shoot(), "выстрел $i")
            waitAim(g)
            assertEquals(ex, g.player.x, 1e-4f); assertEquals(ey, g.player.y, 1e-4f)
            assertTrue(g.enemy.y > g.player.y, "новый противник выше")
            assertFalse(g.over)
        }
        assertEquals(9, g.kills)
        assertEquals(9, g.combo)
        assertTrue(g.score > 9 * 2, "серия в голову даёт бонус")
        val boss = g.enemy
        assertTrue(boss.boss && boss.hp > 2, "10-й — босс с запасом здоровья")
        g.aim = g.angleTo(boss, head = false)
        assertEquals(DuelGame.Hit.BODY, g.shoot())
        assertEquals(DuelGame.Phase.AIM, g.phase, "босс не упал — стреляем дальше")
        assertEquals(0, g.combo)
        while (g.enemy === boss && !g.over) {
            waitAim(g)
            if (g.enemy !== boss) break
            g.aim = g.angleTo(boss, head = true); g.shoot()
            repeat(3) { g.step(0.02f) }
        }
        assertEquals(10, g.kills)
        assertFalse(g.over)
    }

    @Test fun duelSpriteRows() {
        for (hat in 0..3) for (t in listOf(true, false)) {
            val s = DuelGame.sprite(hat, t)
            assertEquals(18, s.size)
            assertTrue(s.all { it.length == 12 }, "шляпа $hat")
        }
    }
}
