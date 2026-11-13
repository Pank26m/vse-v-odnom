package com.pank.vsevodnom.alarm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.app.NotificationCompat
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R
import com.pank.vsevodnom.data.AlarmEntity
import com.pank.vsevodnom.data.AppDb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Будильник 0.45: всё, чего нет в таблице alarm, — в prefs «alarm_ext» (JSON по id), база не меняется.
 * date — однократный на конкретный день (эпоха-день); sound — uri мелодии (пусто — системная);
 * weather — погода на экране; full — полноэкранный (нельзя свернуть, только выключить/решить задачу);
 * summary — после выключения уведомление «что до обеда»; mission — none/math/type/selfie; level — сложность примеров 0…4;
 * ramp — плавное нарастание громкости, секунд (0 — сразу громко); tags — теги будильника.
 */
data class AlarmExt(
    val date: Long? = null,
    val sound: String = "", val soundName: String = "",
    val weather: Boolean = true,
    val full: Boolean = true,
    val summary: Boolean = true,
    val mission: String = "none", val level: Int = 1,
    val ramp: Int = 0,
    val tags: List<String> = emptyList(),
) {
    fun json(): JSONObject = JSONObject().put("date", date ?: JSONObject.NULL).put("sound", sound).put("soundName", soundName).put("weather", weather).put("full", full)
        .put("summary", summary).put("mission", mission).put("level", level).put("ramp", ramp).put("tags", JSONArray(tags))
    companion object {
        fun of(o: JSONObject) = AlarmExt(
            date = if (o.isNull("date")) null else o.optLong("date"),
            sound = o.optString("sound"), soundName = o.optString("soundName"),
            weather = o.optBoolean("weather", true), full = o.optBoolean("full", true), summary = o.optBoolean("summary", true),
            mission = o.optString("mission", "none"), level = o.optInt("level", 1), ramp = o.optInt("ramp", 0),
            tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        )
    }
}

object AlarmExtStore {
    @Volatile private var map: Map<Long, AlarmExt> = emptyMap()
    @Volatile private var loaded = false
    private fun p(ctx: Context) = ctx.getSharedPreferences("alarm_ext", Context.MODE_PRIVATE)
    fun ensure(ctx: Context) {
        if (loaded) return
        map = runCatching { val o = JSONObject(p(ctx).getString("all", "{}")!!); o.keys().asSequence().associate { it.toLong() to AlarmExt.of(o.getJSONObject(it)) } }.getOrDefault(emptyMap())
        loaded = true
    }
    fun get(ctx: Context, id: Long): AlarmExt { ensure(ctx); return map[id] ?: AlarmExt() }
    /** Без контекста — для расчёта времени (если ещё не загружено — без даты). */
    fun peek(id: Long): AlarmExt? = map[id]
    fun set(ctx: Context, id: Long, e: AlarmExt) {
        ensure(ctx); map = map + (id to e); save(ctx)
    }
    fun remove(ctx: Context, id: Long) { ensure(ctx); map = map - id; save(ctx) }
    private fun save(ctx: Context) { val o = JSONObject(); map.forEach { (k, v) -> o.put(k.toString(), v.json()) }; p(ctx).edit().putString("all", o.toString()).apply() }
    fun allTags(ctx: Context): List<String> { ensure(ctx); return map.values.flatMap { it.tags }.distinct().sorted() }
}

/** Общие настройки будильника: отложить (вкл/минуты) — задаются заранее, не утром. */
object AlarmSettings {
    private fun p(ctx: Context) = ctx.getSharedPreferences("alarm_settings", Context.MODE_PRIVATE)
    fun snoozeOn(ctx: Context) = p(ctx).getBoolean("snooze_on", true)
    fun snoozeMin(ctx: Context) = p(ctx).getInt("snooze_min", 10)
    fun set(ctx: Context, on: Boolean, min: Int) = p(ctx).edit().putBoolean("snooze_on", on).putInt("snooze_min", min).apply()
    fun sort(ctx: Context) = p(ctx).getInt("sort", 0)
    fun setSort(ctx: Context, s: Int) = p(ctx).edit().putInt("sort", s).apply()
}

