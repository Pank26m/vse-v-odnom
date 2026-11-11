package com.goodwy.keyboard.vse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.goodwy.keyboard.FlorisImeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

    fun toClipboard(ctx: Context, label: String, text: String) = runCatching {
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
            var who1 = "Яндекс"; var who2 = "LanguageTool"
            val res = runCatching { withContext(Dispatchers.IO) {
                // 1) орфография: Яндекс.Спеллер; недоступен (в Украине часто заблокирован) — словарь на телефоне
                val (t1, n1) = runCatching { yandex(full, langOf(full)) }.getOrElse { who1 = "словарь телефона"; offlineFix(ctx, full) }
                // 2) запятые, заглавные, дефисы: LanguageTool
                val (t2, n2) = runCatching { languageTool(t1, langOf(t1)) }.getOrElse { who2 = "—"; t1 to 0 }
                Triple(t2, n1, n2)
            } }
            res.onSuccess { (fixed, n1, n2) ->
                if (n1 + n2 == 0) VseIme.status.value = "Ошибок не нашёл (орфография: $who1, запятые: $who2)"
                else {
                    toClipboard(ctx, "Исходный текст", full); replaceField(b, s, a, fixed)
                    VseIme.status.value = "Исправлено: орфография $n1 ($who1), запятые и прочее $n2 ($who2). Исходный — в буфере"
                }
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

    /** Без интернета: каждое слово не из словаря → самый частый вариант с одной правкой; «невиделись» → «не виделись». */
    fun offlineFix(ctx: Context, text: String): Pair<String, Int> = offlineFixIn({ VseSpell.dict(ctx, it) }, text)

    fun offlineFixIn(dict: (String) -> KDict?, text: String): Pair<String, Int> {
        var n = 0
        // язык — по всему тексту: в русском тексте «сонце», «гарячий» — ошибки, хоть по-украински и правильно
        val cyr = Regex("[А-Яа-яЁёІіЇїЄєҐґ]").containsMatchIn(text)
        val textLangs = when {
            Regex("[іїєґІЇЄҐ]").containsMatchIn(text) -> listOf("uk")
            cyr -> listOf("ru")
            else -> listOf("en")
        }
        val out = Regex("[A-Za-zА-Яа-яЁёІіЇїЄєҐґ'’]+").replace(text) { m ->
            val w = m.value
            val langs = if (w.any { it in 'a'..'z' || it in 'A'..'Z' }) listOf("en") else textLangs
            if (VseSpell.okLangs(dict, w, langs)) return@replace w
            val low = w.lowercase()
            // слитное «не» с глаголом: «невиделись» → «не виделись»
            if (low.startsWith("не") && low.length > 4 && VseSpell.okLangs(dict, low.drop(2), langs)) { n++; return@replace w.take(2) + " " + w.drop(2) }
            val s = VseSpell.bestIn(dict, w, langs) ?: return@replace w
            n++; s
        }
        return out to n
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
        VseIme.status.value = "Перевожу…"
        VseIme.scope.launch {
            // онлайн: Google (как «Google Переводчик»), запасной — MyMemory. Ошибки обоих показываем как есть.
            val res = withContext(Dispatchers.IO) {
                runCatching { google(full, from, to) }.recoverCatching { e1 ->
                    runCatching { myMemory(full, from, to) }.getOrElse { e2 -> throw Exception("Google: ${e1.message ?: e1.javaClass.simpleName}; MyMemory: ${e2.message ?: e2.javaClass.simpleName}") }
                }
            }
            res.onSuccess { out ->
                toClipboard(ctx, "Исходный текст", full); replaceField(b, s, a, out)
                VseIme.status.value = "Переведено (оригинал — в буфере)"
            }.onFailure { VseIme.status.value = "Перевод не удался: ${it.message}" }
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

    private fun clear() = VseIme.scope.launch { kotlinx.coroutines.delay(4000); VseIme.status.value = null }
}
