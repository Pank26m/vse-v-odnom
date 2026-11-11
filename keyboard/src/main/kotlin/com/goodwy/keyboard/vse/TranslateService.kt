package com.goodwy.keyboard.vse

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Перевод для клавиатуры — в ОСНОВНОМ процессе приложения (там Google ML Kit запускается сам).
 * Клавиатура (процесс :ime) присылает текст через Messenger, ответ — переведённый текст или ошибка.
 */
class TranslateService : Service() {
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val reply = msg.replyTo ?: return
            val d = msg.data
            val text = d.getString("text").orEmpty(); val from = d.getString("from").orEmpty(); val to = d.getString("to").orEmpty()
            fun answer(ok: Boolean, s: String) = runCatching {
                reply.send(Message.obtain(null, 2).apply { data = Bundle().apply { putBoolean("ok", ok); putString("text", s) } })
            }
            fun code(l: String) = when (l) { "uk" -> TranslateLanguage.UKRAINIAN; "ru" -> TranslateLanguage.RUSSIAN; else -> TranslateLanguage.ENGLISH }
            val tr = runCatching { Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(code(from)).setTargetLanguage(code(to)).build()) }
                .getOrElse { answer(false, it.message ?: "ML Kit не запустился"); return }
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    tr.translate(text).addOnSuccessListener { answer(true, it); tr.close() }
                        .addOnFailureListener { answer(false, it.message ?: "ошибка перевода"); tr.close() }
                }
                .addOnFailureListener { answer(false, "словарь не скачался: " + (it.message ?: "нет сети")); tr.close() }
        }
    }
    private val messenger = Messenger(handler)
    override fun onCreate() {
        super.onCreate()
        // на всякий случай: в 0.38 ML Kit падал внутри (NPE) — инициализируем явно
        runCatching { com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(applicationContext) }
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
}
