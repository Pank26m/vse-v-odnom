package com.pank.vsevodnom.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Отрезок без света: минуты от полуночи. */
data class Outage(val day: LocalDate, val fromMin: Int, val toMin: Int) {
    fun label() = "%02d:%02d–%02d:%02d".format(fromMin / 60, fromMin % 60, (toMin / 60) % 24, toMin % 60)
}

data class PowerState(
    val group: String,
    val outages: List<Outage>,          // сегодня и завтра
    val emergency: Boolean,             // экстренные отключения — график не действует
    val source: String,
    val fetchedAt: Long,
    val possible: List<Outage> = emptyList(),   // «возможные» отключения (серым в графике)
) {
    private val nowMin get() = LocalTime.now().let { it.hour * 60 + it.minute }
    val offNow get() = outages.any { it.day == LocalDate.now() && nowMin in it.fromMin until it.toMin }

    /** Минуты до включения, если сейчас света нет (идущие подряд отключения склеиваются, завтрашние тоже). */
    val nextOnInMin: Int? get() {
        if (!offNow) return null
        val today = LocalDate.now()
        val abs = outages.map { (if (it.day == today) 0 else 1440 * (it.day.toEpochDay() - today.toEpochDay()).toInt()).let { d -> (it.fromMin + d) to (it.toMin + d) } }.sortedBy { it.first }
        var end = abs.first { nowMin in it.first until it.second }.second
        abs.forEach { (a, b) -> if (a <= end && b > end) end = b }
        return end - nowMin
    }

    /** Строка для сводки: ближайшее включение или отключение. */
    fun nearestText(): String {
        if (emergency) return "Экстренные отключения — график не действует"
        nextOnInMin?.let { return "Ближайшее включение через ${fmtMin(it)}" }
        val today = LocalDate.now()
        val next = outages.filter { it.day == today && it.fromMin > nowMin }.minByOrNull { it.fromMin }
            ?: outages.filter { it.day == today.plusDays(1) }.minByOrNull { it.fromMin }
        next ?: return "Отключений по графику нет"
        val inMin = next.fromMin - nowMin + if (next.day == today) 0 else 1440
        return "Ближайшее отключение через ${fmtMin(inMin)} (в %02d:%02d)".format(next.fromMin / 60, next.fromMin % 60)
    }
    val nextOffInMin: Int? get() = outages.filter { it.day == LocalDate.now() && it.fromMin > nowMin }.minOfOrNull { it.fromMin - nowMin }
    companion object { fun fmtMin(m: Int) = if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин" }
    val todaySlots get() = outages.filter { it.day == LocalDate.now() && it.toMin > nowMin }.map { it.label() }
}

/**
 * График отключений ДТЭК Киевские электросети (YASNO).
 * Официального открытого API нет — используем публичный эндпоинт, которым пользуется сайт YASNO.
 * Если он не ответил — показываем последнее, что было, и ссылки на сайты ДТЭК и YASNO.
 */
object Power {
    var state by mutableStateOf<PowerState?>(null)

    private fun p(ctx: Context) = ctx.getSharedPreferences("power", Context.MODE_PRIVATE)
    fun group(ctx: Context) = p(ctx).getString("group", "")!!
    fun setGroup(ctx: Context, g: String) = p(ctx).edit().putString("group", g.trim()).apply()
    fun notify(ctx: Context) = p(ctx).getBoolean("notify", true)
    fun setNotify(ctx: Context, b: Boolean) = p(ctx).edit().putBoolean("notify", b).apply()
    /** Не беспокоить с 23:00 до 08:00 — напоминания на это время не ставятся. */
    fun quiet(ctx: Context) = p(ctx).getBoolean("quiet", true)
    fun setQuiet(ctx: Context, b: Boolean) = p(ctx).edit().putBoolean("quiet", b).apply()

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000; c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) vse-v-odnom")
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Разбор ответа YASNO: { "3.1": { "today": { "slots": [{start, end, type}], "status": … }, "tomorrow": {…} } }. */
    fun parseYasno(json: String, group: String, today: LocalDate): Pair<List<Outage>, Boolean> = parseYasnoFull(json, group, today).let { it.first to it.second }

    /** То же, плюс «возможные» отключения. */
    fun parseYasnoFull(json: String, group: String, today: LocalDate): Triple<List<Outage>, Boolean, List<Outage>> {
        val root = JSONObject(json)
        val g = root.optJSONObject(group) ?: root.optJSONObject(group.replace('.', '_')) ?: throw RuntimeException("Нет очереди $group в ответе")
        val out = ArrayList<Outage>(); val maybe = ArrayList<Outage>(); var emergency = false
        listOf("today" to today, "tomorrow" to today.plusDays(1)).forEach { (k, d) ->
            val day = g.optJSONObject(k) ?: return@forEach
            val status = day.optString("status")
            if (status.contains("Emergency", true)) emergency = true
            val slots = day.optJSONArray("slots") ?: return@forEach
            for (i in 0 until slots.length()) {
                val s = slots.getJSONObject(i)
                val type = s.optString("type")
                if (type.equals("Definite", true) || type.contains("Off", true)) out += Outage(d, s.optInt("start"), s.optInt("end"))
                else if (type.contains("Possib", true) || type.contains("Maybe", true)) maybe += Outage(d, s.optInt("start"), s.optInt("end"))
            }
        }
        return Triple(out.sortedWith(compareBy({ it.day }, { it.fromMin })), emergency, maybe)
    }

    suspend fun refresh(ctx: Context, force: Boolean = false): PowerState? = withContext(Dispatchers.IO) {
        state?.let { if (!force && System.currentTimeMillis() - it.fetchedAt < 10 * 60_000) return@withContext it }
        val group = group(ctx)
        val today = LocalDate.now()
        if (group.isBlank()) { state = null; return@withContext null }
        val st = runCatching {
            val (o, em, maybe) = parseYasnoFull(get("https://app.yasno.ua/api/blackout-service/public/shutdowns/regions/25/dsos/902/planned-outages"), group, today)
            PowerState(group, o, em, "YASNO", System.currentTimeMillis(), maybe)
        }.getOrElse { e ->
            // нет связи — оставляем прошлый график, если он про ту же очередь
            state?.takeIf { it.group == group }?.copy(source = "YASNO (нет связи: ${e.message})") ?: PowerState(group, emptyList(), false, "нет связи с YASNO", System.currentTimeMillis())
        }
        state = st
        scheduleReminders(ctx, st)
        st
    }

    /** Напоминание за 30 минут до каждого отключения — только если нет экстренных (тогда график не действует). */
    private fun scheduleReminders(ctx: Context, st: PowerState) {
        if (!notify(ctx)) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val z = ZoneId.systemDefault()
        st.outages.take(12).forEachIndexed { i, o ->
            val at = o.day.atStartOfDay(z).plusMinutes(o.fromMin - 30L).toInstant().toEpochMilli()
            val pi = PendingIntent.getBroadcast(ctx, 300 + i, Intent(ctx, PowerReminderReceiver::class.java).putExtra("slot", o.label()),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val h = o.day.atStartOfDay(z).plusMinutes(o.fromMin - 30L).hour
            val night = quiet(ctx) && (h >= 23 || h < 8)
            if (st.emergency || night || at < System.currentTimeMillis()) am.cancel(pi)
            else runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }.onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }
}

class PowerReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        EventReminder.notify(ctx, "Через 30 минут отключат свет", "По графику: ${intent.getStringExtra("slot")}. Заряди телефон и павербанк.", 3100)
    }
}

