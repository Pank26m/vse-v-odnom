package com.pank.vsevodnom

import com.pank.vsevodnom.data.Habit
import com.pank.vsevodnom.data.HabitLog
import com.pank.vsevodnom.data.HabitPenalty
import com.pank.vsevodnom.data.Todo
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Серия привычки с «штрафами» = пропущенными днями (пример владельца, 0.45). */
class HabitRunsTest {
    private val d9 = LocalDate.of(2026, 11, 9)
    private fun habit(id: Long) = Habit(id = id, title = "h", startDay = d9.toEpochDay(), createdAt = 0)
    private fun logs(id: Long, vararg days: Int) = days.map { HabitLog(habitId = id, epochDay = LocalDate.of(2026, 11, it).toEpochDay(), count = 1) }

    @Test fun oneMissExcused() {
        HabitPenalty.putForTest(1, HabitPenalty.Rule(perMonth = 2, inRow = 1))
        val r = Todo.runs(habit(1), logs(1, 9, 10, 12, 13), LocalDate.of(2026, 11, 13))
        assertEquals(4, r.cur); assertEquals(4, r.best); assertEquals(setOf(LocalDate.of(2026, 11, 11).toEpochDay()), r.excused)
    }

    @Test fun twoInRowBreaks() {
        HabitPenalty.putForTest(2, HabitPenalty.Rule(perMonth = 2, inRow = 1))
        val r = Todo.runs(habit(2), logs(2, 9, 10, 13), LocalDate.of(2026, 11, 13))
        assertEquals(1, r.cur); assertEquals(2, r.best)
    }

    @Test fun monthLimit() {
        HabitPenalty.putForTest(3, HabitPenalty.Rule(perMonth = 2, inRow = 1))
        // пропуски 10, 12, 14: третий сверх «2 в месяц» — серия рвётся
        val r = Todo.runs(habit(3), logs(3, 9, 11, 13, 15, 16), LocalDate.of(2026, 11, 16))
        assertEquals(2, r.cur); assertEquals(3, r.best)
    }

    @Test fun noRuleAndTodayOpen() {
        val r = Todo.runs(habit(4), logs(4, 9, 10, 11), LocalDate.of(2026, 11, 12))
        assertEquals(3, r.cur)   // сегодня ещё не отмечено — серия жива
        val r2 = Todo.runs(habit(4), logs(4, 9, 11), LocalDate.of(2026, 11, 11))
        assertEquals(1, r2.cur); assertEquals(1, r2.best)
    }
}
