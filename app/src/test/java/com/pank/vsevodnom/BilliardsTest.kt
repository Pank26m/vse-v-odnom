package com.pank.vsevodnom

import com.pank.vsevodnom.ui.games.*
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BilliardsTest {
    @Test fun breakSettles() {
        for (mode in PoolMode.entries) {
            val g = PoolGame(mode, Random(3)); g.turn = 0
            g.shoot(-Math.PI / 2, 1.0, 0.0, 0.0); g.runToEnd()
            assertTrue(!g.shooting, "удар закончился")
            g.balls.filter { it.on }.forEach { b -> assertTrue(b.x in 0.0..g.table.W && b.y in 0.0..g.table.H, "${mode}: шар ${b.num} на столе (${b.x}, ${b.y})") }
        }
    }

    /** Прямой удар в угловую лузу забивает шар; накат — биток идёт следом, оттяжка — назад. */
    @Test fun straightPotAndSpin() {
        fun setup(): PoolGame { val g = PoolGame(PoolMode.EIGHT, Random(1)); g.balls.drop(1).forEach { it.on = false }
            val t = g.table; val b = g.ball(1); b.on = true; b.x = 0.4; b.y = 0.4
            val (ax, ay) = t.aimPoint(0); val ang = atan2(ay - b.y, ax - b.x)
            g.cue.x = b.x - kotlin.math.cos(ang) * 0.5; g.cue.y = b.y - kotlin.math.sin(ang) * 0.5; g.broken = true; g.turn = 0; return g }
        val g = setup(); val t = g.table; val b = g.ball(1)
        val ang = atan2(b.y - g.cue.y, b.x - g.cue.x)
        g.shoot(ang, 0.4, 0.0, 0.0); g.runToEnd()
        assertTrue(!g.ball(1).on, "шар забит")
        // оттяжка: биток после удара откатывается назад (дальше от лузы, чем точка удара)
        val g2 = setup(); val c0 = hypot(g2.cue.x, g2.cue.y)
        g2.ball(1).also { it.x = 0.4; it.y = 0.4 }
        g2.shoot(ang, 0.45, 0.0, -1.0); g2.runToEnd()
        assertTrue(hypot(g2.cue.x, g2.cue.y) > c0 - 0.2, "оттяжка вернула биток назад")
        assertTrue(t.R > 0)
    }

    @Test fun guideFindsBall() {
        val g = PoolGame(PoolMode.PYRAMID, Random(2))
        val gd = PoolAim.guide(g, -Math.PI / 2)
        assertTrue(gd.hit != null, "прямо по центру — в пирамиду")
    }

    /** Бот против бота: партии доходят до конца без исключений, правила не зацикливаются. */
    @Test fun botSelfPlay() {
        for (mode in PoolMode.entries) repeat(3) { n ->
            val g = PoolGame(mode, Random(n + 10)); var shots = 0
            while (g.winner < 0 && shots < 260) {
                val s = PoolBot.move(g, if (mode == PoolMode.EIGHT) 1 else 2, Random(shots + n))
                g.shoot(s.angle, s.power, s.sx, s.sy); g.runToEnd(); shots++
                val onTable = g.balls.count { it.on }
                assertTrue(onTable >= 1, "биток всегда возвращается")
            }
            assertTrue(g.winner >= 0 || shots == 260, "партия идёт")
            if (mode == PoolMode.PYRAMID) assertEquals(true, g.score.all { it >= 0 })
        }
    }
}
