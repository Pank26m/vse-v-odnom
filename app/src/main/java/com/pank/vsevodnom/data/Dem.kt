package com.pank.vsevodnom.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Высота местности из рельефа GLMap (скачанная «Топография»). Нужна там, где в треке нет высот GPS: построенные маршруты,
 * импорт без высот. Записанные треки хранят высоту GPS (барометр/спутники) — их не трогаем.
 */
object Dem {
    /** Высота точки из рельефа GLMap («Топография» в Загрузке карт) или NaN. */
    fun elevation(@Suppress("UNUSED_PARAMETER") ctx: Context, lat: Double, lon: Double): Double = GlMap.elevation(lat, lon)

    /**
     * Точки с высотой из рельефа: где высоты нет — берём из DEM. Длинные прямые отрезки (маршрут по дороге) дробим
     * через ~40 м, иначе подъёмы между редкими точками не видны. null — рельефа для этих мест на телефоне нет.
     */
    fun fill(ctx: Context, pts: List<GeoPt>): List<GeoPt>? {
        if (pts.isEmpty() || !GlMap.ready) return null
        val out = ArrayList<GeoPt>(pts.size * 2)
        for (i in pts.indices) {
            val p = pts[i]
            if (i > 0) {
                val a = pts[i - 1]; val d = Osm.distance(a, p)
                val k = (d / 40).toInt()
                for (s in 1 until k) {
                    val f = s.toDouble() / k
                    val lat = a.lat + (p.lat - a.lat) * f; val lon = a.lon + (p.lon - a.lon) * f
                    val t = if (a.t > 0 && p.t > 0) a.t + ((p.t - a.t) * f).toLong() else 0
                    out += GeoPt(lat, lon, t, elevation(ctx, lat, lon))
                }
            }
            out += if (p.alt.isNaN()) p.copy(alt = elevation(ctx, p.lat, p.lon)) else p
        }
        return out.takeIf { o -> o.any { !it.alt.isNaN() } }
    }

    /** Есть ли в треке высоты. */
    fun hasAlt(pts: List<GeoPt>) = pts.count { !it.alt.isNaN() } >= pts.size / 2 && pts.isNotEmpty()

    /** Трек без высот → с высотами из рельефа (сохраняется в базу один раз, помечается в meta «alt: dem»). */
    suspend fun ensure(ctx: Context, t: Track): Track = withContext(Dispatchers.IO) {
        val pts = Osm.decode(t.points)
        if (pts.size < 2 || hasAlt(pts)) return@withContext t
        val filled = fill(ctx, pts) ?: return@withContext t
        val meta = runCatching { if (t.meta.isBlank()) org.json.JSONObject() else org.json.JSONObject(t.meta) }.getOrDefault(org.json.JSONObject()).put("alt", "dem").toString()
        val nt = t.copy(points = Osm.encode(filled), meta = meta)
        AppDb.get(ctx).map().upsertTrack(nt)
        nt
    }

    /** Высоты из рельефа, а не из GPS. */
    fun fromDem(t: Track) = runCatching { org.json.JSONObject(t.meta).optString("alt") == "dem" }.getOrDefault(false)
}
