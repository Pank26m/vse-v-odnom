package com.pank.vsevodnom.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.launch

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
            "yearly" -> d.plusYears(1)
            "weekdays" -> generateSequence(d.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek.value <= 5 }
            else -> null
        } ?: return
        val id = dao.upsertTask(t.copy(id = 0, dueDay = next.toEpochDay(), done = false, doneAt = null, createdAt = System.currentTimeMillis()))
        scheduleReminder(ctx, t.copy(id = id, dueDay = next.toEpochDay()))
    }

    suspend fun undo(ctx: Context, t: TaskItem) = AppDb.get(ctx).todo().upsertTask(t.copy(done = false, doneAt = null))

    fun scheduleReminder(ctx: Context, t: TaskItem) {
        val day = t.dueDay ?: return
        val before = t.remindBefore ?: return
        // задача на весь день — напоминаем в 9:00 (или за N дней в 9:00)
        val min = t.dueMinute ?: 540
        val at = LocalDate.ofEpochDay(day).atStartOfDay(ZoneId.systemDefault()).plusMinutes(min.toLong() - before).toInstant().toEpochMilli()
        EventReminder.schedule(ctx, 5_000_000 + t.id, t.title, at)
    }

    /** Нужно ли делать привычку в этот день. */
    fun isDue(h: Habit, day: LocalDate, logs: List<HabitLog> = emptyList()): Boolean {
        if (h.startDay != null && day.toEpochDay() < h.startDay) return false
        if (h.goalDays != null && h.startDay != null && day.toEpochDay() >= h.startDay + h.goalDays) return false
        return when (h.freqType) {
            1 -> { // N раз в неделю: пока норма недели не выполнена (или сегодня уже отмечено)
                val mon = day.minusDays((day.dayOfWeek.value - 1).toLong()).toEpochDay()
                val doneDays = logs.filter { it.habitId == h.id && it.count >= h.perDay && it.epochDay in mon..(mon + 6) && it.epochDay != day.toEpochDay() }.size
                doneDays < h.freqN || logs.any { it.habitId == h.id && it.epochDay == day.toEpochDay() && it.count > 0 }
            }
            2 -> ((day.toEpochDay() - (h.startDay ?: (h.createdAt / 86_400_000L))) % h.freqN.coerceAtLeast(1)) == 0L
            else -> h.daysMask and (1 shl (day.dayOfWeek.value - 1)) != 0
        }
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
            if (h.freqType == 0 && h.daysMask and (1 shl (d.dayOfWeek.value - 1)) == 0 || h.freqType == 2 && !isDue(h, d)) { d = d.minusDays(1); return@repeat }
            if (d.toEpochDay() in done) { n++; d = d.minusDays(1) } else return n
        }
        return n
    }
}

/** Напоминания привычек: ежедневный будильник на каждое время; в день, когда привычка не нужна, — молчит. */
object HabitReminders {
    private fun pi(ctx: Context, h: Habit, i: Int, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT) = PendingIntent.getBroadcast(ctx, 7_000_000 + (h.id * 8 + i).toInt(),
        Intent(ctx, HabitReminderReceiver::class.java).putExtra("id", h.id), flags or PendingIntent.FLAG_IMMUTABLE)

    fun times(h: Habit): List<Int> = (h.reminders.split(',').mapNotNull { it.trim().toIntOrNull() } + listOfNotNull(h.remindMinute)).distinct().take(8)

    fun schedule(ctx: Context, h: Habit) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (i in 0 until 8) pi(ctx, h, i, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
        if (h.archived) return
        times(h).forEachIndexed { i, m ->
            var at = LocalDate.now().atStartOfDay().plusMinutes(m.toLong())
            if (at.isBefore(LocalDateTime.now())) at = at.plusDays(1)
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), AlarmManager.INTERVAL_DAY, pi(ctx, h, i))
        }
    }

    fun rescheduleAll(ctx: Context) = runCatching { AppDb.get(ctx).todo().habitsSync().forEach { schedule(ctx, it) } }
}

class HabitReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0)
        val pr = goAsync()
        AppScope.io.launch {
            try {
                val dao = AppDb.get(ctx).todo()
                val h = dao.habitsSync().firstOrNull { it.id == id } ?: return@launch
                val today = LocalDate.now()
                val logs = dao.habitLogsSync(today.toEpochDay())
                if (!Todo.isDue(h, today, logs)) return@launch
                if ((logs.firstOrNull { it.habitId == id }?.count ?: 0) >= h.perDay) return@launch
                EventReminder.notify(ctx, "${h.icon} ${h.title}".trim(), h.goal.ifBlank { "Привычка на сегодня" }, 6000 + (id % 1000).toInt())
            } finally { pr.finish() }
        }
    }
}