/** Задания, чтобы выключить будильник: примеры, перепечатать текст, селфи. */
object Missions {
    val LEVELS = listOf("Простейший", "Лёгкий", "Средний", "Сложный", "Безумный")
    data class Problem(val text: String, val answer: Long)

    /** Пример по уровню 0…4: от «3 + 4» до «(37 × 48) − (19 × 23)» и квадратов трёхзначных. */
    fun math(level: Int, r: Random = Random): Problem = when (level.coerceIn(0, 4)) {
        0 -> { val a = r.nextInt(1, 10); val b = r.nextInt(1, 10); Problem("$a + $b", (a + b).toLong()) }
        1 -> { val a = r.nextInt(12, 60); val b = r.nextInt(11, 40); if (r.nextBoolean()) Problem("$a + $b", (a + b).toLong()) else { val x = maxOf(a, b); val y = minOf(a, b); Problem("$x − $y", (x - y).toLong()) } }
        2 -> { val a = r.nextInt(12, 30); val b = r.nextInt(3, 10); val c = r.nextInt(10, 90); Problem("$a × $b + $c", (a * b + c).toLong()) }
        3 -> { val a = r.nextInt(13, 50); val b = r.nextInt(12, 40); val c = r.nextInt(100, 900); Problem("$a × $b − $c", (a * b - c).toLong()) }
        else -> if (r.nextBoolean()) { val a = r.nextInt(23, 99); val b = r.nextInt(23, 99); val c = r.nextInt(12, 60); val d = r.nextInt(12, 60); Problem("($a × $b) − ($c × $d)", (a * b - c * d).toLong()) }
                else { val a = r.nextInt(101, 400); Problem("$a²", (a.toLong() * a)) }
    }
    /** Сколько примеров решить. */
    fun count(level: Int) = if (level >= 4) 3 else if (level >= 2) 3 else 2

    val PHRASES = listOf(
        "Каждое утро — это новая возможность сделать что-то хорошее",
        "Я проснулся и готов к новому дню",
        "Сначала стакан воды, потом всё остальное",
        "Дисциплина — это мост между целями и достижениями",
        "Сегодня я сделаю то, что откладывал вчера",
        "Кто рано встаёт, тому весь день принадлежит",
        "Маленькие шаги каждый день дают большой результат",
        "Утро начинается не с кофе, а с решения встать",
        "Я выбираю бодрость, а не ещё пять минут сна",
        "Хорошее утро задаёт тон всему дню",
    )
    fun phrase(r: Random = Random) = PHRASES[r.nextInt(PHRASES.size)]
    /** Сравнение перепечатанного: без учёта регистра, лишних пробелов, ё/е и знаков препинания. */
    fun sameText(a: String, b: String): Boolean {
        fun n(s: String) = s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() || it == ' ' }.split(' ').filter { it.isNotBlank() }.joinToString(" ")
        return n(a) == n(b)
    }
}

/** Селфи будильника: files/alarm_selfies/yyyy-MM-dd_HHmm.jpg; коллаж за месяц — сеткой. */
object AlarmSelfies {
    fun dir(ctx: Context) = File(ctx.filesDir, "alarm_selfies").apply { mkdirs() }
    fun newFile(ctx: Context) = File(dir(ctx), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")) + ".jpg")
    fun all(ctx: Context): List<File> = dir(ctx).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()
    fun byMonth(ctx: Context): Map<YearMonth, List<File>> = all(ctx).groupBy { runCatching { YearMonth.parse(it.name.take(7)) }.getOrDefault(YearMonth.of(2000, 1)) }.toSortedMap(compareByDescending { it })

    /** Есть ли на фото лицо (детектор Android, без сети). */
    fun hasFace(path: String): Boolean = runCatching {
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inSampleSize = 4 }
        var b = BitmapFactory.decodeFile(path, o) ?: return false
        if (b.width % 2 == 1) b = Bitmap.createBitmap(b, 0, 0, b.width - 1, b.height)
        val faces = arrayOfNulls<android.media.FaceDetector.Face>(3)
        android.media.FaceDetector(b.width, b.height, 3).findFaces(b, faces) > 0
    }.getOrDefault(true)

