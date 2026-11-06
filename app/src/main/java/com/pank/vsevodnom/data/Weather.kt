package com.pank.vsevodnom.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.coroutines.resume

enum class Scene { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER }

/** Коды погоды WMO → описание и сцена анимации. */
fun wmo(code: Int): Pair<String, Scene> = when (code) {
    0 -> "Ясно" to Scene.CLEAR
    1 -> "Преимущественно ясно" to Scene.PARTLY
    2 -> "Переменная облачность" to Scene.PARTLY
    3 -> "Пасмурно" to Scene.CLOUDY
    45, 48 -> "Туман" to Scene.FOG
    51, 53, 55 -> "Морось" to Scene.DRIZZLE
    56, 57 -> "Ледяная морось" to Scene.DRIZZLE
    61 -> "Небольшой дождь" to Scene.RAIN
    63 -> "Дождь" to Scene.RAIN
    65 -> "Сильный дождь" to Scene.RAIN
    66, 67 -> "Ледяной дождь" to Scene.RAIN
    71 -> "Небольшой снег" to Scene.SNOW
    73 -> "Снег" to Scene.SNOW
    75 -> "Сильный снег" to Scene.SNOW
    77 -> "Снежная крупа" to Scene.SNOW
    80 -> "Ливень" to Scene.RAIN
    81 -> "Сильный ливень" to Scene.RAIN
    82 -> "Очень сильный ливень" to Scene.RAIN
    85, 86 -> "Снегопад" to Scene.SNOW
    95 -> "Гроза" to Scene.THUNDER
    96, 99 -> "Гроза с градом" to Scene.THUNDER
    else -> "—" to Scene.CLOUDY
}

data class HourW(val time: LocalDateTime, val temp: Double, val rainProb: Int, val code: Int, val isDay: Boolean)
data class DayW(
    val date: LocalDate, val code: Int, val tMax: Double, val tMin: Double, val rainProb: Int,
    val sunrise: String, val sunset: String, val uv: Double,
)

data class WeatherData(
    val temp: Double, val feels: Double, val humidity: Int, val code: Int, val isDay: Boolean,
    val pressureHpa: Double, val windMs: Double, val windDir: Int, val gustsMs: Double,
    val cloud: Int, val precip: Double, val visibilityM: Double?,
    val hours: List<HourW>, val days: List<DayW>,
    val aqi: Int?, val pm25: Double?,
    val fetchedAt: Long,
) {
    val pressureMm get() = pressureHpa * 0.750062
}

object WeatherApi {
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "vse-v-odnom-android")
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Возвращает сырой JSON {"f":…, "a":…} — его же кладём в кэш. */
    suspend fun fetchRaw(lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
        val f = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
                "&hourly=temperature_2m,precipitation_probability,weather_code,is_day,visibility" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset,uv_index_max" +
                "&timezone=auto&forecast_days=10&wind_speed_unit=ms"
        )
        val a = runCatching {
            get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=european_aqi,pm2_5&timezone=auto")
        }.getOrNull()
        JSONObject().put("f", JSONObject(f)).put("a", a?.let { JSONObject(it) } ?: JSONObject.NULL).put("t", System.currentTimeMillis()).toString()
    }

    fun parse(raw: String): WeatherData {
        val root = JSONObject(raw)
        val f = root.getJSONObject("f")
        val cur = f.getJSONObject("current")
        val h = f.getJSONObject("hourly")
        val d = f.getJSONObject("daily")
        val nowStr = cur.getString("time") // локальное время места
        val now = LocalDateTime.parse(nowStr)

        val hTimes = h.getJSONArray("time")
        val hours = ArrayList<HourW>()
        var visibility: Double? = null
        for (i in 0 until hTimes.length()) {
            val t = LocalDateTime.parse(hTimes.getString(i))
            if (t.isBefore(now.withMinute(0))) continue
            if (visibility == null) visibility = h.getJSONArray("visibility").optDoubleOrNull(i)
            if (hours.size >= 24) break
            hours += HourW(
                t, h.getJSONArray("temperature_2m").optDouble(i),
                h.getJSONArray("precipitation_probability").optInt(i, 0),
                h.getJSONArray("weather_code").optInt(i, 3),
                h.getJSONArray("is_day").optInt(i, 1) == 1
            )
        }
        val dT = d.getJSONArray("time")
        val days = (0 until dT.length()).map { i ->
            DayW(
                LocalDate.parse(dT.getString(i)), d.getJSONArray("weather_code").optInt(i, 3),
                d.getJSONArray("temperature_2m_max").optDouble(i), d.getJSONArray("temperature_2m_min").optDouble(i),
                d.getJSONArray("precipitation_probability_max").optInt(i, 0),
                d.getJSONArray("sunrise").optString(i).substringAfter('T'),
                d.getJSONArray("sunset").optString(i).substringAfter('T'),
                d.getJSONArray("uv_index_max").optDouble(i, 0.0),
            )
        }
        val a = root.optJSONObject("a")?.optJSONObject("current")
        return WeatherData(
            temp = cur.optDouble("temperature_2m"), feels = cur.optDouble("apparent_temperature"),
            humidity = cur.optInt("relative_humidity_2m"), code = cur.optInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1, pressureHpa = cur.optDouble("pressure_msl"),
            windMs = cur.optDouble("wind_speed_10m"), windDir = cur.optInt("wind_direction_10m"),
            gustsMs = cur.optDouble("wind_gusts_10m"), cloud = cur.optInt("cloud_cover"),
            precip = cur.optDouble("precipitation", 0.0), visibilityM = visibility,
            hours = hours, days = days,
            aqi = a?.let { if (it.isNull("european_aqi")) null else it.optInt("european_aqi") },
            pm25 = a?.optDoubleOrNull("pm2_5"),
            fetchedAt = root.optLong("t", System.currentTimeMillis()),
        )
    }

    private fun JSONArray.optDoubleOrNull(i: Int): Double? = if (isNull(i)) null else optDouble(i)
    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (isNull(k)) null else optDouble(k)

    data class Place(val name: String, val region: String, val lat: Double, val lon: Double, val timezone: String = "")

    suspend fun search(q: String): List<Place> = withContext(Dispatchers.IO) {
        val js = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(q, "UTF-8")}&count=15&language=ru&format=json"))
        val arr = js.optJSONArray("results") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Place(
                o.getString("name"),
                listOf(o.optString("admin1"), o.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                o.getDouble("latitude"), o.getDouble("longitude"), o.optString("timezone")
            )
        }
    }

    /** Текущая точка + название города и района через системный геокодер. */
    @SuppressLint("MissingPermission")
    suspend fun locate(ctx: Context): Place? = withContext(Dispatchers.IO) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val loc: Location? = withTimeoutOrNull(15_000) {
            if (Build.VERSION.SDK_INT >= 30) {
                val provider = when {
                    Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> LocationManager.GPS_PROVIDER
                }
                suspendCancellableCoroutine { cont ->
                    lm.getCurrentLocation(provider, null, ctx.mainExecutor) { cont.resume(it) }
                }
            } else null
        } ?: listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .firstNotNullOfOrNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        loc ?: return@withContext null
        var name = "Моё место"
        var region = ""
        runCatching {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Locale("ru")).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
            if (a != null) {
                name = a.locality ?: a.subAdminArea ?: a.adminArea ?: name
                region = listOfNotNull(a.subLocality, a.adminArea?.takeIf { it != name }).joinToString(", ")
            }
        }
        Place(name, region, loc.latitude, loc.longitude)
    }
}
