package com.pank.vsevodnom.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Состояние тревоги по областям. Ключ — название как в карте (украинское, «м. Київ» отдельно). */
data class AlertState(
    val active: Map<String, Boolean>,
    val since: Map<String, LocalDateTime?>,
    val source: String,
    val fetchedAt: Long,
)

object AlertsApi {
    const val KYIV_CITY = "м. Київ"
    const val KYIV_OBL = "Київська область"

    /** Порядок областей в ответе alerts.in.ua (IoT-эндпоинт) — из их документации. */
    private val IOT_ORDER = listOf(
        "Автономна Республіка Крим", "Волинська область", "Вінницька область", "Дніпропетровська область",
        "Донецька область", "Житомирська область", "Закарпатська область", "Запорізька область",
        "Івано-Франківська область", "м. Київ", "Київська область", "Кіровоградська область",
        "Луганська область", "Львівська область", "Миколаївська область", "Одеська область",
        "Полтавська область", "Рівненська область", "м. Севастополь", "Сумська область",
        "Тернопільська область", "Харківська область", "Херсонська область", "Хмельницька область",
        "Черкаська область", "Чернівецька область", "Чернігівська область",
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE)
    fun token(ctx: Context): String = prefs(ctx).getString("token", "") ?: ""
    fun setToken(ctx: Context, t: String) = prefs(ctx).edit().putString("token", t.trim()).apply()
    fun notifyEnabled(ctx: Context) = prefs(ctx).getBoolean("notify", true)
    fun setNotify(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("notify", on).apply()

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000; c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", "vse-v-odnom-android")
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Приводит разные написания к названиям из карты. */
    fun canonical(name: String): String {
        val n = name.trim().replace("’", "'").replace("ʼ", "'")
        if (n.contains("Київ") && !n.contains("обл")) return KYIV_CITY
        if (n.contains("Севастоп")) return "м. Севастополь"
        if (n.contains("Крим")) return "Автономна Республіка Крим"
        val stem = n.substringBefore(" ").substringBefore("ськ").take(5)
        return IOT_ORDER.firstOrNull { it.startsWith(stem) && it.contains("область") } ?: n
    }

    suspend fun fetch(ctx: Context): AlertState = withContext(Dispatchers.IO) {
        val tok = token(ctx)
        if (tok.isNotBlank()) runCatching { return@withContext fetchOfficial(tok) }
        fetchUbilling()
    }

    /** alerts.in.ua: строка из 27 символов A/P/N в фиксированном порядке. Нужен личный токен. */
    private fun fetchOfficial(token: String): AlertState {
        val raw = get("https://api.alerts.in.ua/v1/iot/active_air_raid_alerts_by_oblast.json?token=$token")
            .trim().trim('"')
        if (raw.length < IOT_ORDER.size) throw RuntimeException("Неожиданный ответ")
        val act = IOT_ORDER.mapIndexed { i, n -> n to (raw[i] == 'A' || raw[i] == 'P') }.toMap()
        return AlertState(act, emptyMap(), "alerts.in.ua", System.currentTimeMillis())
    }

    /** Публичный JSON без ключа (неофициальный агрегатор). Разбираем осторожно. */
    private fun fetchUbilling(): AlertState {
        val js = JSONObject(get("https://ubilling.net.ua/aerialalerts/"))
        val states = js.optJSONObject("states") ?: throw RuntimeException("Источник изменил формат")
        val act = HashMap<String, Boolean>()
        val since = HashMap<String, LocalDateTime?>()
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        for (k in states.keys()) {
            val o = states.optJSONObject(k) ?: continue
            val on = when {
                o.has("alertnow") -> o.optBoolean("alertnow")
                o.has("alert") -> o.optBoolean("alert")
                o.has("enabled") -> o.optBoolean("enabled")
                else -> false
            }
            val name = canonical(k)
            act[name] = on
            since[name] = (o.optString("changed").ifBlank { o.optString("updated") }).let { s ->
                runCatching { LocalDateTime.parse(s.take(19), fmt) }.getOrNull()
                    ?: runCatching { LocalDateTime.parse(s.take(19)) }.getOrNull()
            }
        }
        if (act.isEmpty()) throw RuntimeException("Пустой ответ источника")
        return AlertState(act, since, "ubilling.net.ua", System.currentTimeMillis())
    }
}
