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
        val n = ((cur?.count ?: 0).coerceAtLeast(0) + 1).let { if (it > h.perDay) 0 else it }   // старые «штрафы» −1 считаются как 0
        dao.upsertHabitLog(HabitLog(id = cur?.id ?: 0, habitId = h.id, epochDay = day.toEpochDay(), count = n))
    }

    /** Сколько раз в неделю (с 0.29 привычки без привязки к дням: 2, 3, 5, 6 раз или каждый день). */
    fun timesPerWeek(h: Habit): Int = when (h.freqType) { 1 -> h.freqN.coerceIn(1, 7); 0 -> Integer.bitCount(h.daysMask and 0b1111111).coerceAtLeast(1); else -> 7 }
    fun freqLabel(h: Habit): String = timesPerWeek(h).let { n -> if (n >= 7) "каждый день" else "$n раза в неделю".replace("5 раза", "5 раз").replace("6 раза", "6 раз").replace("1 раза", "1 раз") }

    /** Серия сейчас, рекорд и «простительные» пропуски (штрафы). */
    data class Runs(val cur: Int, val best: Int, val excused: Set<Long>)

    /**
     * Серия по правилам владельца (0.45): штраф — это просто пропущенный нужный день, ставить его не надо.
     * Пропуск прощается, если укладывается в лимиты `HabitPenalty` (в неделю, в месяц, подряд) — тогда серия
     * не обрывается; в серию идут только выполненные дни. Пример: 2 в месяц, подряд 1; 9 ✓ 10 ✓ 11 ✗ 12 ✓ 13 ✓ → серия 4.
     * Если 11 и 12 оба пропущены — второй пропуск сверх «подряд 1» → серия рвётся: рекорд 2, сейчас 1.
     * Сегодняшний день, пока не отмечен, серию не рвёт.
     */
    fun runs(h: Habit, logs: List<HabitLog>, today: LocalDate): Runs {
        val done = logs.filter { it.habitId == h.id && it.count >= h.perDay }.map { it.epochDay }.toSet()
        val rule = HabitPenalty.rule(h.id)
        val first = listOfNotNull(h.startDay, h.createdAt / 86_400_000L, done.minOrNull()).min().coerceAtMost(today.toEpochDay())
        val start = LocalDate.ofEpochDay(maxOf(first, today.toEpochDay() - 800))
        val excused = HashSet<Long>()
        if (h.freqType == 1) {
            // N раз в неделю: неделя засчитана, если норма набрана с учётом разрешённых пропусков в неделю
            val n0 = h.freqN.coerceIn(1, 7)
            var mon = start.minusDays((start.dayOfWeek.value - 1).toLong())
            val curMon = today.minusDays((today.dayOfWeek.value - 1).toLong())
            var cur = 0; var best = 0
            while (!mon.isAfter(curMon)) {
                val c = (0..6).count { mon.plusDays(it.toLong()).toEpochDay() in done }
                val allow = if (rule.any) (if (rule.perWeek > 0) rule.perWeek else 1) else 0
                when {
                    mon == curMon -> cur += c                       // текущая неделя ещё идёт
                    c >= n0 -> cur += c
                    c + allow >= n0 -> { cur += c; excused += (0..6).map { mon.plusDays(it.toLong()).toEpochDay() }.filter { it !in done }.take(n0 - c) }
                    else -> cur = 0
                }
                best = maxOf(best, cur)
                mon = mon.plusWeeks(1)
            }
            return Runs(cur, best, excused)
        }
        var cur = 0; var best = 0; var row = 0
        val perMonth = HashMap<Int, Int>(); val perWeek = HashMap<Long, Int>()
        var d = start
        while (!d.isAfter(today)) {
            if (isDue(h, d, logs)) {
                val e = d.toEpochDay()
                when {
                    e in done -> { cur++; row = 0; best = maxOf(best, cur) }
                    d == today -> {}
                    else -> {
                        row++
                        val ym = d.year * 12 + d.monthValue; val wk = (e + 3).floorDiv(7)
                        val m = (perMonth[ym] ?: 0) + 1; perMonth[ym] = m
                        val w = (perWeek[wk] ?: 0) + 1; perWeek[wk] = w
                        val ok = rule.any && row <= rule.inRow.coerceAtLeast(1) && (rule.perMonth == 0 || m <= rule.perMonth) && (rule.perWeek == 0 || w <= rule.perWeek)
                        if (ok) excused += e else cur = 0
                    }
                }
            }
            d = d.plusDays(1)
        }
        return Runs(cur, best, excused)
    }

    fun streak(h: Habit, logs: List<HabitLog>, today: LocalDate): Int = runs(h, logs, today).cur

    /** Поставить/снять штраф за день (count = −1; снять — 0). */
    suspend fun setPenalty(ctx: Context, h: Habit, day: LocalDate, on: Boolean) {
        val dao = AppDb.get(ctx).todo()
        val cur = dao.habitLog(h.id, day.toEpochDay())
        dao.upsertHabitLog(HabitLog(id = cur?.id ?: 0, habitId = h.id, epochDay = day.toEpochDay(), count = if (on) -1 else 0))
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
                if (!NotifPrefs.on(ctx, "habits")) return@launch
                EventReminder.notify(ctx, "${h.icon} ${h.title}".trim(), h.goal.ifBlank { "Привычка на сегодня" }, 6000 + (id % 1000).toInt())
            } finally { pr.finish() }
        }
    }
}

