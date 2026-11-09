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

    /**
     * «Похожие рядом»: тот же тег (ключ=значение) в 3 км, если мало — в 10 км; само место не показываем, ближние первыми.
     */
    suspend fun similar(ctx: Context, key: String, value: String, lat: Double, lon: Double, limit: Int = 30): List<GeoHit> = withContext(Dispatchers.IO) {
        if (!GlMap.init(ctx) || key.isBlank() || value.isBlank()) return@withContext emptyList()
        val here = GeoPt(lat, lon, 0)
        var out = emptyList<GeoHit>()
        for (km in listOf(3.0, 10.0)) {
            val dLat = km / 111.0; val dLon = km / (111.0 * Math.cos(Math.toRadians(lat)))
            val bb = GLMapBBox().apply { addPoint(MapPoint.CreateFromGeoCoordinates(lat - dLat, lon - dLon)); addPoint(MapPoint.CreateFromGeoCoordinates(lat + dLat, lon + dLon)) }
            val found = runCatching {
                val s = GLSearch()
                try {
                    s.setCenter(MapPoint.CreateFromGeoCoordinates(lat, lon))
                    s.setBBox(bb)
                    s.setLimit(200)
                    runCatching { GLMapManager.VectorTilesAtBBox(bb)?.let { s.setTiles(it) } }
                    s.addFilter(GLSearchFilter.createWithQueryAndTag(value, key))
                    s.search()?.let { list(it) }.orEmpty()
                } finally { runCatching { s.dispose() } }
            }.getOrDefault(emptyList())
            out = found.filter { Osm.distance(here, GeoPt(it.lat, it.lon, 0)).let { d -> d > 20 && d < km * 1000 } }
                .sortedBy { Osm.distance(here, GeoPt(it.lat, it.lon, 0)) }
                .distinctBy { "${it.name}|${(it.lat * 3000).toInt()}|${(it.lon * 3000).toInt()}" }
            if (out.size >= 6) break
        }
        out.take(limit)
    }
}
