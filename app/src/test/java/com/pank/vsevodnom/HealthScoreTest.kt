package com.pank.vsevodnom

import com.pank.vsevodnom.data.BodyCheck
import com.pank.vsevodnom.data.DayHealth
import com.pank.vsevodnom.data.HealthScore
import com.pank.vsevodnom.data.Nutrition
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthScoreTest {
    private val plan = Nutrition.Plan(2100, 140, 70, 225)
    private fun week(steps: Long, kcal: Double? = null, ex: Long = 0) = (6 downTo 0).map { DayHealth(LocalDate.now().minusDays(it.toLong()), steps = steps, intakeKcal = kcal, exerciseMin = ex) }

    @Test fun stepsHalfAndOvereating() {
        val p = HealthScore.compute(week(3000, 5000.0), 6000, plan, 173.8, null, null, null)
        assertEquals(5.0, p.first { it.key == "steps" }.score, 0.01)
        assertEquals(0.0, p.first { it.key == "food" }.score, 0.01)
        assertTrue(HealthScore.total(p)!! < 4)
    }
    @Test fun overtrainingLowersScore() {
        val normal = HealthScore.compute(week(8000, ex = 30), 6000, plan, null, null, null, null).first { it.key == "activity" }.score
        val crazy = HealthScore.compute(week(8000, ex = 180), 6000, plan, null, null, null, null).first { it.key == "activity" }.score
        assertTrue(crazy < normal)
    }
    @Test fun noSleepNoPenalty() {
        val p = HealthScore.compute(week(6000), 6000, plan, null, null, null, null)
        assertTrue(p.none { it.key == "sleep" })
        val body = HealthScore.compute(week(6000), 6000, plan, null, BodyCheck.FIRST, 30, null).first { it.key == "body" }
        assertTrue(body.score < 4, "ИМТ 32 и жир 36% — низкая оценка состава тела")
    }
}
