package com.goodwy.keyboard.vse

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Код из СМС: приложение («Всё в одном», основной процесс) при входящей СМС с кодом пишет его в files/kbd_code.json,
 * клавиатура показывает кнопку «Вставить код» 5 минут или до использования.
 */
object VseCodes {
    private const val TTL = 5 * 60_000L
    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "kbd_code.json")

    fun fresh(ctx: Context): String? = runCatching {
        val o = JSONObject(file(ctx).readText())
        if (o.optBoolean("used") || System.currentTimeMillis() - o.optLong("at") > TTL) null else o.optString("code").ifBlank { null }
    }.getOrNull()

    fun used(ctx: Context) { runCatching { val f = file(ctx); f.writeText(JSONObject(f.readText()).put("used", true).toString()) }; VseIme.rebuildChips(ctx) }

    /** Записать код (вызывается из приложения при входящей СМС). */
    fun put(ctx: Context, code: String) = runCatching {
        file(ctx).writeText(JSONObject().put("code", code).put("at", System.currentTimeMillis()).toString())
    }
}