    /** Коллаж месяца: квадратные плитки в сетке, подпись даты на каждой. */
    fun collage(files: List<File>, cell: Int = 360): Bitmap? {
        if (files.isEmpty()) return null
        val cols = kotlin.math.ceil(kotlin.math.sqrt(files.size.toDouble())).toInt().coerceAtLeast(1)
        val rows = (files.size + cols - 1) / cols
        val out = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(android.graphics.Color.WHITE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = cell * 0.09f; setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK) }
        files.forEachIndexed { i, f ->
            val o = BitmapFactory.Options().apply { inSampleSize = 4 }
            val b = BitmapFactory.decodeFile(f.absolutePath, o) ?: return@forEachIndexed
            val s = minOf(b.width, b.height)
            val src = android.graphics.Rect((b.width - s) / 2, (b.height - s) / 2, (b.width + s) / 2, (b.height + s) / 2)
            val x = (i % cols) * cell; val y = (i / cols) * cell
            c.drawBitmap(b, src, android.graphics.Rect(x + 2, y + 2, x + cell - 2, y + cell - 2), null)
            c.drawText(f.name.substring(8, 10) + "." + f.name.substring(5, 7) + " " + f.name.substring(11, 13) + ":" + f.name.substring(13, 15), x + cell * 0.05f, y + cell * 0.95f, text)
            b.recycle()
        }
        return out
    }
}

/** Утренняя сводка после выключения будильника: задачи до 12:00, пары и сколько всего дел на сегодня. */
object MorningSummary {
    fun post(ctx: Context) {
        runCatching {
            val today = LocalDate.now().toEpochDay()
            val tasks = AppDb.get(ctx).todo().tasksUntilSync(today).filter { it.dueDay == today }
            val beforeNoon = tasks.filter { (it.dueMinute ?: 9999) < 12 * 60 }.sortedBy { it.dueMinute }
            val lessons = runCatching { AppDb.get(ctx).schedule().lessonsSync(today, today) }.getOrDefault(emptyList())
            val lines = beforeNoon.map { t -> "%02d:%02d %s".format((t.dueMinute ?: 0) / 60, (t.dueMinute ?: 0) % 60, t.title) } +
                lessons.filter { (it.start.substringBefore(':').trim().toIntOrNull() ?: 99) < 12 }.map { "${it.start} пара: ${it.subject}" }
            val title = if (lines.isEmpty()) "Доброе утро! До обеда ничего не запланировано" else "Доброе утро! До 12:00 — ${lines.size}"
            val text = (lines.take(6) + if (tasks.size > beforeNoon.size) listOf("Всего задач на сегодня: ${tasks.size}") else emptyList()).joinToString("\n").ifBlank { "Посмотрите задачи на день в ToDo" }
            Channels.ensure(ctx)
            val open = PendingIntent.getActivity(ctx, 44, Intent(ctx, MainActivity::class.java).putExtra("route", "todo"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, Channels.REMINDER).setSmallIcon(R.drawable.ic_notify).setContentTitle(title)
                .setContentText(text.lineSequence().first()).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build()
            ctx.getSystemService(NotificationManager::class.java).notify(45, n)
        }
    }
}

/** Будильники, совпадающие по времени и дням (из разных тегов), — звенит один, в списке — одна строка. */
fun alarmKey(a: AlarmEntity, e: AlarmExt?): String = "${a.hour}:${a.minute}:${a.daysMask}:${e?.date ?: ""}"

/** Текст «через N» и сам момент срабатывания с учётом даты однократного будильника. */
fun nextTriggerOrNull(a: AlarmEntity, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
    val d = AlarmExtStore.peek(a.id)?.date ?: return nextTrigger(a, now)
    val at = LocalDate.ofEpochDay(d).atTime(LocalTime.of(a.hour, a.minute))
    return if (at.isAfter(now)) at else null
}
