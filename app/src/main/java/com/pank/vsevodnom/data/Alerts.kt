package com.pank.vsevodnom.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Уровень тревоги: нет, частично (отдельные районы/громады), вся область. */
enum class AlertLevel { NONE, PARTIAL, FULL }

data class AlertState(
    val level: Map<String, AlertLevel>,
    val since: Map<String, LocalDateTime?>,
    /** Для каждой области — какие районы/громады сейчас под тревогой. */
    val districts: Map<String, List<String>>,
    val source: String,
    val fetchedAt: Long,
    val districtLevel: Boolean,   // true — данные по районам (официальный API), false — только области
) {
    fun of(name: String) = level[name] ?: AlertLevel.NONE
    fun known(name: String) = level.containsKey(name)
}

class RateLimited(val waitSec: Long) : Exception("Слишком частые запросы")

object AlertsApi {
    const val KYIV_CITY = "м. Київ"
    const val KYIV_OBL = "Київська область"
    const val SEVASTOPOL = "м. Севастополь"

    val OBLASTS = listOf(
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

    private class Http(val code: Int, val body: String, val retryAfter: Long?)
    private fun get(url: String): Http {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000; c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", "vse-v-odnom-android")
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return Http(code, body, c.getHeaderField("Retry-After")?.toLongOrNull())
        } finally { c.disconnect() }
    }

    fun canonical(name: String): String {
        val n = name.trim().replace("’", "'").replace("ʼ", "'")
        if (n.contains("Київ") && !n.contains("обл")) return KYIV_CITY
        if (n.contains("Севастоп")) return SEVASTOPOL
        if (n.contains("Крим")) return "Автономна Республіка Крим"
        val stem = n.substringBefore(" ").substringBefore("ськ").take(5)
        return OBLASTS.firstOrNull { it.startsWith(stem) && it.contains("область") } ?: n
    }

    // ---- кэш и ограничение частоты: сервера отвечают 429, если дёргать чаще ----
    private val lock = Mutex()
    @Volatile private var cache: AlertState? = null
    @Volatile private var blockedUntil = 0L
    private const val MIN_INTERVAL = 25_000L

    /** Возвращает свежие данные не чаще раза в 25 с; иначе — кэш. */
    suspend fun fetch(ctx: Context, force: Boolean = false): AlertState = lock.withLock {
        val now = System.currentTimeMillis()
        cache?.let { if (now - it.fetchedAt < MIN_INTERVAL && !force) return it }
        if (now < blockedUntil) cache?.let { return it } ?: throw RateLimited((blockedUntil - now) / 1000)
        val st = withContext(Dispatchers.IO) {
            val tok = token(ctx)
            if (tok.isNotBlank()) runCatching { fetchOfficial(tok) }.getOrElse { if (it is RateLimited) throw it; fetchUbilling() }
            else fetchUbilling()
        }
        cache = st
        st
    }

    fun cached() = cache

    private fun checkRate(h: Http) {
        if (h.code == 429) {
            val wait = (h.retryAfter ?: 60).coerceIn(10, 600)
            blockedUntil = System.currentTimeMillis() + wait * 1000
            throw RateLimited(wait)
        }
        if (h.code == 401 || h.code == 403) throw IllegalStateException("Токен alerts.in.ua не подошёл")
        if (h.code !in 200..299) throw RuntimeException("HTTP ${h.code}")
    }

    /** alerts.in.ua: список активных тревог с уровнем «область / район / громада / город». */
    private fun fetchOfficial(token: String): AlertState {
        val h = get("https://api.alerts.in.ua/v1/alerts/active.json?token=$token")
        checkRate(h)
        val arr = JSONObject(h.body).optJSONArray("alerts") ?: throw RuntimeException("Неожиданный ответ")
        val level = OBLASTS.associateWith { AlertLevel.NONE }.toMutableMap()
        val since = HashMap<String, LocalDateTime?>()
        val districts = HashMap<String, MutableList<String>>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val type = a.optString("location_type")
            val title = a.optString("location_title")
            val obl = canonical(a.optString("location_oblast").ifBlank { title })
            val started = runCatching { Instant.parse(a.optString("started_at")).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
            val full = type == "oblast" || (type == "city" && (obl == KYIV_CITY || obl == SEVASTOPOL))
            val target = if (type == "city" && title.contains("Київ")) KYIV_CITY else obl
            if (full) level[target] = AlertLevel.FULL
            else {
                if (level[target] != AlertLevel.FULL) level[target] = AlertLevel.PARTIAL
                districts.getOrPut(target) { ArrayList() } += title
            }
            since[target] = listOfNotNull(since[target], started).minOrNull()
        }
        return AlertState(level, since, districts, "alerts.in.ua (по районам)", System.currentTimeMillis(), true)
    }

    /** Публичный агрегатор без ключа: только целые области. */
    private fun fetchUbilling(): AlertState {
        val h = get("https://ubilling.net.ua/aerialalerts/")
        checkRate(h)
        val states = JSONObject(h.body).optJSONObject("states") ?: throw RuntimeException("Источник изменил формат")
        val level = HashMap<String, AlertLevel>()
        val since = HashMap<String, LocalDateTime?>()
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        for (k in states.keys()) {
            val o = states.optJSONObject(k) ?: continue
            val on = when { o.has("alertnow") -> o.optBoolean("alertnow"); o.has("alert") -> o.optBoolean("alert"); else -> o.optBoolean("enabled") }
            val name = canonical(k)
            level[name] = if (on) AlertLevel.FULL else AlertLevel.NONE
            since[name] = (o.optString("changed").ifBlank { o.optString("updated") }).let { s ->
                runCatching { LocalDateTime.parse(s.take(19), fmt) }.getOrNull() ?: runCatching { LocalDateTime.parse(s.take(19)) }.getOrNull()
            }
        }
        if (level.isEmpty()) throw RuntimeException("Пустой ответ источника")
        return AlertState(level, since, emptyMap(), "ubilling.net.ua (только области)", System.currentTimeMillis(), false)
    }
}
