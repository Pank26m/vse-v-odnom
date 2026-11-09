package com.pank.vsevodnom.ui.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.pank.vsevodnom.data.OnlineMaps
import globus.glmap.GLMapFileStorage
import globus.glmap.GLMapRasterTileSource
import globus.glmap.GLMapStorageFile
import java.io.File

private fun cache(ctx: Context, name: String): GLMapStorageFile? = runCatching {
    GLMapFileStorage(OnlineMaps.cacheDir(ctx)).findFile("$name.db", true)
}.getOrNull()

/** Онлайн-карта (OSM, спутник, свои .ms — Google и др.): тайлы по адресу слоя, кэш — в files/raster. */
internal class MsTileSource(ctx: Context, private val layer: OnlineMaps.Layer, id: String, attribution: String) :
    GLMapRasterTileSource(cache(ctx, id.replace(Regex("[^A-Za-z0-9_-]"), "_"))) {
    init {
        runCatching {
            var mask = 0
            for (z in layer.minZoom.coerceAtLeast(0)..layer.maxZoom.coerceAtMost(22)) mask = mask or (1 shl z)
            setValidZoomMask(mask)
            setTileSize(layer.tileSize)
            setOverzoom(true)
            if (attribution.isNotBlank()) setAttributionText(attribution)
        }
    }
    override fun urlForTilePos(x: Int, y: Int, z: Int): String = OnlineMaps.url(layer, z, x, y)
}

/**
 * Рельеф мира (цветной, с тенями гор) на мелких масштабах — как карта мира в Maps.me. Встроенный файл z0–4
 * (assets/map/world-z4.mbtiles), дальше растягивается; прозрачный — подписи и границы GLMap видны сквозь него.
 */
internal class ReliefTileSource(ctx: Context) : GLMapRasterTileSource(null) {
    private val db: SQLiteDatabase? = runCatching {
        val f = File(ctx.filesDir, "relief/world-z4.mbtiles")
        if (!f.exists() || f.length() == 0L) {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            ctx.assets.open("map/world-z4.mbtiles").use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(f)
        }
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    }.getOrNull()

    init {
        runCatching { setValidZoomMask((1 shl 0) or (1 shl 1) or (1 shl 2) or (1 shl 3) or (1 shl 4)); setOverzoom(true); setOpacity(0.55f) }
    }

    override fun urlForTilePos(x: Int, y: Int, z: Int): String? = null

    override fun bitmapForTilePos(x: Int, y: Int, z: Int): Bitmap? = runCatching {
        val d = db ?: return null
        d.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", arrayOf(z.toString(), x.toString(), ((1 shl z) - 1 - y).toString())).use { c ->
            if (!c.moveToFirst()) null else c.getBlob(0).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    }.getOrNull()

    fun closeDb() { runCatching { db?.close() } }
}
