package com.pank.vsevodnom

import com.pank.vsevodnom.ui.games.DunkGame
import com.pank.vsevodnom.ui.games.JumperGame
import com.pank.vsevodnom.ui.games.dunkSoundSizes
import com.pank.vsevodnom.ui.games.jumperSoundSizes
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DunkJumperTest {
    /** Бросок с посчитанной скоростью попадает в следующее кольцо; несколько колец подряд, пока они стоят на месте. */
    @Test fun dunkAimedShotsScore() {
        for (seed in 1..20) {
            val g = DunkGame(Random(seed))
            var shots = 0
            while (!g.hoops[1].moving && shots < 6 && !g.over) {
                val tgt = g.hoops[1]
                val before = g.hoopsDone
                val (vx, vy) = g.velocityTo(tgt.x, tgt.y)
                // такую скорость можно набрать рогаткой
                assertTrue(hypot(vx, vy) / DunkGame.PULL_K <= DunkGame.PULL_MAX, "seed $seed: натяжение ${hypot(vx, vy) / DunkGame.PULL_K}")
                // через натяжение, как пальцем
                g.beginAim(); g.setPull(vx / DunkGame.PULL_K, vy / DunkGame.PULL_K)
                assertTrue(g.preview().isNotEmpty())
                assertTrue(g.release(), "seed $seed: бросок")
                var n = 0
                while (n < 600 && g.hoopsDone == before && !g.over) { g.step(1f / 60f); n++ }
                assertFalse(g.over, "seed $seed бросок $shots: мимо")
                assertEquals(before + 1, g.hoopsDone)
                repeat(30) { g.step(1f / 60f) }   // мяч улёгся
                assertEquals(DunkGame.S_REST, g.state)
                shots++
            }
            assertTrue(shots >= 3, "seed $seed: $shots")
            assertTrue(g.score >= shots)
        }
    }

    /** Чистые попадания растят серию и очки; мяч загорается. */
    @Test fun dunkSwishStreak() {
        val g = DunkGame(Random(5))
        repeat(3) {
            val (vx, vy) = g.velocityTo(g.hoops[1].x, g.hoops[1].y)
            g.launch(vx, vy)
            var n = 0; val before = g.hoopsDone
            while (n < 600 && g.hoopsDone == before && !g.over) { g.step(1f / 60f); n++ }
            repeat(30) { g.step(1f / 60f) }
        }
        assertEquals(3, g.streak)
        assertEquals(2 + 3 + 4, g.score)
        assertTrue(g.onFire)
    }

    /** Слабый бросок вбок — мяч падает вниз, игра кончается. */
    @Test fun dunkMissEndsGame() {
        val g = DunkGame(Random(3))
        val away = if (g.hoops[1].x > 50f) -1f else 1f
        g.launch(away * 30f, 60f)
        var n = 0
        while (n < 900 && !g.over) { g.step(1f / 60f); n++ }
        assertTrue(g.over)
        assertEquals(0, g.score)
    }

    /** Кольцо с мячом — дужки отбивают мяч (удар по дужке снимает «чисто»). */
    @Test fun dunkRimBounce() {
        val g = DunkGame(Random(8))
        val tgt = g.hoops[1]
        // целимся точно в дальнюю дужку
        val (vx, vy) = g.velocityTo(tgt.rimX(if (tgt.x > g.bx) 1 else -1), tgt.rimY(1) + 2f, 10f)
        g.launch(vx, vy)
        var touched = false; var n = 0
        while (n < 600 && !g.over && g.state == DunkGame.S_FLY) { g.step(1f / 60f); if (g.rimTouched) touched = true; n++ }
        assertTrue(touched)
    }

    /** Мир «Прыгуна» до 50 000: между твёрдыми платформами по высоте всегда меньше высоты прыжка. */
    @Test fun jumperWorldReachable() {
        for (seed in 1..5) {
            val g = JumperGame(Random(seed))
            g.generateUpTo(50_000f)
            val ys = g.plats.filter { it.solid }.map { it.y }.sorted()
            assertTrue(ys.last() >= 50_000f)
            var maxGap = 0f
            for (i in 1 until ys.size) maxGap = maxOf(maxGap, ys[i] - ys[i - 1])
            assertTrue(maxGap < JumperGame.JUMP_H * 0.85f, "seed $seed: промежуток $maxGap при прыжке ${JumperGame.JUMP_H}")
            // наверху платформы реже, чем внизу
            val low = ys.count { it < 5_000f }; val high = ys.count { it in 45_000f..50_000f }
            assertTrue(high < low, "low $low high $high")
            // особые виды и опасности появляются
            assertTrue(g.plats.any { it.kind == JumperGame.MOVING })
            assertTrue(g.plats.any { it.kind == JumperGame.BREAK })
            assertTrue(g.plats.any { it.kind == JumperGame.VANISH })
            assertTrue(g.plats.any { it.item == JumperGame.SPRING })
            assertTrue(g.monsters.isNotEmpty() && g.holes.isNotEmpty())
            // опасность в стороне от платформ (обходится по горизонтали)
            for (m in g.monsters) {
                val near = g.plats.filter { it.solid && abs(it.y - m.y) < 6f && abs(JumperGame.wdx(it.x, m.x)) < 10f }
                assertTrue(near.isEmpty(), "монстр на платформе: ${m.x},${m.y}")
            }
        }
    }

    /** Простой «бот» (держит курс на ближайшую достижимую платформу) поднимается: физика прыжка и склейка краёв работают. */
    @Test fun jumperBotClimbs() {
        for (seed in 1..3) {
            val g = JumperGame(Random(seed))
            g.viewH = 180f
            var time = 0f
            while (time < 120f && !g.over && g.maxY < 1400f) {
                val apex = g.py + if (g.vy > 0) g.vy * g.vy / (2 * JumperGame.G) else 0f
                val target = g.plats.filter { it.solid && !it.gone && !it.broken && it.y < apex - 2f && it.y > g.py - 60f }.maxByOrNull { it.y }
                g.input = if (target == null) 0f else (JumperGame.wdx(target.x, g.px) / 4f).coerceIn(-1f, 1f)
                g.step(1f / 60f); time += 1f / 60f
            }
            assertFalse(g.over, "seed $seed: упал на ${g.maxY}")
            assertTrue(g.maxY >= 1400f, "seed $seed: ${g.maxY}")
        }
    }

    /** Монстра давят сверху, сбоку — смерть; пружина подбрасывает выше. */
    @Test fun jumperMonsterAndSpring() {
        val g = JumperGame(Random(1))
        g.monsters.clear(); g.holes.clear()
        // сверху
        val m = JumperGame.Monster(g.px, g.py + 30f, 0, 1)
        g.monsters += m
        g.vy = -100f; g.py = m.y + 8f
        g.step(1f / 60f)
        assertTrue(m.dead); assertFalse(g.dying); assertTrue(g.vy > 0f)
        // снизу
        val g2 = JumperGame(Random(1)); g2.monsters.clear(); g2.holes.clear()
        val m2 = JumperGame.Monster(g2.px, g2.py + 14f, 0, 1); g2.monsters += m2
        g2.vy = 120f
        repeat(10) { g2.step(1f / 60f) }
        assertTrue(g2.dying)
        // пружина
        val g3 = JumperGame(Random(1))
        val p = g3.plats.first()
        p.item = JumperGame.SPRING; p.itemDx = 0f
        g3.px = p.x; g3.py = p.y + 10f; g3.vy = -50f
        repeat(30) { if (g3.vy < 0f) g3.step(1f / 60f) }
        assertEquals(JumperGame.SPRING_V, g3.vy, 20f)
    }

    @Test fun soundsBuild() {
        assertTrue(dunkSoundSizes().all { it > 1000 })
        assertTrue(jumperSoundSizes().all { it > 1000 })
    }
}
