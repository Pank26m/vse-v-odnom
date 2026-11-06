package com.pank.vsevodnom.data

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

object Todo {
    /** Выполнить задачу; для повторяющихся — создать следующую. */
    suspend fun complete(ctx: Context, t: TaskItem) {
        val dao = AppDb.get(ctx).todo()
        dao.upsertTask(t.copy(done = true, doneAt = System.currentTimeMillis()))
        val d = t.dueDay?.let(LocalDate::ofEpochDay) ?: return
        val next = when (t.repeat) {
            "daily" -> d.plusDays(1)
            "weekly" -> d.plusWeeks(1)
            "monthly" -> d.plusMonths(1)
            "weekdays" -> generateSequence(d.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek.value <= 5 }
            else -> null
        } ?: return
        val id = dao.upsertTask(t.copy(id = 0, dueDay = next.toEpochDay(), done = false, doneAt = null, createdAt = System.currentTimeMillis()))
        scheduleReminder(ctx, t.copy(id = id, dueDay = next.toEpochDay()))
    }

    suspend fun undo(ctx: Context, t: TaskItem) = AppDb.get(ctx).todo().upsertTask(t.copy(done = false, doneAt = null))

    fun scheduleReminder(ctx: Context, t: TaskItem) {
        val day = t.dueDay ?: return
        val min = t.dueMinute ?: return
        val before = t.remindBefore ?: return
        val at = LocalDate.ofEpochDay(day).atStartOfDay(ZoneId.systemDefault()).plusMinutes(min.toLong() - before).toInstant().toEpochMilli()
        EventReminder.schedule(ctx, 5_000_000 + t.id, t.title, at)
    }

    /** +1 к привычке за день (по кругу: после выполнения — сброс). */
    suspend fun tickHabit(ctx: Context, h: Habit, day: LocalDate) {
        val dao = AppDb.get(ctx).todo()
        val cur = dao.habitLog(h.id, day.toEpochDay())
        val n = ((cur?.count ?: 0) + 1).let { if (it > h.perDay) 0 else it }
        dao.upsertHabitLog(HabitLog(id = cur?.id ?: 0, habitId = h.id, epochDay = day.toEpochDay(), count = n))
    }

    /** Серия: сколько запланированных дней подряд привычка выполнена (до вчера включительно + сегодня, если уже). */
    fun streak(h: Habit, logs: List<HabitLog>, today: LocalDate): Int {
        val done = logs.filter { it.habitId == h.id && it.count >= h.perDay }.map { it.epochDay }.toSet()
        var d = if (today.toEpochDay() in done) today else today.minusDays(1)
        var n = 0
        repeat(400) {
            if (h.daysMask and (1 shl (d.dayOfWeek.value - 1)) == 0) { d = d.minusDays(1); return@repeat }
            if (d.toEpochDay() in done) { n++; d = d.minusDays(1) } else return n
        }
        return n
    }
}
