package com.pank.vsevodnom.data

import android.content.Context
import globus.glmap.GLMapError
import globus.glmap.GLMapLocaleSettings
import globus.glmap.MapGeoPoint
import globus.glmap.MapPoint
import globus.glroute.CostingOptions
import globus.glroute.GLRoute
import globus.glroute.GLRouteManeuver
import globus.glroute.GLRoutePoint
import globus.glroute.GLRouteRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Маршруты движком GLMap (Valhalla — тот же, что у Guru Maps). Офлайн — по навигационным данным из «Загрузки карт»
 * (у каждой страны/области кнопка «Навигация»), онлайн — сервер GLMap. Подъёмы для пешком/вело штрафуются (use_hills).
 */
object GlRoute {
    @Volatile private var config: String? = null
    private fun config(ctx: Context) = config ?: ctx.assets.open("valhalla.json").use { String(it.readBytes()) }.also { config = it }

    /** Есть ли навигационные данные хоть одной скачанной карты. */
    fun offlineAvailable(): Boolean = GlMap.downloaded().any { GlMap.has(it, globus.glmap.GLMapInfo.DataSetMask.NAVIGATION) }

    suspend fun route(ctx: Context, mode: String, way: List<GeoPt>, offline: Boolean, avoidToll: Boolean = false, avoidFerry: Boolean = false): Routing.Route {
        if (!GlMap.init(ctx)) throw Exception("GLMap не запущен — нужен ключ (Настройки карты → Ключи и сервисы)")
        val r = request(ctx, mode, way, offline, avoidToll, avoidFerry)
        return try { convert(r, way, if (offline) "офлайн" else "онлайн") } finally { runCatching { r.dispose() } }
    }

    private suspend fun request(ctx: Context, mode: String, way: List<GeoPt>, offline: Boolean, avoidToll: Boolean, avoidFerry: Boolean): GLRoute =
        suspendCancellableCoroutine { cont ->
            val req = GLRouteRequest().apply {
                when (mode) {
                    "bike" -> setBicycleWithOptions(CostingOptions.Bicycle().apply { runCatching { useHills = 0.25f; useRoads = 0.4f } })
                    "foot" -> setPedestrianWithOptions(CostingOptions.Pedestrian().apply { runCatching { useHills = 0.35f } })
                    else -> setAutoWithOptions(CostingOptions.Auto().apply {
                        runCatching { road?.let { if (avoidToll) it.useTolls = 0f; if (avoidFerry) it.useFerry = 0f } }
                    })
                }
                runCatching { locale = "ru" }
                runCatching { unitSystem = GLMapLocaleSettings.UnitSystem.International }
                way.forEach { addPoint(GLRoutePoint(it.lat, it.lon, Double.NaN, GLRoutePoint.Type.BREAK)) }
            }
            val cb = object : GLRouteRequest.ResultsCallback {
                override fun onResult(route: GLRoute) { if (cont.isActive) cont.resume(route) else route.dispose() }
                override fun onError(error: GLMapError) {
                    val t = error.toString()
                    val msg = if (offline) "Офлайн-маршрут не построен" + (if (t.contains("tile", true) || t.contains("data", true) || t.contains("location", true))
                        " — нет навигационных данных этого места (Загрузка карт → «Навигация»)" else ": $t") else "Онлайн-маршрут не построен: $t"
                    if (cont.isActive) cont.resumeWithException(Exception(msg))
                }
            }
            val id = runCatching { if (offline) req.startOffline(config(ctx), cb) else req.startOnline(cb) }
                .getOrElse { if (cont.isActive) cont.resumeWithException(it); 0L }
            cont.invokeOnCancellation { if (id != 0L) runCatching { GLRouteRequest.cancel(id) } }
        }

    private fun geo(p: MapPoint): GeoPt { val g = MapGeoPoint(p); return GeoPt(g.lat, g.lon, 0) }

