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

/** Угроза внутри тревоги (alerts.in.ua: threats[]). */
data class Threat(val type: String, val red: Boolean, val message: String)

/** Одна активная тревога: область, район, громада или город. */
data class AlertItem(
    val kind: String,          // oblast / raion / hromada / city
    val title: String,
    val oblast: String,        // каноническое имя области (из location_oblast)
    val raion: String?,        // для громад — их район
    val red: Boolean,          // alert_level: red — подтверждённая, yellow — «жёлтая» (угроза)
    val alertType: String,     // air_raid, artillery_shelling, urban_fights, chemical, nuclear
    val started: LocalDateTime?,
    val notes: String,
    val threats: List<Threat>,
)

data class AlertState(
    val level: Map<String, AlertLevel>,
    val since: Map<String, LocalDateTime?>,
    /** Для каждой области — какие районы/громады сейчас под тревогой. */
    val districts: Map<String, List<String>>,
    val source: String,
    val fetchedAt: Long,
    val districtLevel: Boolean,   // true — данные по районам (официальный API), false — только области
    val items: List<AlertItem> = emptyList(),
) {
    fun of(name: String) = level[name] ?: AlertLevel.NONE
    fun known(name: String) = level.containsKey(name)
    fun itemsOf(oblast: String) = items.filter { it.oblast == oblast }
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
    private fun get(url: String, token: String? = null): Http {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000; c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", "vse-v-odnom-android")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return Http(code, body, c.getHeaderField("Retry-After")?.toLongOrNull())
        } finally { c.disconnect() }
    }

    const val CRIMEA = "Автономна Республіка Крим"
    private fun norm(s: String) = s.trim().replace('’', '\'').replace('ʼ', '\'').replace('`', '\'').replace(Regex(" +"), " ")

    /**
     * Имя области → каноническое. Только точное совпадение (по полному слову),
     * никаких префиксов: раньше «Чернігівська» и «Чернівецька» сводились к «Черні» и путались.
     */
    fun canonical(name: String): String {
        val n = norm(name)
        OBLASTS.firstOrNull { norm(it).equals(n, true) }?.let { return it }
        val low = n.lowercase()
        if (low == "київ" || low == "м. київ" || low == "місто київ" || low == "kyiv") return KYIV_CITY
        if (low.contains("севастоп")) return SEVASTOPOL
        if (low.contains("крим")) return CRIMEA
        val first = low.substringBefore(' ').removeSuffix(".")
        return OBLASTS.firstOrNull { it.lowercase().substringBefore(' ') == first && it.contains("область") } ?: n
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
        val h = get("https://api.alerts.in.ua/v1/alerts/active.json", token)
        checkRate(h)
        return parseOfficial(h.body, System.currentTimeMillis())
    }

    private fun time(s: String) = runCatching { Instant.parse(s).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()

    fun parseOfficial(body: String, now: Long): AlertState {
        val arr = JSONObject(body).optJSONArray("alerts") ?: throw RuntimeException("Неожиданный ответ")
        val level = OBLASTS.associateWith { AlertLevel.NONE }.toMutableMap()
        val since = HashMap<String, LocalDateTime?>()
        val districts = HashMap<String, MutableList<String>>()
        val items = ArrayList<AlertItem>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (a.optString("finished_at").let { it.isNotBlank() && it != "null" }) continue
            val type = a.optString("location_type")
            val title = norm(a.optString("location_title"))
            // location_oblast — точное имя области; location_oblast_uid у районов врёт (равен uid района), его не берём
            var obl = canonical(a.optString("location_oblast").ifBlank { title })
            if (type == "city" && (title == KYIV_CITY || title.endsWith(" Київ"))) obl = KYIV_CITY
            if (type == "city" && title.contains("Севастоп")) obl = SEVASTOPOL
            val started = time(a.optString("started_at"))
            val th = a.optJSONArray("threats")
            val threats = (0 until (th?.length() ?: 0)).mapNotNull { j ->
                th!!.optJSONObject(j)?.let { t -> Threat(t.optString("threat_type", "unknown"), t.optString("level") == "red", t.optString("source_message").takeIf { m -> m != "null" }.orEmpty()) }
            }
            val red = a.optString("alert_level").let { it.isBlank() || it == "red" || it == "null" }
            items += AlertItem(type, title, obl, a.optString("location_raion").takeIf { it.isNotBlank() && it != "null" }?.let(::norm),
                red, a.optString("alert_type"), started, a.optString("notes").takeIf { it != "null" }.orEmpty(), threats)
            val full = type == "oblast" || obl == KYIV_CITY || obl == SEVASTOPOL
            if (full) level[obl] = AlertLevel.FULL
            else {
                if (level[obl] != AlertLevel.FULL) level[obl] = AlertLevel.PARTIAL
                districts.getOrPut(obl) { ArrayList() }.let { if (title !in it) it += title }
            }
            since[obl] = listOfNotNull(since[obl], started).minOrNull()
        }
        return AlertState(level, since, districts, "alerts.in.ua (по районам)", now, true, items)
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
