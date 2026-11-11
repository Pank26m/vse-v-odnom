package com.goodwy.keyboard.vse

import android.content.Context
import org.json.JSONArray
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Пароли в клавиатуре. Окно приложения только проверяет отпечаток и присылает записи сюда (внутри приложения),
 * список открывается прямо в клавиатуре — вставка идёт в поле, которое уже в фокусе (раньше текст терялся при
 * возврате из окна паролей в Telegram/Instagram). Записи живут в памяти 3 минуты, на диск не пишутся.
 */
object VseVault {
    data class Entry(val title: String, val login: String, val password: String, val totp: String, val url: String, val extra: List<Pair<String, String>>)

    @Volatile var entries: List<Entry> = emptyList()
        private set
    private var at = 0L
    private const val TTL = 3 * 60_000L

    fun put(json: String) {
        entries = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val ex = o.optJSONArray("extra")?.let { x -> (0 until x.length()).map { x.getJSONObject(it).let { e -> e.optString("label") to e.optString("value") } } } ?: emptyList()
                Entry(o.optString("title"), o.optString("login"), o.optString("password"), o.optString("totp"), o.optString("url"), ex)
            }
        }.getOrDefault(emptyList())
        at = System.currentTimeMillis()
    }

    fun alive(): Boolean { if (entries.isNotEmpty() && System.currentTimeMillis() - at > TTL) wipe(); return entries.isNotEmpty() }
    fun wipe() { entries = emptyList() }

    // ---------- что подходит приложению/сайту ----------
    private val TR = mapOf('а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'ґ' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'є' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'і' to "i", 'ї' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya")
    /** Латиница для сравнения: «Инстаграм» → «instagram», «Телеграм» → «telegram». */
    fun translit(s: String): String = s.lowercase(Locale.ROOT).map { TR[it] ?: it.toString() }.joinToString("")

    /** Ключ приложения: com.instagram.android → «instagram», org.telegram.messenger → «telegram». */
    fun keyOf(pkg: String): String = pkg.split('.').filter { it !in setOf("com", "org", "android", "app", "mobile", "ua", "ru", "net", "messenger", "lite", "katana") }
        .maxByOrNull { it.length }.orEmpty().let { if (pkg == "com.facebook.katana") "facebook" else it }

    fun matches(e: Entry, key: String): Boolean {
        if (key.length < 3) return false
        val k = key.take(6)
        return translit(e.title).contains(k) || e.url.lowercase().contains(k) || translit(e.title).replace("ks", "x").contains(k)
    }

    // ---------- коды 2FA прямо в клавиатуре ----------
    fun totp(secretOrUri: String, now: Long = System.currentTimeMillis()): String? = runCatching {
        var secret = secretOrUri.trim(); var digits = 6; var period = 30; var algo = "HmacSHA1"
        if (secret.startsWith("otpauth://", true)) {
            val q = secret.substringAfter('?').split('&').associate { it.substringBefore('=').lowercase() to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            secret = q["secret"] ?: return null
            digits = q["digits"]?.toIntOrNull() ?: 6; period = q["period"]?.toIntOrNull() ?: 30
            algo = when (q["algorithm"]?.uppercase()) { "SHA256" -> "HmacSHA256"; "SHA512" -> "HmacSHA512"; else -> "HmacSHA1" }
        }
        val key = base32(secret.replace(" ", "").uppercase()) ?: return null
        val counter = now / 1000 / period
        val msg = ByteArray(8) { i -> (counter shr (56 - 8 * i) and 0xFF).toByte() }
        val h = Mac.getInstance(algo).apply { init(SecretKeySpec(key, algo)) }.doFinal(msg)
        val o = h.last().toInt() and 0x0F
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        var mod = 1; repeat(digits) { mod *= 10 }
        (bin % mod).toString().padStart(digits, '0')
    }.getOrNull()

    private fun base32(s: String): ByteArray? {
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = java.io.ByteArrayOutputStream(); var buf = 0; var bits = 0
        for (c in s.trimEnd('=')) { val v = abc.indexOf(c); if (v < 0) return null; buf = (buf shl 5) or v; bits += 5; if (bits >= 8) { out.write(buf shr (bits - 8) and 0xFF); bits -= 8 } }
        return out.toByteArray()
    }
}
