package com.goodwy.keyboard.vse

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.launch

/**
 * Резервная копия набранного: пока пишешь, текст поля сохраняется (раз в ~1,5 с после паузы) в files/kbd_drafts.json.
 * Свернул Telegram, вернулся — текста нет: «Буфер обмена» → значок часов → «Набранное». Хранится сутки.
 * Не сохраняется: поля паролей и «инкогнито»-поля (приложение просит не запоминать ввод).
 */
object VseDrafts {
    data class Draft(val pkg: String, val field: String, val text: String, val at: Long)

    private const val DAY = 24 * 3600_000L
    private const val MAX = 60
    @Volatile private var key = ""
    @Volatile var enabled = true
    private var lastSaved = ""
    private var job: kotlinx.coroutines.Job? = null

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "kbd_drafts.json")

    /** Новое поле ввода. */
    fun enter(pkg: String, fieldId: Int, hint: String?, allowed: Boolean) {
        key = "$pkg|$fieldId|${hint.orEmpty().take(40)}"; enabled = allowed; lastSaved = ""
    }

    /** Текст поля изменился. */
    fun onText(ctx: Context, text: String) {
        if (!enabled || key.isEmpty()) return
        val t = text.trim()
        if (t.length < 3 || t == lastSaved) return
        job?.cancel()
        val k = key
        job = VseIme.scope.launch {
            kotlinx.coroutines.delay(1500)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { save(ctx, k, t) }
            lastSaved = t
        }
    }

    @Synchronized private fun save(ctx: Context, k: String, t: String) {
        val now = System.currentTimeMillis()
        val l = load(ctx).toMutableList()
        val pkg = k.substringBefore('|')
        // то же поле: дописываем/правим — заменяем последнюю запись; начал новое сообщение — новая запись
        val i = l.indexOfFirst { it.field == k }
        val prev = l.getOrNull(i)
        if (prev != null && (t.startsWith(prev.text.take(12)) || prev.text.startsWith(t.take(12))) && now - prev.at < 3 * 3600_000L) {
            // стёр часть — не теряем более длинный вариант: он остаётся, пока не набрано другое
            if (t.length >= prev.text.length - 3 || !prev.text.startsWith(t)) l[i] = prev.copy(text = t, at = now)
        } else l.add(0, Draft(pkg, k, t, now))
        write(ctx, l.sortedByDescending { it.at }.filter { now - it.at < DAY }.take(MAX))
    }

    fun load(ctx: Context): List<Draft> = runCatching {
        val a = JSONArray(file(ctx).readText()); val now = System.currentTimeMillis()
        (0 until a.length()).map { a.getJSONObject(it) }.map { Draft(it.optString("p"), it.optString("f"), it.optString("t"), it.optLong("a")) }.filter { now - it.at < DAY }
    }.getOrDefault(emptyList())

    fun remove(ctx: Context, d: Draft) = write(ctx, load(ctx).filterNot { it.field == d.field && it.at == d.at })
    fun clear(ctx: Context) { runCatching { file(ctx).delete() } }

    private fun write(ctx: Context, l: List<Draft>) = runCatching {
        file(ctx).writeText(JSONArray().apply { l.forEach { put(JSONObject().put("p", it.pkg).put("f", it.field).put("t", it.text).put("a", it.at)) } }.toString())
    }

    fun appName(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
