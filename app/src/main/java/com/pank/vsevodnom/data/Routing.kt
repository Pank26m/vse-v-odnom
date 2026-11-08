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
    fun nearestIndex(pts: List<GeoPt>, lat: Double, lon: Double): Int {
        var best = 0; var bd = Double.MAX_VALUE; val k = Math.cos(Math.toRadians(lat))
        pts.forEachIndexed { i, p -> val d = (p.lat - lat) * (p.lat - lat) + (p.lon - lon) * (p.lon - lon) * k * k; if (d < bd) { bd = d; best = i } }
        return best
    }

    /** Шаг навигации: индекс точки маршрута, команда (коды BRouter: Step.*), номер съезда с кольца. */
    data class Step(val index: Int, val cmd: Int, val exit: Int = 0)
    data class Route(val points: List<GeoPt>, val meters: Double, val seconds: Double, val delay: Double = 0.0, val steps: List<Step> = emptyList(), val source: String = "")

    suspend fun route(mode: String, way: List<GeoPt>, avoidToll: Boolean = false, avoidFerry: Boolean = false): Route = withContext(Dispatchers.IO) {
        val profile = when (mode) { "bike" -> "routed-bike"; "foot" -> "routed-foot"; else -> "routed-car" }
        val ex = listOfNotNull("toll".takeIf { avoidToll && mode == "car" }, "ferry".takeIf { avoidFerry && mode == "car" }).joinToString(",")
        val url = String.format(Locale.US, "https://routing.openstreetmap.de/%s/route/v1/driving/%s?overview=full&geometries=geojson&steps=true", profile,
            way.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }) + if (ex.isNotEmpty()) "&exclude=$ex" else ""
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "VseVOdnom/0.16 (personal Android app)")
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val r = o.getJSONArray("routes").getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until coords.length()).map { val p = coords.getJSONArray(it); GeoPt(p.getDouble(1), p.getDouble(0), 0) }
        // шаги OSRM → ближайшая точка геометрии
        val steps = ArrayList<Step>()
        val legs = r.optJSONArray("legs")
        for (li in 0 until (legs?.length() ?: 0)) {
            val ss = legs!!.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 1 until ss.length()) {
                val m = ss.getJSONObject(si).getJSONObject("maneuver"); val loc = m.getJSONArray("location")
                val type = m.optString("type"); val mod = m.optString("modifier")
                val cmd = when {
                    type == "arrive" -> Nav.END
                    type == "roundabout" || type == "rotary" -> Nav.RNDB
                    mod == "uturn" -> Nav.TU
                    mod == "sharp left" -> Nav.TSHL; mod == "left" -> Nav.TL; mod == "slight left" -> if (type == "fork" || type == "off ramp") Nav.KL else Nav.TSLL
                    mod == "sharp right" -> Nav.TSHR; mod == "right" -> Nav.TR; mod == "slight right" -> if (type == "fork" || type == "off ramp") Nav.KR else Nav.TSLR
                    else -> Nav.C
                }
                if (cmd == Nav.C && type != "new name") continue
                if (cmd == Nav.C) continue
                val idx = nearestIndex(pts, loc.getDouble(1), loc.getDouble(0))
                steps += Step(idx, cmd, m.optInt("exit", 0))
            }
        }
        Route(pts, r.getDouble("distance"), r.getDouble("duration"), steps = steps, source = "OSRM")
    }
}
