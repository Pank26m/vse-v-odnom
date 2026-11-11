package com.goodwy.keyboard.vse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.goodwy.keyboard.FlorisImeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Работа с текстом поля целиком: проверка пунктуации и орфографии (LanguageTool — правила, без ИИ, бесплатный
 * сервер, нужен интернет) и перевод (Google ML Kit — на телефоне, словарь ~30 МБ скачивается один раз).
 * Перед заменой исходный текст кладётся в буфер обмена — всегда можно вернуть.
 */
object VseText {
    /** Весь текст поля: до курсора, выделение, после. */
    private fun fieldText(): Triple<String, String, String>? {
        val ic = FlorisImeService.currentInputConnection() ?: return null
        val before = ic.getTextBeforeCursor(20_000, 0)?.toString() ?: ""
        val sel = ic.getSelectedText(0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(20_000, 0)?.toString() ?: ""
        return Triple(before, sel, after)
    }

    /** Заменить весь текст поля на [new]. */
    private fun replaceField(before: String, sel: String, after: String, new: String) {
        val ic = FlorisImeService.currentInputConnection() ?: return
        ic.beginBatchEdit()
        ic.finishComposingText()
        if (sel.isNotEmpty()) ic.commitText("", 1)
        ic.deleteSurroundingText(before.length, after.length)
        ic.commitText(new, 1)
        ic.endBatchEdit()
    }

    private fun toClipboard(ctx: Context, label: String, text: String) = runCatching {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** Язык текста по буквам: і/ї/є/ґ — украинский, кириллица — русский, иначе английский. */
    fun langOf(t: String): String = when {
        t.any { it in "іїєґІЇЄҐ" } -> "uk"
        t.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' } > t.count { it in 'a'..'z' || it in 'A'..'Z' } -> "ru"
        else -> "en"
    }

    // ---------- пунктуация и орфография: LanguageTool ----------
    fun punctuate(ctx: Context) {
        val (b, s, a) = fieldText() ?: return
        val full = b + s + a
        if (full.isBlank()) { VseIme.status.value = "Поле пустое"; clear(); return }
        VseIme.status.value = "Проверяю текст…"
        VseIme.scope.launch {
            // сначала орфография (Яндекс.Спеллер — сильнее всего в русском/украинском), потом запятые и грамматика (LanguageTool)
            val res = runCatching { withContext(Dispatchers.IO) {
                val (t1, n1) = runCatching { yandex(full, langOf(full)) }.getOrDefault(full to 0)
                val (t2, n2) = runCatching { languageTool(t1, langOf(t1)) }.getOrElse { if (n1 > 0) t1 to 0 else throw it }
                t2 to (n1 + n2)
            } }
            res.onSuccess { (fixed, n) ->
                if (n == 0) VseIme.status.value = "Ошибок не нашёл"
                else { toClipboard(ctx, "Исходный текст", full); replaceField(b, s, a, fixed); VseIme.status.value = "Исправлено: $n (исходный — в буфере)" }
            }.onFailure { VseIme.status.value = "Проверка не удалась: ${it.message ?: "нет связи"}" }
            clear()
        }
    }

    /**
     * Яндекс.Спеллер: орфография ru/uk/en, бесплатно, без ключа. Текст — по предложениям (checkTexts): на длинном
     * тексте с множеством ошибок сервис возвращает пустой ответ. Правки — с конца, чтобы не сбить позиции.
     */
    private fun yandex(text: String, lang: String): Pair<String, Int> {
        val l = when (lang) { "uk" -> "uk,en"; "ru" -> "ru,en"; else -> "en" }
        // режем по концу предложения, сохраняя разделители
        val parts = ArrayList<String>(); var st = 0
        Regex("(?<=[.!?…])\\s+|\\n").findAll(text).forEach { m -> parts += text.substring(st, m.range.last + 1); st = m.range.last + 1 }
        if (st < text.length) parts += text.substring(st)
        val c = URL("https://speller.yandex.net/services/spellservice.json/checkTexts").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        val body = parts.joinToString("&") { "text=" + URLEncoder.encode(it, "UTF-8") } + "&lang=$l&options=14"
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode != 200) throw java.io.IOException("Спеллер ответил ${c.responseCode}")
        val res = org.json.JSONArray(c.inputStream.bufferedReader().readText())
        var n = 0
        val out = parts.mapIndexed { i, part ->
            val arr = res.optJSONArray(i) ?: return@mapIndexed part
            val fixes = (0 until arr.length()).mapNotNull { k ->
                val o = arr.getJSONObject(k); val sug = o.optJSONArray("s")?.optString(0) ?: return@mapNotNull null
                Triple(o.getInt("pos"), o.getInt("len"), sug)
            }.sortedByDescending { it.first }
            val sb = StringBuilder(part); var last = Int.MAX_VALUE
            for ((pos, len, rep) in fixes) {
                if (pos + len > last || pos + len > sb.length) continue
                val orig = sb.substring(pos, pos + len)
                val r = if (orig.firstOrNull()?.isUpperCase() == true) rep.replaceFirstChar { it.uppercase() } else rep
                sb.replace(pos, pos + len, r); last = pos; n++
            }
            sb.toString()
        }
        return out.joinToString("") to n
    }

    private fun languageTool(text: String, lang: String): Pair<String, Int> {
        val l = when (lang) { "uk" -> "uk-UA"; "ru" -> "ru-RU"; else -> "en-US" }
        val c = URL("https://api.languagetool.org/v2/check").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        c.outputStream.use { it.write(("text=" + URLEncoder.encode(text, "UTF-8") + "&language=$l").toByteArray()) }
        if (c.responseCode != 200) throw java.io.IOException("сервер ответил ${c.responseCode}")
        val matches = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("matches")
        val fixes = (0 until matches.length()).mapNotNull { i ->
            val m = matches.getJSONObject(i)
            val rep = m.optJSONArray("replacements")?.optJSONObject(0)?.optString("value") ?: return@mapNotNull null
            val off = m.getInt("offset"); val len = m.getInt("length")
            // орфографию уже поправил Спеллер; у LanguageTool из «опечаток» берём только дефис («что то» → «что-то»),
            // его догадки по словам часто хуже («дубарь» → «кубарь»)
            if (m.optJSONObject("rule")?.optJSONObject("category")?.optString("id") == "TYPOS") {
                val orig = text.substring(off, minOf(text.length, off + len))
                if (rep.replace('-', ' ') != orig) return@mapNotNull null
            }
            Triple(off, len, rep)
        }.sortedByDescending { it.first }
        val sb = StringBuilder(text)
        var last = Int.MAX_VALUE; var n = 0
        for ((off, len, rep) in fixes) {
            if (off + len > last || off + len > sb.length) continue   // пересекающиеся правки — пропускаем
            sb.replace(off, off + len, rep); last = off; n++
        }
        return sb.toString() to n
    }

    // ---------- перевод: Google ML Kit на телефоне ----------
    fun translate(ctx: Context, to: String) {
        val (b, s, a) = fieldText() ?: return
        val full = b + s + a
        if (full.isBlank()) { VseIme.status.value = "Поле пустое"; clear(); return }
        val from = langOf(full)
        if (from == to) { VseIme.status.value = "Текст уже на этом языке"; clear(); return }
        VseIme.status.value = "Перевожу… (первый раз скачивается словарь ~30 МБ)"
        VseIme.scope.launch {
            // онлайн — Google (как в «Google Переводчике»), запасной — MyMemory; без сети — словарь ML Kit на телефоне
            runCatching { withContext(Dispatchers.IO) { runCatching { google(full, from, to) }.getOrElse { myMemory(full, from, to) } } }
                .recoverCatching { viaService(ctx, full, from, to) }
                .onSuccess { out ->
                    toClipboard(ctx, "Исходный текст", full); replaceField(b, s, a, out)
                    VseIme.status.value = "Переведено (оригинал — в буфере)"
                }.onFailure { VseIme.status.value = "Перевод не удался: ${it.message ?: "ошибка"}" }
            clear()
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36")
        if (c.responseCode != 200) throw java.io.IOException("ответ ${c.responseCode}")
        return c.inputStream.bufferedReader().readText()
    }

    /** Google Переводчик (бесплатная точка, как у расширений браузера); длинный текст — частями по ~1500 знаков. */
    private fun google(text: String, from: String, to: String): String = chunks(text, 1500).joinToString("") { part ->
        val a = org.json.JSONArray(get("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&q=" + URLEncoder.encode(part, "UTF-8")))
        val seg = a.getJSONArray(0)
        (0 until seg.length()).joinToString("") { seg.getJSONArray(it).optString(0) }
    }

    private fun myMemory(text: String, from: String, to: String): String = chunks(text, 450).joinToString("") { part ->
        val o = JSONObject(get("https://api.mymemory.translated.net/get?langpair=$from|$to&q=" + URLEncoder.encode(part, "UTF-8")))
        o.getJSONObject("responseData").getString("translatedText")
    }

    /** Разрезать по предложениям/переводам строк, не длиннее n. */
    private fun chunks(t: String, n: Int): List<String> {
        if (t.length <= n) return listOf(t)
        val out = ArrayList<String>(); var start = 0
        while (start < t.length) {
            var end = minOf(t.length, start + n)
            if (end < t.length) { val cut = t.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), end - 1); if (cut > start) end = cut + 1 }
            out += t.substring(start, end); start = end
        }
        return out
    }

