package com.pank.vsevodnom.data

import android.content.Context
import globus.glmap.GLMapBBox
import globus.glmap.GLMapError
import globus.glmap.GLMapManager
import globus.glmap.GLMapVectorObject
import globus.glmap.GLMapVectorObjectList
import globus.glmap.MapGeoPoint
import globus.glmap.MapPoint
import globus.glsearch.GLSearch
import globus.glsearch.GLSearchFilter
import globus.glsearch.GLSearchRequest
import globus.glsearch.GLSearchRequestType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Поиск движком GLMap: офлайн — по скачанным картам GLMap (названия, адреса, заведения), онлайн — сервер GLMap.
 * «Похожие рядом» — объекты с тем же тегом OSM (amenity=bench, shop=bakery…) вокруг точки, в том числе сервисные
 * (лавочки, урны, бюветы, туалеты), которых нет среди «мест» обычного поиска.
 */
object GlFind {
    val KEYS = listOf("amenity", "shop", "tourism", "leisure", "historic", "office", "craft", "healthcare", "emergency", "natural", "railway",
        "public_transport", "aeroway", "aerialway", "man_made", "barrier", "sport", "highway", "place", "waterway", "landuse", "building")

    private fun GLMapVectorObject.tag(k: String): String? = runCatching { valueForKey(k)?.use { it.string } }.getOrNull()?.takeIf { it.isNotBlank() }

    fun kindOf(o: GLMapVectorObject): String {
        for (k in KEYS) o.tag(k)?.let { return "$k:$it" }
        return ""
    }

    private fun hit(o: GLMapVectorObject): GeoHit? = runCatching {
        val g = MapGeoPoint(o.point())
        val info = runCatching { GLSearch.GetDisplayInfo(o, GlMap.locale) }.getOrNull()
        val title = info?.title?.let { runCatching { it.string }.getOrNull() }
        val sub = info?.secondaryText?.let { runCatching { it.string }.getOrNull() }
        val cat = info?.category?.let { c -> runCatching { c.localizedName(GlMap.locale)?.use { it.string } }.getOrNull() }
        runCatching { info?.close() }
        val kind = kindOf(o)
        val name = title ?: runCatching { o.localizedName(GlMap.locale)?.use { it.string } }.getOrNull() ?: cat ?: MapNames.kind(kind).ifBlank { return null }
        GeoHit(name, listOfNotNull(cat ?: MapNames.kind(kind).ifBlank { null }, sub).distinct().joinToString(" · "), g.lat, g.lon, kind)
    }.getOrNull()

    private fun list(l: GLMapVectorObjectList): List<GeoHit> = runCatching { l.toArray().mapNotNull { o -> hit(o).also { runCatching { o.dispose() } } } }.getOrDefault(emptyList()).also { runCatching { l.dispose() } }

    /** Поиск по тексту возле точки. offline=false — сервер GLMap. */
    suspend fun search(ctx: Context, q: String, lat: Double, lon: Double, offline: Boolean, limit: Int = 40): List<GeoHit> {
        if (q.isBlank() || !GlMap.init(ctx)) return emptyList()
        return suspendCancellableCoroutine { cont ->
            val req = GLSearchRequest(GLSearchRequestType.Search, q, MapGeoPoint(lat, lon), limit, arrayOf("ru", "uk", "native", "en"), null)
            val cb = object : GLSearchRequest.ResultsCallback {
                override fun onResult(objects: GLMapVectorObjectList) { val r = list(objects); if (cont.isActive) cont.resume(r) }
                override fun onError(error: GLMapError) { if (cont.isActive) cont.resume(emptyList()) }
            }
            val id = runCatching { if (offline) req.startOffline(cb) else req.startOnline(cb) }.getOrElse { if (cont.isActive) cont.resume(emptyList()); 0L }
            cont.invokeOnCancellation { if (id != 0L) runCatching { GLSearchRequest.cancel(id) } }
        }
    }

    /** Есть ли скачанные карты GLMap (офлайн-поиск по ним). */
    fun offlineAvailable() = GlMap.downloaded().isNotEmpty()

    /** Свойства места по виду «ключ:значение» (у результатов поиска и «похожих»), чтобы у них тоже работали «Похожие рядом». */
    fun propsOf(kind: String): Map<String, String> {
        val k = kind.substringBefore(':', ""); val v = kind.substringAfter(':', "")
        return if (k in KEYS && v.isNotBlank()) mapOf(k to v, "osm_key" to k, "osm_value" to v) else emptyMap()
    }

