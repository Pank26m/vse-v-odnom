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
            val res = runCatching { withContext(Dispatchers.IO) { languageTool(full, langOf(full)) } }
            res.onSuccess { (fixed, n) ->
                if (n == 0) VseIme.status.value = "Ошибок не нашёл"
                else { toClipboard(ctx, "Исходный текст", full); replaceField(b, s, a, fixed); VseIme.status.value = "Исправлено: $n (исходный — в буфере)" }
            }.onFailure { VseIme.status.value = "Проверка не удалась: ${it.message ?: "нет связи"}" }
            clear()
        }
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
            Triple(m.getInt("offset"), m.getInt("length"), rep)
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
            runCatching {
                com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(ctx.applicationContext)
                val code = { l: String -> when (l) { "uk" -> com.google.mlkit.nl.translate.TranslateLanguage.UKRAINIAN; "ru" -> com.google.mlkit.nl.translate.TranslateLanguage.RUSSIAN; else -> com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH } }
                val tr = com.google.mlkit.nl.translate.Translation.getClient(
                    com.google.mlkit.nl.translate.TranslatorOptions.Builder().setSourceLanguage(code(from)).setTargetLanguage(code(to)).build())
                try {
                    await(tr.downloadModelIfNeeded(com.google.mlkit.common.model.DownloadConditions.Builder().build()))
                    await(tr.translate(full))
                } finally { tr.close() }
            }.onSuccess { out ->
                toClipboard(ctx, "Исходный текст", full); replaceField(b, s, a, out ?: full)
                VseIme.status.value = "Переведено (оригинал — в буфере)"
            }.onFailure { VseIme.status.value = "Перевод не удался: ${it.message ?: "ошибка"}" }
            clear()
        }
    }

    private suspend fun <T> await(t: com.google.android.gms.tasks.Task<T>): T? = suspendCancellableCoroutine { cont ->
        t.addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun clear() = VseIme.scope.launch { kotlinx.coroutines.delay(4000); VseIme.status.value = null }
}