/** Праздники и памятные дни Украины (во время военного положения выходных нет). */
object Holidays {
    private fun orthodoxEaster(y: Int): LocalDate {
        val a = y % 4; val b = y % 7; val c = y % 19
        val d = (19 * c + 15) % 30; val e = (2 * a + 4 * b - d + 34) % 7
        val month = (d + e + 114) / 31; val day = (d + e + 114) % 31 + 1
        return LocalDate.of(y, month, day).plusDays(13) // юлианская дата → григорианская
    }

    fun all(year: Int): Map<LocalDate, String> {
        val easter = orthodoxEaster(year)
        val mothers = LocalDate.of(year, 5, 1).with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(2, java.time.DayOfWeek.SUNDAY))
        return mapOf(
            LocalDate.of(year, 1, 1) to "Новый год",
            LocalDate.of(year, 2, 14) to "День святого Валентина",
            LocalDate.of(year, 3, 8) to "Международный женский день",
            easter to "Пасха",
            easter.plusDays(49) to "Троица",
            mothers to "День матери",
            LocalDate.of(year, 5, 8) to "День памяти и победы над нацизмом",
            LocalDate.of(year, 6, 28) to "День Конституции Украины",
            LocalDate.of(year, 7, 15) to "День украинской государственности",
            LocalDate.of(year, 8, 24) to "День Независимости Украины",
            LocalDate.of(year, 10, 1) to "День защитников и защитниц Украины",
            LocalDate.of(year, 12, 6) to "День Вооружённых сил Украины",
            LocalDate.of(year, 12, 25) to "Рождество",
            LocalDate.of(year, 12, 31) to "Канун Нового года",
        )
    }

    fun on(d: LocalDate): String? = all(d.year)[d]
}