    private fun http(url: String, body: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("User-Agent", "VseVOdnom/0.29 (personal Android app)")
        c.connectTimeout = 8_000; c.readTimeout = 30_000; c.requestMethod = "POST"; c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray()) }
        return c.inputStream.bufferedReader().use { it.readText() }
    }
    private fun overpass(q: String): org.json.JSONArray = org.json.JSONObject(http("https://overpass-api.de/api/interpreter", "data=" + java.net.URLEncoder.encode(q, "UTF-8"))).getJSONArray("elements")
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Вид места, если о нём ничего не известно (метка, Google): ближайший объект OSM в 40 м (с тем же названием, если есть). Онлайн. */
    suspend fun kindNear(lat: Double, lon: Double, name: String?): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (!Net.online) return@withContext null
        runCatching {
            val els = overpass("[out:json][timeout:15];nwr(around:40,$lat,$lon)[~\"^(amenity|shop|tourism|leisure|historic|office|craft|healthcare)$\"~\".\"];out tags center 30;")
            val all = (0 until els.length()).map { els.getJSONObject(it) }
            val pick = all.firstOrNull { e -> name != null && e.optJSONObject("tags")?.optString("name")?.equals(name, true) == true } ?: all.firstOrNull()
            val t = pick?.optJSONObject("tags") ?: return@runCatching null
            KEYS.firstOrNull { t.has(it) }?.let { it to t.getString(it) }
        }.getOrNull()
    }

    /**
     * «Похожие рядом»: тот же тег (ключ=значение) — 30 ближайших по прямой, а не всё в радиусе. Ищем кругами
     * 1 → 3 → 10 → 30 км, пока не наберётся 30; на каждом круге — сначала скачанная карта GLMap (быстро, без сети),
     * потом Overpass (OpenStreetMap, там и лавочки, и площадки). Не дольше минуты — что нашлось, то и показываем.
     * onProgress — сколько уже найдено (для плашки «поиск… найдено 7»).
     */
    suspend fun similar(ctx: Context, key: String, value: String, lat: Double, lon: Double, limit: Int = 30,
                        onProgress: suspend (Int) -> Unit = {}): List<GeoHit> = withContext(Dispatchers.IO) {
        if (key.isBlank() || value.isBlank()) return@withContext emptyList()
        val here = GeoPt(lat, lon, 0)
        val label = MapNames.label(key, value)
        val found = LinkedHashMap<String, GeoHit>()
        fun d(h: GeoHit) = Osm.distance(here, GeoPt(h.lat, h.lon, 0))
        fun add(l: List<GeoHit>) { for (h in l) if (d(h) > 15) found.putIfAbsent("${h.name}|${(h.lat * 3000).toInt()}|${(h.lon * 3000).toInt()}", h) }
        fun best() = found.values.sortedBy { d(it) }.take(limit)
        kotlinx.coroutines.withTimeoutOrNull(60_000) {
            for (km in listOf(1.0, 3.0, 10.0, 30.0)) {
                add(offlineSimilar(ctx, key, value, lat, lon, km, label)); onProgress(found.size)
                if (Net.online) runCatching {
                    kotlinx.coroutines.runInterruptible {
                        val els = overpass("[out:json][timeout:25];nwr(around:${(km * 1000).toInt()},$lat,$lon)[\"${esc(key)}\"=\"${esc(value)}\"];out tags center 300;")
                        add((0 until els.length()).mapNotNull { i ->
                            val e = els.getJSONObject(i)
                            val la = e.optDouble("lat").takeIf { !it.isNaN() } ?: e.optJSONObject("center")?.optDouble("lat") ?: return@mapNotNull null
                            val lo = e.optDouble("lon").takeIf { !it.isNaN() } ?: e.optJSONObject("center")?.optDouble("lon") ?: return@mapNotNull null
                            val t = e.optJSONObject("tags")
                            val nm = t?.optString("name:ru")?.ifBlank { null } ?: t?.optString("name")?.ifBlank { null } ?: label
                            val addr = listOfNotNull(t?.optString("addr:street")?.ifBlank { null }, t?.optString("addr:housenumber")?.ifBlank { null }).joinToString(", ")
                            GeoHit(nm, listOf(label, addr).filter { it.isNotBlank() }.distinct().joinToString(" · "), la, lo, "$key:$value")
                        })
                    }
                }
                onProgress(found.size)
                // круг дал 30 и больше — дальние уже не ближе: стоп
                if (found.values.count { d(it) <= km * 1000 } >= limit) break
            }
        }
        best()
    }

    /** Без интернета: поиск по скачанным картам GLMap; имена — из тегов, без GetDisplayInfo. */
    private fun offlineSimilar(ctx: Context, key: String, value: String, lat: Double, lon: Double, km: Double, label: String): List<GeoHit> {
        if (!GlMap.init(ctx) || !offlineAvailable()) return emptyList()
        val dLat = km / 111.0; val dLon = km / (111.0 * Math.cos(Math.toRadians(lat)))
        val bb = GLMapBBox().apply { addPoint(MapPoint.CreateFromGeoCoordinates(lat - dLat, lon - dLon)); addPoint(MapPoint.CreateFromGeoCoordinates(lat + dLat, lon + dLon)) }
        return runCatching {
            val s = GLSearch()
            try {
                s.setLocaleSettings(GlMap.locale)
                s.setCenter(MapPoint.CreateFromGeoCoordinates(lat, lon))
                s.setBBox(bb)
                s.setLimit(200)
                runCatching { GLMapManager.VectorTilesAtBBox(bb)?.let { s.setTiles(it) } }
                s.addFilter(GLSearchFilter.createWithQueryAndTag(value, key))
                val l = s.search() ?: return@runCatching emptyList()
                val r = l.toArray().mapNotNull { o ->
                    runCatching {
                        if (o.tag(key) != value) return@runCatching null
                        val g = MapGeoPoint(o.point())
                        val nm = runCatching { o.localizedName(GlMap.locale)?.use { it.string } }.getOrNull()?.ifBlank { null } ?: o.tag("name") ?: label
                        GeoHit(nm, label, g.lat, g.lon, "$key:$value")
                    }.getOrNull().also { runCatching { o.dispose() } }
                }
                runCatching { l.dispose() }
                r
            } finally { runCatching { s.dispose() } }
        }.getOrDefault(emptyList())
    }
}
