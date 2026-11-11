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

    fun stop() {
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
        listen(ctx)
    }

    private fun listen(ctx: Context) {
        runCatching { rec?.destroy() }
        val r = if (android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        val sm by ctx.subtypeManager()
        val lang = runCatching { sm.activeSubtype.primaryLocale.languageTag() }.getOrDefault("ru-RU")
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
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { VseIme.status.value = "🎙 $it" }
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotBlank()) {
                    val ic = FlorisImeService.currentInputConnection()
                    val prev = ic?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
                    val sep = if (prev.isEmpty() || prev.endsWith(" ") || prev.endsWith("\n")) "" else " "
                    VseIme.commit(ctx, sep + t)
                }
                if (listening) listen(ctx)   // слушаем дальше, пока не нажали стоп
            }
            override fun onError(e: Int) {
                if (listening && (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) { listen(ctx); return }
                stop()
                if (e != SpeechRecognizer.ERROR_CLIENT) VseIme.status.value = "Голос: ошибка $e"
            }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(i)
    }
}