    /** Точки маршрута: координаты трека (пары x,y во внутренних единицах карты), запасной путь — линии манёвров. */
    private fun points(r: GLRoute, way: List<GeoPt>): List<GeoPt> {
        val near = { pts: List<GeoPt> -> pts.size >= 2 && Osm.distance(pts.first(), way.first()) < 5_000 && Osm.distance(pts.last(), way.last()) < 5_000 }
        runCatching {
            val c = r.trackCoordinates
            if (c != null && c.size >= 4) {
                val pts = (0 until c.size / 2).map { geo(MapPoint(c[it * 2].toDouble(), c[it * 2 + 1].toDouble())) }
                if (near(pts)) return pts
                val pts2 = (0 until c.size / 2).map { GeoPt(c[it * 2].toDouble() / 1e6, c[it * 2 + 1].toDouble() / 1e6, 0) }
                if (near(pts2)) return pts2
                val pts3 = (0 until c.size / 2).map { GeoPt(c[it * 2 + 1].toDouble() / 1e6, c[it * 2].toDouble() / 1e6, 0) }
                if (near(pts3)) return pts3
            }
        }
        val out = ArrayList<GeoPt>()
        val tmp = MapPoint()
        runCatching {
            r.maneuvers.forEach { m ->
                m.line?.use { l ->
                    val n = l.pointCount
                    for (i in 0 until n) { l.getPointAtIndex(i, tmp); val g = geo(tmp); if (out.isEmpty() || Osm.distance(out.last(), g) > 0.5) out += g }
                }
            }
        }
        return out
    }

    private fun cmd(type: Int): Int? = when (type) {
        GLRouteManeuver.Type.Left -> Nav.TL; GLRouteManeuver.Type.Right -> Nav.TR
        GLRouteManeuver.Type.SlightLeft -> Nav.TSLL; GLRouteManeuver.Type.SlightRight -> Nav.TSLR
        GLRouteManeuver.Type.SharpLeft -> Nav.TSHL; GLRouteManeuver.Type.SharpRight -> Nav.TSHR
        GLRouteManeuver.Type.StayLeft, GLRouteManeuver.Type.RampLeft, GLRouteManeuver.Type.MergeLeft -> Nav.KL
        GLRouteManeuver.Type.StayRight, GLRouteManeuver.Type.RampRight, GLRouteManeuver.Type.MergeRight -> Nav.KR
        GLRouteManeuver.Type.ExitLeft -> Nav.EL; GLRouteManeuver.Type.ExitRight -> Nav.ER
        GLRouteManeuver.Type.UturnLeft -> Nav.TLU; GLRouteManeuver.Type.UturnRight -> Nav.TRU
        GLRouteManeuver.Type.RoundaboutEnter -> Nav.RNDB
        GLRouteManeuver.Type.Destination, GLRouteManeuver.Type.DestinationLeft, GLRouteManeuver.Type.DestinationRight -> Nav.END
        else -> null
    }

    private fun convert(r: GLRoute, way: List<GeoPt>, source: String): Routing.Route {
        val pts = points(r, way)
        if (pts.size < 2) throw Exception("Маршрут пустой")
        val steps = ArrayList<Routing.Step>()
        runCatching {
            r.maneuvers.forEach { m ->
                val c = cmd(m.type) ?: return@forEach
                if (c == Nav.END) return@forEach
                val sp = geo(m.startPoint)
                steps += Routing.Step(Routing.nearestIndex(pts, sp.lat, sp.lon), c, if (c == Nav.RNDB) runCatching { m.roundaboutExitCount }.getOrDefault(0) else 0)
            }
        }
        steps += Routing.Step(pts.size - 1, Nav.END)
        val len = runCatching { r.length }.getOrDefault(0.0).let { if (it < 1000 && it > 0 && pts.size > 2 && it < pts.zipWithNext { a, b -> Osm.distance(a, b) }.sum() / 100) it * 1000 else it }
        val meters = if (len > 0) len else pts.zipWithNext { a, b -> Osm.distance(a, b) }.sum()
        return Routing.Route(pts, meters, runCatching { r.duration }.getOrDefault(0.0), steps = steps.sortedBy { it.index }, source = source)
    }
}
