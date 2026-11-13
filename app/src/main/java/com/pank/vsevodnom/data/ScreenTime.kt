package com.pank.vsevodnom.data

import android.content.Context
import android.content.Intent
import com.pank.vsevodnom.data.device.AppScan
import java.time.LocalDate
import java.time.ZoneId

/**
 * Экранное время по дням (0.45) — для «Здоровья» и оценки жизни. Источник — статистика использования Android
 * (нужен доступ «Статистика использования»); рабочий стол не считается. Кэш на 10 минут.
 */
object ScreenTime {
    @Volatile private var cache: Pair<Long, Map<LocalDate, Long>>? = null

    fun available(ctx: Context) = AppScan.hasUsage(ctx)

    /** Минуты на экране по дням за последние [days] дней (сегодня — до текущего момента). */
    fun byDay(ctx: Context, days: Int = 35): Map<LocalDate, Long> {
        if (!available(ctx)) return emptyMap()
        cache?.let { (t, m) -> if (System.currentTimeMillis() - t < 10 * 60_000 && m.size >= minOf(days, 7)) return m }
        val home = runCatching { ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet() }.getOrDefault(emptySet())
        val z = ZoneId.systemDefault(); val today = LocalDate.now()
        val out = LinkedHashMap<LocalDate, Long>()
        for (k in days - 1 downTo 0) {
            val d = today.minusDays(k.toLong())
            val from = d.atStartOfDay(z).toInstant().toEpochMilli()
            val to = if (k == 0) System.currentTimeMillis() else d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
            val ms = runCatching { AppScan.screenTime(ctx, from, to).first.filter { it.pkg !in home && it.pkg != "com.android.systemui" }.sumOf { it.fgMs } }.getOrDefault(0L)
            if (ms > 0) out[d] = ms / 60_000
        }
        cache = System.currentTimeMillis() to out
        return out
    }

    /** Оценка 0…10 по среднему за день: до 2 ч — 10, дальше минус, 8 ч и больше — 0. */
    fun score(avgMin: Double): Double { val h = avgMin / 60; return (if (h <= 2) 10.0 else 10 - (h - 2) * 10 / 6).coerceIn(0.0, 10.0) }
}
