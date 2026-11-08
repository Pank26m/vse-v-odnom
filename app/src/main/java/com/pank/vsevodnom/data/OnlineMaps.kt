package com.pank.vsevodnom.data

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.storage.FileSource
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream

/**
 * Онлайн-карты (растровые тайлы): встроенные + свои из файлов .ms (формат Guru Maps / Galileo:
 * `<customMapSource><name/><minZoom/><maxZoom/><url/><serverParts/></customMapSource>`, слои — `<layers>`).
 * Тайлы попадают в кэш MapLibre на телефоне и лежат там, пока его не очистить в «Кэше карт».
 */
object OnlineMaps {
    data class Layer(val tiles: List<String>, val minZoom: Int = 0, val maxZoom: Int = 19, val tileSize: Int = 256, val tms: Boolean = false)
    data class Source(val id: String, val name: String, val layers: List<Layer>, val attribution: String = "", val builtIn: Boolean = false)

    val BUILT_IN = listOf(
        Source("osm", "OpenStreetMap", listOf(Layer(listOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png"), maxZoom = 19)), "© OpenStreetMap", true),
        Source("topo", "OpenTopoMap (рельеф)", listOf(Layer(listOf("a", "b", "c").map { "https://$it.tile.opentopomap.org/{z}/{x}/{y}.png" }, maxZoom = 17)), "© OpenTopoMap, OpenStreetMap", true),
        Source("esri", "Спутник (Esri)", listOf(Layer(listOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"), maxZoom = 19)), "© Esri", true),
        Source("cyclosm", "CyclOSM (вело)", listOf(Layer(listOf("a", "b", "c").map { "https://$it.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png" }, maxZoom = 20)), "© CyclOSM, OpenStreetMap", true),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("map", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Source> = BUILT_IN + custom(ctx)

    fun custom(ctx: Context): List<Source> = runCatching {
        val a = JSONArray(prefs(ctx).getString("online_sources", "[]"))
        (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveCustom(ctx: Context, list: List<Source>) = prefs(ctx).edit().putString("online_sources", JSONArray(list.map { toJson(it) }).toString()).apply()

    private fun toJson(s: Source) = JSONObject().put("id", s.id).put("name", s.name).put("attr", s.attribution).put("layers", JSONArray(s.layers.map { l ->
        JSONObject().put("tiles", JSONArray(l.tiles)).put("min", l.minZoom).put("max", l.maxZoom).put("size", l.tileSize).put("tms", l.tms)
    }))
    private fun fromJson(o: JSONObject) = Source(o.getString("id"), o.getString("name"), o.getJSONArray("layers").let { a ->
        (0 until a.length()).map { i -> val l = a.getJSONObject(i); val t = l.getJSONArray("tiles")
            Layer((0 until t.length()).map { t.getString(it) }, l.optInt("min"), l.optInt("max", 19), l.optInt("size", 256), l.optBoolean("tms")) }
    }, o.optString("attr"))

    /** Разбор .ms. Подстановки Guru: {$x} {$y} {$z} {$quad} {$serverpart} {$invY}. */
    fun parseMs(input: InputStream, fileName: String): Source? {
        val p = Xml.newPullParser(); p.setInput(input, null)
        var name = ""; val layers = ArrayList<Layer>()
        var url = ""; var parts = ""; var minZ = 0; var maxZ = 19; var size = 256
        val text = StringBuilder()
        fun flush() {
            if (url.isBlank()) return
            var u = url.trim().replace("&amp;", "&")
            val tms = u.contains("{\$invY}")
            u = u.replace("{\$x}", "{x}").replace("{\$y}", "{y}").replace("{\$invY}", "{y}").replace("{\$z}", "{z}").replace("{\$quad}", "{quadkey}")
            val sp = parts.trim().split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
            val tiles = if (u.contains("{\$serverpart}") && sp.isNotEmpty()) sp.map { u.replace("{\$serverpart}", it) } else listOf(u.replace("{\$serverpart}", ""))
            layers += Layer(tiles, minZ, maxZ, size, tms)
            url = ""; parts = ""
        }
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> { text.setLength(0); if (p.name == "customMapSource" && url.isNotBlank()) flush() }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val t = text.toString().trim()
                    when (p.name) {
                        "name" -> if (name.isBlank()) name = t
                        "url" -> url = t
                        "serverParts" -> parts = t
                        "minZoom" -> minZ = t.toIntOrNull() ?: 0
                        "maxZoom" -> maxZ = t.toIntOrNull() ?: 19
                        "tileSize" -> size = t.toIntOrNull() ?: 256
                        "customMapSource" -> flush()
                    }
                    text.setLength(0)
                }
            }
            p.next()
        }
        flush()
        if (layers.isEmpty()) return null
        return Source("ms_" + System.currentTimeMillis(), name.ifBlank { fileName.substringBeforeLast('.') }, layers, name)
    }

    // ---------- кэш ----------
    fun cacheFile(ctx: Context) = File(FileSource.getResourcesCachePath(ctx), "mbgl-offline.db")
    fun cacheBytes(ctx: Context) = cacheFile(ctx).length()
    /** Предел кэша в байтах; 0 — без предела (MapLibre по умолчанию держит 50 МБ). */
    fun cacheLimit(ctx: Context) = prefs(ctx).getLong("cache_limit", 2L shl 30)
    fun applyCacheLimit(ctx: Context, bytes: Long = cacheLimit(ctx)) {
        prefs(ctx).edit().putLong("cache_limit", bytes).apply()
        runCatching { OfflineManager.getInstance(ctx).setMaximumAmbientCacheSize(if (bytes <= 0) Long.MAX_VALUE / 4 else bytes, null) }
    }
    fun clearCache(ctx: Context, done: () -> Unit) = runCatching {
        OfflineManager.getInstance(ctx).clearAmbientCache(object : OfflineManager.FileSourceCallback {
            override fun onSuccess() = done()
            override fun onError(message: String) = done()
        })
    }
}
