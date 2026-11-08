package com.pank.vsevodnom.ui.map

import android.content.Context
import com.pank.vsevodnom.data.AlertGeo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/** Настройки карты (prefs «map»): единицы, координаты, навигация, дом/работа, недавние. */
object MapPrefs {
    fun p(ctx: Context) = ctx.getSharedPreferences("map", Context.MODE_PRIVATE)

    data class Spot(val name: String, val lat: Double, val lon: Double, val sub: String = "")

    private fun spot(o: JSONObject) = Spot(o.optString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optString("sub"))
    private fun json(s: Spot) = JSONObject().put("name", s.name).put("lat", s.lat).put("lon", s.lon).put("sub", s.sub)

    fun home(ctx: Context) = p(ctx).getString("home", null)?.let { runCatching { spot(JSONObject(it)) }.getOrNull() }
    fun work(ctx: Context) = p(ctx).getString("work", null)?.let { runCatching { spot(JSONObject(it)) }.getOrNull() }
    fun setHome(ctx: Context, s: Spot?) = p(ctx).edit().putString("home", s?.let { json(it).toString() }).apply()
    fun setWork(ctx: Context, s: Spot?) = p(ctx).edit().putString("work", s?.let { json(it).toString() }).apply()

    /** Свои избранные места для маршрута (учёба, вторая работа…). */
    fun favs(ctx: Context): List<Spot> = runCatching { JSONArray(p(ctx).getString("favs", "[]")).let { a -> (0 until a.length()).map { spot(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
    fun setFavs(ctx: Context, l: List<Spot>) = p(ctx).edit().putString("favs", JSONArray(l.map { json(it) }).toString()).apply()

    fun recents(ctx: Context): List<Spot> = runCatching { JSONArray(p(ctx).getString("recents", "[]")).let { a -> (0 until a.length()).map { spot(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
    fun addRecent(ctx: Context, s: Spot) {
        if (p(ctx).getBoolean("no_history", false)) return
        val l = (listOf(s) + recents(ctx).filterNot { abs(it.lat - s.lat) < 1e-5 && abs(it.lon - s.lon) < 1e-5 }).take(15)
        p(ctx).edit().putString("recents", JSONArray(l.map { json(it) }).toString()).apply()
    }
    fun clearRecents(ctx: Context) = p(ctx).edit().remove("recents").remove("search_history").apply()

    fun history(ctx: Context): List<String> = runCatching { JSONArray(p(ctx).getString("search_history", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    fun addHistory(ctx: Context, q: String) {
        if (p(ctx).getBoolean("no_history", false) || q.isBlank()) return
        p(ctx).edit().putString("search_history", JSONArray((listOf(q) + history(ctx).filter { it != q }).take(12)).toString()).apply()
    }

    // единицы
    fun imperial(ctx: Context) = p(ctx).getString("units", "metric") == "imperial"
    fun dist(ctx: Context, m: Double): String = if (imperial(ctx)) {
        val mi = m / 1609.344; if (mi < 0.2) "%.0f фт".format(m * 3.28084) else "%.2f ми".format(mi).replace('.', ',')
    } else if (m < 1000) "${m.toInt()} м" else if (m < 10_000) "%.2f км".format(m / 1000).replace('.', ',') else "%.1f км".format(m / 1000).replace('.', ',')
    fun speed(ctx: Context, kmh: Float) = if (imperial(ctx)) "%.0f".format(kmh / 1.609344f) to "ми/ч" else "%.0f".format(kmh) to "км/ч"
    fun alt(ctx: Context, m: Double) = if (imperial(ctx)) "%.0f".format(m * 3.28084) to "фт" else "%.0f".format(m) to "м"

    /** Координаты в выбранном виде: градусы, градусы-минуты, градусы-минуты-секунды. */
    fun coords(ctx: Context, lat: Double, lon: Double): String {
        fun dm(v: Double, pos: Char, neg: Char, sec: Boolean): String {
            val a = abs(v); val d = a.toInt(); val mFull = (a - d) * 60
            val h = if (v >= 0) pos else neg
            return if (sec) { val m = mFull.toInt(); val s = (mFull - m) * 60; String.format(Locale.US, "%d°%02d'%04.1f\"%c", d, m, s, h) }
            else String.format(Locale.US, "%d°%06.3f'%c", d, mFull, h)
        }
        return when (p(ctx).getString("coord_fmt", "dd")) {
            "dms" -> dm(lat, 'N', 'S', true) + " " + dm(lon, 'E', 'W', true)
            "ddm" -> dm(lat, 'N', 'S', false) + " " + dm(lon, 'E', 'W', false)
            else -> String.format(Locale.US, "%.6f, %.6f", lat, lon)
        }
    }

    /** «50.45, 30.52» → точка (для поиска по координатам). */
    fun parseCoords(q: String): Pair<Double, Double>? {
        val m = Regex("^\\s*(-?\\d{1,2}(?:[.,]\\d+)?)\\s*[,; ]\\s*(-?\\d{1,3}(?:[.,]\\d+)?)\\s*$").find(q) ?: return null
        val lat = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        return if (abs(lat) <= 90 && abs(lon) <= 180) lat to lon else null
    }

    // навигация
    fun navMode(ctx: Context) = p(ctx).getString("nav_mode", "car") ?: "car"
    fun bool(ctx: Context, k: String, def: Boolean) = p(ctx).getBoolean(k, def)
    fun setBool(ctx: Context, k: String, v: Boolean) = p(ctx).edit().putBoolean(k, v).apply()
    fun str(ctx: Context, k: String, def: String) = p(ctx).getString(k, def) ?: def
    fun setStr(ctx: Context, k: String, v: String) = p(ctx).edit().putString(k, v).apply()
    fun trackFolder(ctx: Context): Long? = p(ctx).getLong("track_folder", 0).takeIf { it > 0 }
    fun setTrackFolder(ctx: Context, id: Long?) = p(ctx).edit().putLong("track_folder", id ?: 0).apply()
    /** «Только без интернета»: не обращаться к Nominatim и Википедии. */
    fun offlineOnly(ctx: Context) = p(ctx).getBoolean("offline_only", false)
}

/** Административное деление Украины без интернета (область → район → громада) по границам из assets/geo. */
object UaAdmin {
    @Volatile private var map: AlertGeo.GeoMap? = null
    @Volatile private var hromady: List<AlertGeo.Area>? = null
    // проекция файлов assets/geo: x = (lon − 22.137)·109.43 + 15, y = (52.379 − lat)·165.14 + 13
    private fun x(lon: Double) = ((lon - 22.137) * 109.43 + 15).toFloat()
    private fun y(lat: Double) = ((52.379 - lat) * 165.14 + 13).toFloat()

    fun lookup(ctx: Context, lat: Double, lon: Double): List<String> {
        if (lat !in 44.0..52.6 || lon !in 22.0..40.5) return emptyList()
        val m = map ?: AlertGeo.parseMap(ctx.assets.open("geo/raions.txt").bufferedReader().readText()).also { map = it }
        val px = x(lon); val py = y(lat)
        val city = m.oblasts.firstOrNull { it.name.startsWith("м. ") && it.contains(px, py) }
        if (city != null) return listOf("Україна", city.name.removePrefix("м. "))
        val o = m.oblasts.firstOrNull { !it.name.startsWith("м. ") && it.contains(px, py) } ?: return emptyList()
        val r = m.raions.firstOrNull { it.oblast == o.oblast && it.contains(px, py) }
        val h = r?.let { rr ->
            (hromady ?: AlertGeo.parseHromady(ctx.assets.open("geo/hromady.txt").bufferedReader().readText()).also { hromady = it })
                .firstOrNull { it.oblast == o.oblast && it.raion == rr.raion && it.contains(px, py) }
        }
        return listOfNotNull("Україна", o.name, r?.name, h?.name)
    }
}
