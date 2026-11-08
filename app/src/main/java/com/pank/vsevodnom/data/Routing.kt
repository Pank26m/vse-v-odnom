package com.pank.vsevodnom.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Маршрут по дорогам онлайн: OSRM на серверах FOSSGIS (routing.openstreetmap.de — авто, велосипед, пешком; без ключа,
 * для личного пользования). Офлайн-маршруты по дорогам — следующий этап.
 */
object Routing {
    data class Route(val points: List<GeoPt>, val meters: Double, val seconds: Double, val delay: Double = 0.0)

    suspend fun route(mode: String, from: GeoPt, to: GeoPt): Route = withContext(Dispatchers.IO) {
        val profile = when (mode) { "bike" -> "routed-bike"; "foot" -> "routed-foot"; else -> "routed-car" }
        val url = String.format(Locale.US, "https://routing.openstreetmap.de/%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson", profile, from.lon, from.lat, to.lon, to.lat)
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "VseVOdnom/0.16 (personal Android app)")
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val r = o.getJSONArray("routes").getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        Route((0 until coords.length()).map { val p = coords.getJSONArray(it); GeoPt(p.getDouble(1), p.getDouble(0), 0) }, r.getDouble("distance"), r.getDouble("duration"))
    }
}