    /** Перевод через службу в основном процессе (ML Kit там запускается сам). До 3 минут — на скачивание словаря. */
    private suspend fun viaService(ctx: Context, text: String, from: String, to: String): String =
        kotlinx.coroutines.withTimeout(180_000) {
            suspendCancellableCoroutine { cont ->
                val app = ctx.applicationContext
                var conn: android.content.ServiceConnection? = null
                val reply = android.os.Messenger(object : android.os.Handler(android.os.Looper.getMainLooper()) {
                    override fun handleMessage(m: android.os.Message) {
                        val ok = m.data.getBoolean("ok"); val t = m.data.getString("text").orEmpty()
                        conn?.let { runCatching { app.unbindService(it) } }
                        if (!cont.isActive) return
                        if (ok) cont.resume(t) else cont.resumeWithException(Exception(t))
                    }
                })
                conn = object : android.content.ServiceConnection {
                    override fun onServiceConnected(n: android.content.ComponentName?, b: android.os.IBinder?) {
                        runCatching {
                            android.os.Messenger(b).send(android.os.Message.obtain(null, 1).apply {
                                data = android.os.Bundle().apply { putString("text", text); putString("from", from); putString("to", to) }
                                replyTo = reply
                            })
                        }.onFailure { if (cont.isActive) cont.resumeWithException(it) }
                    }
                    override fun onServiceDisconnected(n: android.content.ComponentName?) {}
                }
                val ok = app.bindService(android.content.Intent(app, TranslateService::class.java), conn!!, Context.BIND_AUTO_CREATE)
                if (!ok) cont.resumeWithException(Exception("служба перевода не запустилась"))
                cont.invokeOnCancellation { conn?.let { c -> runCatching { app.unbindService(c) } } }
            }
        }

    private suspend fun <T> await(t: com.google.android.gms.tasks.Task<T>): T? = suspendCancellableCoroutine { cont ->
        t.addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun clear() = VseIme.scope.launch { kotlinx.coroutines.delay(4000); VseIme.status.value = null }
}
