package com.pank.vsevodnom.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Высота местности из скачанного рельефа страны (<id>-dem.mbtiles, Terrarium PNG, до z11 — шаг ~50 м).
 * Нужна там, где в треке нет высот GPS: построенные маршруты, импорт без высот. Те же файлы потом пойдут на 3D-рельеф.
 */
object Dem {
    private val dbs = HashMap<String, Pair<SQLiteDatabase, Int>>()       // id → (база, макс. зум)
    private val tiles = object : LinkedHashMap<String, IntArray?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray?>?) = size > 40
    }

    fun available(ctx: Context) = OfflineMaps.installed(ctx).filterValues { "dem" in it.second }.keys

    private fun db(ctx: Context, id: String): Pair<SQLiteDatabase, Int>? = synchronized(dbs) {
        dbs[id]?.takeIf { it.first.isOpen } ?: run {
            val f = OfflineMaps.file(ctx, id, "dem")
            if (!f.exists()) return null
            val d = runCatching { SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS) }.getOrNull() ?: return null
            val mz = runCatching { d.rawQuery("SELECT value FROM metadata WHERE name='maxzoom'", null).use { c -> if (c.moveToFirst()) c.getString(0).toInt() else 11 } }.getOrDefault(11)
            (d to mz.coerceIn(5, 14)).also { dbs[id] = it }
        }
    }

    /** Terrarium: (R·256 + G + B/256) − 32768 метров. */
    fun terrarium(px: Int): Double = ((px shr 16) and 255) * 256.0 + ((px shr 8) and 255) + (px and 255) / 256.0 - 32768.0

    /** Доли пикселя в тайле зума z: (x тайла, y тайла, x пикселя 0…256, y пикселя). */
    fun pixel(lat: Double, lon: Double, z: Int): DoubleArray {
        val n = (1 shl z).toDouble()
        val fx = (lon + 180.0) / 360.0 * n
        val r = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        val fy = (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n
        return doubleArrayOf(floor(fx), floor(fy), (fx - floor(fx)) * 256, (fy - floor(fy)) * 256)
    }

    private fun tile(d: SQLiteDatabase, key: String, z: Int, x: Int, y: Int): IntArray? = synchronized(tiles) {
        if (tiles.containsKey(key)) return tiles[key]
        val px = runCatching {
            d.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", arrayOf(z.toString(), x.toString(), ((1 shl z) - 1 - y).toString())).use { c ->
                if (!c.moveToFirst()) null else {
                    val b = c.getBlob(0)
                    val bm = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) ?: return@use null
                    IntArray(bm.width * bm.height).also { bm.getPixels(it, 0, bm.width, 0, 0, bm.width, bm.height); bm.recycle() }
                }
            }
        }.getOrNull()
        tiles[key] = px
        return px
    }

    /** Высота точки или NaN, если рельефа на телефоне нет. */
    fun elevation(ctx: Context, lat: Double, lon: Double): Double {
        for (id in available(ctx)) {
            val (d, z) = db(ctx, id) ?: continue
            val p = pixel(lat, lon, z)
            val x = p[0].toInt(); val y = p[1].toInt()
            val px = tile(d, "$id/$z/$x/$y", z, x, y) ?: continue
            val side = kotlin.math.sqrt(px.size.toDouble()).toInt()
            val sx = (p[2] * side / 256 - 0.5).coerceIn(0.0, side - 1.001); val sy = (p[3] * side / 256 - 0.5).coerceIn(0.0, side - 1.001)
            val ix = sx.toInt(); val iy = sy.toInt(); val ax = sx - ix; val ay = sy - iy
            fun h(i: Int, j: Int) = terrarium(px[j * side + i])
            val v = h(ix, iy) * (1 - ax) * (1 - ay) + h(ix + 1, iy) * ax * (1 - ay) + h(ix, iy + 1) * (1 - ax) * ay + h(ix + 1, iy + 1) * ax * ay
            if (v > -500 && v < 9000) return v
        }
        // рельеф GLMap (скачанные карты GLMap с рельефом)
        return GlMap.elevation(lat, lon)
    }

    /**
     * Точки с высотой из рельефа: где высоты нет — берём из DEM. Длинные прямые отрезки (маршрут по дороге) дробим
     * через ~40 м, иначе подъёмы между редкими точками не видны. null — рельефа для этих мест на телефоне нет.
     */
    fun fill(ctx: Context, pts: List<GeoPt>): List<GeoPt>? {
        if (pts.isEmpty() || (available(ctx).isEmpty() && !GlMap.ready)) return null
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