/**
 * «Разрешено штрафов» у привычки (prefs habit_pen): сколько в неделю, сколько в месяц и сколько дней подряд.
 * Штраф — день, когда привычка не выполнена «по уважительной причине» (раз в месяц погулял с друзьями):
 * серия не растёт, но и не сбрасывается. Подряд — чтобы нельзя было поставить 30-е, 31-е, 1-е и 2-е число.
 */
object HabitPenalty {
    data class Rule(val perWeek: Int = 0, val perMonth: Int = 0, val inRow: Int = 1) { val any get() = perWeek > 0 || perMonth > 0 }
    @Volatile private var map: Map<Long, Rule> = emptyMap()
    @Volatile private var loaded = false
    private fun p(ctx: Context) = ctx.getSharedPreferences("habit_pen", Context.MODE_PRIVATE)
    fun ensure(ctx: Context) {
        if (loaded) return
        map = runCatching { val o = org.json.JSONObject(p(ctx).getString("rules", "{}")!!)
            o.keys().asSequence().associate { k -> val r = o.getJSONObject(k); k.toLong() to Rule(r.optInt("w"), r.optInt("m"), r.optInt("row", 1)) } }.getOrDefault(emptyMap())
        loaded = true
    }
    fun rule(id: Long): Rule = map[id] ?: Rule()
    /** Для тестов: правило без SharedPreferences. */
    internal fun putForTest(id: Long, r: Rule) { map = map + (id to r); loaded = true }
    fun set(ctx: Context, id: Long, r: Rule) {
        ensure(ctx)
        map = map + (id to r)
        val o = org.json.JSONObject(); map.forEach { (k, v) -> o.put(k.toString(), org.json.JSONObject().put("w", v.perWeek).put("m", v.perMonth).put("row", v.inRow)) }
        p(ctx).edit().putString("rules", o.toString()).apply()
    }
    /** Укладывается ли штраф в день d: считаются штрафы этой недели и месяца до d включительно и серия штрафов подряд. */
    fun allowed(r: Rule, pens: Set<Long>, d: LocalDate): Boolean {
        if (!r.any) return false
        val mon = d.minusDays((d.dayOfWeek.value - 1).toLong())
        val inWeek = (mon.toEpochDay()..d.toEpochDay()).count { it in pens }
        val inMonth = (d.withDayOfMonth(1).toEpochDay()..d.toEpochDay()).count { it in pens }
        var row = 0; var x = d
        while (x.toEpochDay() in pens && row < 40) { row++; x = x.minusDays(1) }
        return (r.perWeek == 0 || inWeek <= r.perWeek) && (r.perMonth == 0 || inMonth <= r.perMonth) && row <= r.inRow.coerceAtLeast(1)
    }
}
