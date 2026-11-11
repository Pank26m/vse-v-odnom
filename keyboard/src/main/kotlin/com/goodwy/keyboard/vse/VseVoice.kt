package com.goodwy.keyboard.vse

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.goodwy.keyboard.FlorisImeService
import com.goodwy.keyboard.subtypeManager
import kotlinx.coroutines.launch

/**
 * Голосовой ввод прямо в клавиатуре: распознавание Google на телефоне (на Pixel — без интернета) с расстановкой
 * знаков препинания (Android 13+). Нажал микрофон — говоришь, текст вставляется по фразам; нажал ещё раз — стоп.
 * Своего распознавания у Claude для приложений нет — это лучший бесплатный вариант.
 */
object VseVoice {
    private var rec: SpeechRecognizer? = null
    @Volatile var listening = false
        private set

    fun toggle(ctx: Context) { if (listening) stop() else start(ctx) }

    /** Последний промежуточный текст фразы и то, что из этой фразы уже вставлено. */
    private var lastPartial = ""
    private var sessionCommitted = ""
    private var appCtx: Context? = null

    /** Вставить с пробелом, если нужно. */
    private fun put(ctx: Context, t: String) {
        val text = t.trim(); if (text.isEmpty()) return
        val prev = FlorisImeService.currentInputConnection()?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val sep = if (prev.isEmpty() || prev.endsWith(" ") || prev.endsWith("\n")) "" else " "
        VseIme.commit(ctx, sep + text)
    }

    fun stop() {
        // результат после stopListening уже не придёт (распознаватель закрываем) — вставляем то, что успели сказать
        appCtx?.let { c -> if (lastPartial.isNotBlank()) put(c, lastPartial.removePrefix(sessionCommitted).ifBlank { "" }) }
        lastPartial = ""; sessionCommitted = ""
        listening = false
        runCatching { rec?.stopListening(); rec?.destroy() }
        rec = null
        VseIme.status.value = null
    }

    private fun start(ctx: Context) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            VseIme.status.value = "Нужен доступ к микрофону: открой «Всё в одном» → Диктофон (разрешение общее)"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { FlorisImeService.switchToVoiceInputMethod(); return }
        listening = true
        appCtx = ctx.applicationContext
        listen(ctx)
    }

    /** Распознавание на телефоне не знает этот язык (ошибка 12/13) — дальше онлайн-распознавание Google. */
    private var onlineOnly = false

    private fun listen(ctx: Context) {
        runCatching { rec?.destroy() }
        val onDevice = !onlineOnly && android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        val sm by ctx.subtypeManager()
        // полный код языка: «ru» → «ru-RU» (с коротким кодом распознавание отвечает «язык не поддерживается», ошибка 12)
        val raw = runCatching { sm.activeSubtype.primaryLocale.languageTag() }.getOrDefault("ru-RU")
        val lang = when (raw.substringBefore('-').lowercase()) { "ru" -> "ru-RU"; "uk" -> "uk-UA"; "en" -> if (raw.contains('-')) raw else "en-US"; else -> raw }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500)
        if (android.os.Build.VERSION.SDK_INT >= 33) i.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { VseIme.status.value = "🎙 Говори… (микрофон — стоп)" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {
                val p = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (p.isBlank()) return
                // после паузы распознаватель начинает новую фразу «с нуля», а в итоге отдаёт только её —
                // поэтому законченную часть вставляем сразу (так терялось «Он не заливает…» в примере владельца)
                val head = lastPartial.take(8)
                if (lastPartial.length > p.length && head.isNotEmpty() && !p.startsWith(head)) {
                    put(ctx, lastPartial.removePrefix(sessionCommitted)); sessionCommitted = ""
                }
                lastPartial = p
                VseIme.status.value = "🎙 $p"
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().ifBlank { lastPartial }
                put(ctx, t)
                lastPartial = ""; sessionCommitted = ""
                if (listening) listen(ctx)   // слушаем дальше, пока не нажали стоп
            }
            override fun onError(e: Int) {
                if (listening && (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) { listen(ctx); return }
                // 12/13 — язык не поддерживается/не скачан на телефоне: просим скачать и сразу слушаем онлайн
                if (onDevice && (e == 12 || e == 13)) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { r.triggerModelDownload(i) }
                    onlineOnly = true
                    if (listening) { listen(ctx); return }
                }
                stop()
                val why = when (e) {
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "нет сети"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "нет доступа к микрофону"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "распознавание занято, попробуй ещё раз"
                    12, 13 -> "язык не поддерживается (нужны «Распознавание речи Google» и язык в его настройках)"
                    else -> "ошибка $e"
                }
                if (e != SpeechRecognizer.ERROR_CLIENT) { VseIme.status.value = "Голос: $why"; VseIme.scope.launch { kotlinx.coroutines.delay(5000); if (!listening) VseIme.status.value = null } }
            }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(i)
    }
}
