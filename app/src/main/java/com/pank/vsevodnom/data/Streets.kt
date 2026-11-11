package com.pank.vsevodnom.data

import globus.glmap.GLMapBBox
import globus.glmap.GLMapManager
import globus.glmap.GLMapVectorObject
import globus.glmap.MapGeoPoint
import globus.glmap.MapPoint
import globus.glsearch.GLSearch
import globus.glsearch.GLSearchFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Улицы и дороги: что за улица под пальцем (офлайн — поиск GLMap по дорогам рядом, онлайн — Nominatim), вся улица целиком
 * (все её отрезки рядом: офлайн GLMap, онлайн Overpass), русские названия видов дорог.
 */
object Streets {
    data class Hit(val name: String?, val highway: String, val tags: Map<String, String>, val lines: List<List<GeoPt>>, val osmUrl: String = "")

    private val ROADS = listOf("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service",
        "pedestrian", "road", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link", "track", "footway", "path", "cycleway", "steps", "bridleway")
    private val TAG_KEYS = listOf("name", "highway", "surface", "lanes", "maxspeed", "oneway", "lit", "width", "ref", "smoothness", "sidewalk", "bicycle", "foot", "wikipedia", "wikidata", "old_name", "alt_name")

    /** Вид дороги по-русски (легенда OSM). */
    fun kind(h: String?): String = when (h) {
        "motorway" -> "Автомагистраль"; "motorway_link" -> "Съезд с автомагистрали"
        "trunk" -> "Шоссе (магистраль)"; "trunk_link" -> "Съезд с шоссе"
        "primary" -> "Главная дорога (магистральная улица)"; "primary_link" -> "Съезд с главной дороги"
        "secondary" -> "Улица районного значения"; "secondary_link" -> "Съезд"
        "tertiary" -> "Улица местного значения"; "tertiary_link" -> "Съезд"
        "unclassified" -> "Дорога без класса"; "residential" -> "Жилая улица"; "living_street" -> "Жилая зона"
        "service" -> "Проезд"; "pedestrian" -> "Пешеходная улица"; "road" -> "Дорога"
        "track" -> "Грунтовка"; "footway" -> "Тротуар / пешеходная дорожка"; "path" -> "Тропинка"; "cycleway" -> "Велодорожка"
        "steps" -> "Лестница"; "bridleway" -> "Конная тропа"
        else -> "Улица"
    }
    val SURFACE = mapOf("asphalt" to "асфальт", "paved" to "твёрдое", "unpaved" to "грунт", "ground" to "грунт", "dirt" to "земля", "gravel" to "гравий",
        "fine_gravel" to "мелкий гравий", "compacted" to "укатанное", "grass" to "трава", "sand" to "песок", "paving_stones" to "плитка", "sett" to "брусчатка",
        "cobblestone" to "булыжник", "concrete" to "бетон", "concrete:plates" to "бетонные плиты", "wood" to "дерево")

    private fun GLMapVectorObject.tag(k: String): String? = runCatching { valueForKey(k)?.use { it.string } }.getOrNull()?.takeIf { it.isNotBlank() }
    private fun GLMapVectorObject.lines(): List<List<GeoPt>> = runCatching {
        val out = ArrayList<List<GeoPt>>(); val tmp = MapPoint()
        for (li in 0 until lineCount) {
            val s = getLinePointStart(li); val n = getLinePointCount(li)
            out += (s until s + n).map { i -> getPointAtIndex(i, tmp); val g = MapGeoPoint(tmp); GeoPt(g.lat, g.lon, 0) }
        }
        out.filter { it.size >= 2 }
    }.getOrDefault(emptyList())

    private fun bbox(lat: Double, lon: Double, m: Double): GLMapBBox {
        val dLat = m / 111_000.0; val dLon = m / (111_000.0 * Math.cos(Math.toRadians(lat)))
        return GLMapBBox().apply { addPoint(MapPoint.CreateFromGeoCoordinates(lat - dLat, lon - dLon)); addPoint(MapPoint.CreateFromGeoCoordinates(lat + dLat, lon + dLon)) }
    }

    /** Ближайшая к точке дорога в радиусе [m] метров — из скачанных/закэшированных данных GLMap. */
    fun nearestOffline(lat: Double, lon: Double, m: Double): Hit? = runCatching {
        if (!GlMap.ready) return null
        val s = GLSearch()
        try {
            val bb = bbox(lat, lon, m)
            s.setCenter(MapPoint.CreateFromGeoCoordinates(lat, lon)); s.setBBox(bb); s.setLimit(60)
            runCatching { GLMapManager.VectorTilesAtBBox(bb)?.let { s.setTiles(it) } }
            ROADS.forEach { runCatching { s.addFilter(GLSearchFilter.createWithQueryAndTag(it, "highway")) } }
            val objs = s.search()?.let { l -> l.toArray().also { runCatching { l.dispose() } } }.orEmpty()
            val here = GeoPt(lat, lon, 0)
            val best = objs.mapNotNull { o -> val ln = o.lines(); if (ln.isEmpty()) null else Triple(o, ln, ln.minOf { pl -> pl.minOf { Osm.distance(here, it) } }) }
                .minByOrNull { it.third }
            val hit = best?.takeIf { it.third < m * 2 }?.let { (o, ln, _) ->
                val tags = TAG_KEYS.mapNotNull { k -> o.tag(k)?.let { k to it } }.toMap()
                val nm = runCatching { o.localizedName(GlMap.locale)?.use { it.string } }.getOrNull()?.takeIf { it.isNotBlank() } ?: tags["name"]
                Hit(nm, tags["highway"] ?: "road", tags, ln)
            }
            objs.forEach { runCatching { it.dispose() } }
            hit
        } finally { runCatching { s.dispose() } }
    }.getOrNull()

    private fun get(url: String, body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "VseVOdnom/0.28 (personal Android app)")
        c.setRequestProperty("Accept-Language", "ru,uk,en")
        c.connectTimeout = 10_000; c.readTimeout = 25_000
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun geoLines(g: JSONObject?): List<List<GeoPt>> {
        g ?: return emptyList()
        fun line(a: JSONArray) = (0 until a.length()).map { val p = a.getJSONArray(it); GeoPt(p.getDouble(1), p.getDouble(0), 0) }
        val c = g.optJSONArray("coordinates") ?: return emptyList()
        return when (g.optString("type")) {
            "LineString" -> listOf(line(c))
            "MultiLineString" -> (0 until c.length()).map { line(c.getJSONArray(it)) }
            "Polygon" -> (0 until c.length()).map { line(c.getJSONArray(it)) }
            "MultiPolygon" -> (0 until c.length()).flatMap { i -> val p = c.getJSONArray(i); (0 until p.length()).map { line(p.getJSONArray(it)) } }
            else -> emptyList()
        }.filter { it.size >= 2 }
    }

    /** Онлайн: что за дорога в точке (Nominatim, масштаб «улица»). */
    suspend fun nearestOnline(lat: Double, lon: Double): Hit? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(Osm.raw("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&extratags=1&namedetails=1&polygon_geojson=1&lat=$lat&lon=$lon"))
            if (o.optString("category") != "highway") return@runCatching null
            val ex = o.optJSONObject("extratags") ?: JSONObject()
            val tags = ex.keys().asSequence().associateWith { ex.optString(it) } + ("highway" to o.optString("type"))
            val type = o.optString("osm_type"); val id = o.optLong("osm_id")
            Hit(o.optString("name").ifBlank { null }, o.optString("type"), tags, geoLines(o.optJSONObject("geojson")),
                if (type.isNotBlank() && id > 0) "https://www.openstreetmap.org/$type/$id" else "")
        }.getOrNull()
    }

    /** Расстояние от точки до ближайшего отрезка линий, м (по плоскому приближению — для десятков метров точно). */
    fun distanceTo(p: GeoPt, lines: List<List<GeoPt>>): Double {
        val kx = 111_320.0 * Math.cos(Math.toRadians(p.lat)); val ky = 110_540.0
        var best = Double.MAX_VALUE
        for (l in lines) for (i in 1 until l.size) {
            val ax = (l[i - 1].lon - p.lon) * kx; val ay = (l[i - 1].lat - p.lat) * ky
            val bx = (l[i].lon - p.lon) * kx; val by = (l[i].lat - p.lat) * ky
            val dx = bx - ax; val dy = by - ay; val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
            best = minOf(best, Math.hypot(ax + t * dx, ay + t * dy))
        }
        if (lines.any { it.size == 1 }) lines.filter { it.size == 1 }.forEach { best = minOf(best, Osm.distance(p, it[0])) }
        return best
    }

    private fun norm(s: String?) = s?.lowercase()?.replace('ё', 'е')?.replace(Regex("\\s+"), " ")?.trim()

    /**
     * Вся улица: все отрезки с этим названием в 6 км — офлайн из GLMap, иначе Overpass. [name] — тег name из OSM,
     * [alt] — название, как его показывает карта (может быть по-русски): подходит любое из двух.
     */
    suspend fun whole(name: String, lat: Double, lon: Double, alt: String? = null): List<List<GeoPt>> = withContext(Dispatchers.IO) {
        val names = setOfNotNull(norm(name), norm(alt))
        val off = runCatching {
            if (!GlMap.ready) return@runCatching emptyList<List<GeoPt>>()
            val out = ArrayList<List<GeoPt>>()
            for (q in listOfNotNull(name, alt?.takeIf { norm(it) != norm(name) })) {
                val s = GLSearch()
                try {
                    val bb = bbox(lat, lon, 6000.0)
                    s.setLocaleSettings(GlMap.locale)
                    s.setCenter(MapPoint.CreateFromGeoCoordinates(lat, lon)); s.setBBox(bb); s.setLimit(500)
                    runCatching { GLMapManager.VectorTilesAtBBox(bb)?.let { s.setTiles(it) } }
                    s.addFilter(GLSearchFilter.createWithQuery(q))
                    val objs = s.search()?.let { l -> l.toArray().also { runCatching { l.dispose() } } }.orEmpty()
                    out += objs.filter { o -> o.tag("highway") != null &&
                        (norm(o.tag("name")) in names || norm(runCatching { o.localizedName(GlMap.locale)?.use { it.string } }.getOrNull()) in names) }
                        .flatMap { it.lines() }
                    objs.forEach { runCatching { it.dispose() } }
                } finally { runCatching { s.dispose() } }
            }
            out.distinctBy { l -> "${l.first().lat}|${l.first().lon}|${l.last().lat}|${l.last().lon}" }
        }.getOrDefault(emptyList())
        if (off.size > 1 || !Net.online) return@withContext off
        runCatching {
            val q = "[out:json][timeout:20];way(around:6000,$lat,$lon)[\"highway\"][\"name\"=\"${name.replace("\"", "\\\"")}\"];out geom;"
            val o = JSONObject(get("https://overpass-api.de/api/interpreter", "data=" + URLEncoder.encode(q, "UTF-8")))
            val els = o.getJSONArray("elements")
            (0 until els.length()).mapNotNull { i ->
                val g = els.getJSONObject(i).optJSONArray("geometry") ?: return@mapNotNull null
                (0 until g.length()).map { val p = g.getJSONObject(it); GeoPt(p.getDouble("lat"), p.getDouble("lon"), 0) }.takeIf { it.size >= 2 }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: off
    }

    /** Граница района/города/страны (контур) — онлайн из Nominatim по названию рядом с точкой. */
    suspend fun boundary(name: String, lat: Double, lon: Double, place: String?): List<List<GeoPt>> = withContext(Dispatchers.IO) {
        runCatching {
            val d = when (place) { "country" -> 25.0; "state", "region", "province" -> 6.0; "city", "town" -> 1.0; else -> 0.3 }
            val box = "${lon - d},${lat + d},${lon + d},${lat - d}"
            val a = JSONArray(Osm.raw("https://nominatim.openstreetmap.org/search?format=jsonv2&polygon_geojson=1&polygon_threshold=0.0005&limit=5&viewbox=$box&bounded=1&q=" + URLEncoder.encode(name, "UTF-8")))
            val best = (0 until a.length()).map { a.getJSONObject(it) }
                .firstOrNull { val t = it.optJSONObject("geojson")?.optString("type"); (t == "Polygon" || t == "MultiPolygon") && (it.optString("category") == "boundary" || it.optString("category") == "place") }
            geoLines(best?.optJSONObject("geojson"))
        }.getOrDefault(emptyList())
    }
}
