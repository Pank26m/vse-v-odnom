package com.goodwy.keyboard.vse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.text.InputType
import androidx.core.content.ContextCompat
import com.goodwy.keyboard.FlorisImeService
import com.goodwy.keyboard.editorInstance
import com.goodwy.keyboard.ime.editor.FlorisEditorInfo
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Следит за текстом перед курсором: номер телефона без контакта, пример для калькулятора («250*3» → «= 750»),
 * начало почты в поле почты (подсказывает твои почты — запоминает те, что ты уже вводил). Плюс «Где я».
 */
object VseWatch {
    /** Результат калькулятора и был ли в конце «=». */
    var calc: Pair<String, Boolean>? = null
    /** Подсказки почт: (что уже набрано, почта). */
    var emails: List<Pair<String, String>> = emptyList()
    private var emailField = false
    private var fieldText = ""

    fun watch(ctx: Context) {
        val ed by ctx.editorInstance()
        VseIme.scope.launch {
            ed.activeContentFlow.collectLatest { content ->
                val before = content.textBeforeSelection
                var changed = VsePhone.update(ctx, before)
                val c = calcOf(before)
                if (c != calc) { calc = c; changed = true }
                if (emailField) {
                    fieldText = before + content.textAfterSelection
                    val e = emailHints(ctx, before)
                    if (e != emails) { emails = e; changed = true }
                }
                if (changed) VseIme.rebuildChips(ctx)
            }
        }
    }

    fun enterField(info: FlorisEditorInfo) {
        val v = info.base.inputType and InputType.TYPE_MASK_VARIATION
        emailField = (info.base.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        fieldText = ""; emails = emptyList(); calc = null
    }

    /** Уходим из поля почты: если там была почта — запомнить (чем чаще, тем выше в подсказках). */
    fun leaveField(ctx: Context) {
        if (!emailField) return
        val e = fieldText.trim()
        if (EMAIL.matches(e)) runCatching {
            val m = loadEmails(ctx).toMutableMap(); m[e.lowercase(Locale.ROOT)] = (m[e.lowercase(Locale.ROOT)] ?: 0) + 1
            file(ctx).writeText(m.entries.joinToString("\n") { "${it.value}|${it.key}" })
        }
    }

    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "kbd_emails.txt")
    private fun loadEmails(ctx: Context): Map<String, Int> = runCatching {
        file(ctx).readLines().mapNotNull { l -> l.split('|', limit = 2).takeIf { it.size == 2 }?.let { it[1] to (it[0].toIntOrNull() ?: 1) } }.toMap()
    }.getOrDefault(emptyMap())

    private fun emailHints(ctx: Context, before: String): List<Pair<String, String>> {
        val typed = Regex("[A-Za-z0-9._%+\\-@]*$").find(before)?.value.orEmpty()
        return loadEmails(ctx).entries.sortedByDescending { it.value }.map { it.key }
            .filter { it.startsWith(typed.lowercase(Locale.ROOT)) && it != typed.lowercase(Locale.ROOT) }.take(3).map { typed to it }
    }

    // ---------- калькулятор ----------
    private val EXPR = Regex("([0-9(][0-9.,()+\\-*/×÷:^% ]*[0-9)%])\\s*(=?)\\s*$")
    fun calcOf(before: String): Pair<String, Boolean>? {
        val m = EXPR.find(before.takeLast(60)) ?: return null
        val e = m.groupValues[1]
        // нужен хотя бы один знак действия между числами; телефоны и даты не считаем
        if (!Regex("[0-9)]\\s*[+\\-*/×÷:^]\\s*[0-9(]").containsMatchIn(e)) return null
        if (Regex("^\\+?\\d[\\d\\- ]{8,}$").matches(e.trim()) || Regex("^\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}$").matches(e.trim())) return null
        val v = runCatching { Calc(e.replace('×', '*').replace('÷', '/').replace(':', '/').replace(',', '.').replace(" ", "")).parse() }.getOrNull() ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        val s = if (v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong().toString() else "%.6f".format(Locale.US, v).trimEnd('0').trimEnd('.')
        return s.replace('.', ',') to (m.groupValues[2] == "=")
    }

    /** Простой разбор: + − × ÷ ^ %, скобки. */
    private class Calc(val s: String) {
        var i = 0
        fun parse(): Double { val v = expr(); if (i != s.length) throw IllegalArgumentException(); return v }
        fun expr(): Double { var v = term(); while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val t = term(); v = if (op == '+') v + t else v - t }; return v }
        fun term(): Double { var v = pow(); while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val t = pow(); v = if (op == '*') v * t else v / t }; return v }
        fun pow(): Double { val b = unary(); return if (i < s.length && s[i] == '^') { i++; Math.pow(b, pow()) } else b }
        fun unary(): Double = if (i < s.length && s[i] == '-') { i++; -unary() } else atom()
        fun atom(): Double {
            if (i < s.length && s[i] == '(') { i++; val v = expr(); if (i >= s.length || s[i] != ')') throw IllegalArgumentException(); i++; return pct(v) }
            val st = i; while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalArgumentException()
            return pct(s.substring(st, i).toDouble())
        }
        fun pct(v: Double): Double = if (i < s.length && s[i] == '%') { i++; v / 100 } else v
    }

    // ---------- где я ----------
    fun location(ctx: Context) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            VseIme.status.value = "Нет доступа к геолокации: открой карту в «Всё в одном» и разреши"; clearLater(); return
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        fun put(l: Location) {
            VseIme.commit(ctx, "📍 https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, l.latitude, l.longitude))
            VseIme.status.value = null
        }
        @Suppress("MissingPermission")
        val last = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 5 * 60_000) { put(last); return }
        VseIme.status.value = "Определяю, где ты…"
        @Suppress("MissingPermission")
        runCatching {
            lm.getCurrentLocation(LocationManager.FUSED_PROVIDER.takeIf { lm.hasProvider(it) } ?: LocationManager.GPS_PROVIDER, null, ContextCompat.getMainExecutor(ctx)) { l ->
                if (l != null) put(l) else if (last != null) put(last) else { VseIme.status.value = "Не удалось определить место"; clearLater() }
            }
        }.onFailure { if (last != null) put(last) else { VseIme.status.value = "Не удалось определить место"; clearLater() } }
    }

    private fun clearLater() = VseIme.scope.launch { kotlinx.coroutines.delay(4000); VseIme.status.value = null }

    @Suppress("unused") private fun ic() = FlorisImeService.currentInputConnection()
}
