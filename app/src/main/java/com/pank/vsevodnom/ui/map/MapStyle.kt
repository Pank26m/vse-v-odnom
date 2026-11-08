package com.pank.vsevodnom.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.pank.vsevodnom.data.OfflineMaps
import com.pank.vsevodnom.data.OnlineMaps
import org.json.JSONArray
import org.json.JSONObject

/**
 * Офлайн-стиль карты собирается в приложении:
 *  • рельеф мира (растр из mbtiles) — карта мира как в Maps.me, видна везде;
 *  • подписи и границы мира (Natural Earth);
 *  • для каждой скачанной страны — векторные слои OSM Liberty (вид «авто/пешком/вело», assets/map/car|foot|bike.json),
 *    тени рельефа (raster-dem) и линии высот;
 *  • серая штриховка поверх нескачанного при приближении (от зума ~6, это масштаб ~100 км).
 * Шрифты (Noto Sans: латиница + кириллица) и значки — в assets, интернет не нужен.
 */
object MapStyle {
    enum class Variant(val key: String, val title: String) { CAR("car", "На машине"), FOOT("foot", "Пешком"), BIKE("bike", "Велосипед") }

    data class Opts(
        val variant: Variant = Variant.CAR,
        val hillshade: Boolean = true,
        val contours: Boolean = true,
        val relief: Boolean = true,
        val lang: String = "local",           // local | ru | en
        val online: OnlineMaps.Source? = null,
        val worldLabels: Boolean = true,
        val traffic: String? = null,          // ключ TomTom, если слой пробок включён
    )

    const val HATCH_MIN_ZOOM = 5.5
    private val layerCache = HashMap<Variant, String>()

    fun opts(ctx: Context): Opts {
        val p = ctx.getSharedPreferences("map", Context.MODE_PRIVATE)
        val online = p.getString("online_id", null)?.let { id -> OnlineMaps.all(ctx).firstOrNull { it.id == id } }
        return Opts(
            Variant.entries.firstOrNull { it.key == p.getString("variant", "car") } ?: Variant.CAR,
            p.getBoolean("hillshade", true), p.getBoolean("contours", true), p.getBoolean("relief", true),
            p.getString("lang", "local") ?: "local", if (p.getBoolean("use_online", false)) online else null,
            traffic = com.pank.vsevodnom.data.ApiKeys.tomtom(ctx).takeIf { it.isNotBlank() && p.getBoolean("traffic", false) },
        )
    }

    private fun nameExpr(lang: String): JSONArray = when (lang) {
        "ru" -> JSONArray("""["coalesce",["get","name:ru"],["get","name"]]""")
        "en" -> JSONArray("""["coalesce",["get","name_en"],["get","name:en"],["get","name:latin"],["get","name"]]""")
        else -> JSONArray("""["coalesce",["get","name"],["get","name:latin"]]""")
    }

    private fun worldNameExpr(lang: String): JSONArray = when (lang) {
        "en" -> JSONArray("""["coalesce",["get","name_en"],["get","name"]]""")
        "local" -> JSONArray("""["coalesce",["get","name_ru"],["get","name"]]""")
        else -> JSONArray("""["coalesce",["get","name_ru"],["get","name"]]""")
    }

    /** Дырка должна идти по часовой (внешнее кольцо мира — против): MapLibre различает кольца по направлению. */
    private fun ccwOff(r: JSONArray): JSONArray {
        var a = 0.0
        for (i in 0 until r.length() - 1) { val p = r.getJSONArray(i); val q = r.getJSONArray(i + 1); a += p.getDouble(0) * q.getDouble(1) - q.getDouble(0) * p.getDouble(1) }
        if (a <= 0) return r
        val out = JSONArray(); for (i in r.length() - 1 downTo 0) out.put(r.get(i)); return out
    }

    /** Мир минус скачанные страны: внешнее кольцо — весь мир, дырки — границы стран. */
    private fun hatchGeo(shapes: List<JSONObject>): JSONObject {
        val world = JSONArray("[[-180,-85],[180,-85],[180,85],[-180,85],[-180,-85]]")
        val rings = JSONArray().put(world)
        shapes.forEach { g ->
            val polys = if (g.getString("type") == "Polygon") JSONArray().put(g.getJSONArray("coordinates")) else g.getJSONArray("coordinates")
            for (i in 0 until polys.length()) rings.put(ccwOff(polys.getJSONArray(i).getJSONArray(0)))
        }
        return JSONObject().put("type", "Feature").put("properties", JSONObject())
            .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", rings))
    }

    private fun shapesGeo(shapes: List<JSONObject>): JSONObject {
        val polys = JSONArray()
        shapes.forEach { g ->
            if (g.getString("type") == "Polygon") polys.put(g.getJSONArray("coordinates"))
            else { val a = g.getJSONArray("coordinates"); for (i in 0 until a.length()) polys.put(a.getJSONArray(i)) }
        }
        return JSONObject().put("type", "MultiPolygon").put("coordinates", polys)
    }

    /** Что лежит на телефоне: пути к файлам. id страны → (вид части → путь). */
    class Input(val layersJson: String, val worldRelief: String, val worldLabels: String?, val files: Map<String, Map<String, String>>, val shapes: List<JSONObject>)

    fun build(ctx: Context, o: Opts): String {
        val inst = OfflineMaps.installed(ctx)
        val files = inst.mapValues { (id, v) -> v.second.associateWith { OfflineMaps.file(ctx, id, it).absolutePath } }
        val input = Input(layerCache.getOrPut(o.variant) { ctx.assets.open("map/${o.variant.key}.json").bufferedReader().use { it.readText() } },
            OfflineMaps.worldRelief(ctx).absolutePath, OfflineMaps.worldLabels(ctx)?.absolutePath, files, OfflineMaps.shapes(ctx).map { it.second })
        return compose(input, o)
    }

    fun compose(input: Input, o: Opts): String {
        val sources = JSONObject()
        val layers = JSONArray()
        val shapeGeoms = input.shapes
        val name = nameExpr(o.lang)
        val hasWorld = o.worldLabels && input.worldLabels != null

        layers.put(JSONObject("""{"id":"bg","type":"background","paint":{"background-color":"#e6e4de"}}"""))

        if (o.online != null) {
            o.online.layers.forEachIndexed { i, l ->
                sources.put("on$i", JSONObject().put("type", "raster").put("tileSize", l.tileSize).put("minzoom", l.minZoom).put("maxzoom", l.maxZoom)
                    .put("scheme", if (l.tms) "tms" else "xyz").put("tiles", JSONArray(l.tiles)).put("attribution", o.online.attribution))
                layers.put(JSONObject().put("id", "on$i").put("type", "raster").put("source", "on$i"))
            }
        } else {
            // мир
            sources.put("world", JSONObject().put("type", "raster").put("url", "mbtiles://" + input.worldRelief).put("tileSize", 256))
            layers.put(JSONObject().put("id", "world").put("type", "raster").put("source", "world")
                .put("paint", JSONObject().put("raster-opacity", if (o.relief) 1.0 else 0.25).put("raster-fade-duration", 0)))
            if (shapeGeoms.isNotEmpty()) {
                sources.put("shapes", JSONObject().put("type", "geojson").put("data", JSONObject().put("type", "Feature").put("properties", JSONObject()).put("geometry", shapesGeo(shapeGeoms))))
                layers.put(JSONObject("""{"id":"land","type":"fill","source":"shapes","paint":{"fill-color":"#f3f1ec","fill-antialias":false,
                    "fill-opacity":["interpolate",["linear"],["zoom"],4,0,6.5,1]}}"""))
            }
        }

        val countries = input.files.filterValues { "map" in it }.keys.toList()
        val base = JSONArray(input.layersJson)
        val nonSymbol = ArrayList<JSONObject>(); val symbol = ArrayList<JSONObject>()
        for (i in 0 until base.length()) base.getJSONObject(i).let { if (it.getString("type") == "symbol") symbol += it else nonSymbol += it }

        fun copyFor(l: JSONObject, c: String): JSONObject {
            val n = JSONObject(l.toString())
            n.put("id", l.getString("id") + "@" + c); n.put("source", "omt_$c")
            n.optJSONObject("layout")?.let { lay -> if (lay.optString("text-field") == "__NAME__") lay.put("text-field", name) }
            // подписи стран/городов на мелких зумах даёт слой мира — чтобы не двоились
            if (hasWorld && n.optString("source-layer") == "place" && n.optDouble("minzoom", 0.0) < HATCH_MIN_ZOOM) n.put("minzoom", HATCH_MIN_ZOOM)
            return n
        }

        if (o.online == null) {
            countries.forEach { c -> sources.put("omt_$c", JSONObject().put("type", "vector").put("url", "mbtiles://" + input.files[c]!!["map"])) }
            val hillAfter = "landuse_school"   // тени — под водой и дорогами
            nonSymbol.forEach { l ->
                countries.forEach { c -> layers.put(copyFor(l, c)) }
                if (l.getString("id") == hillAfter) terrain(o, input.files, sources, layers)
            }
        } else terrain(o, input.files, sources, layers)

        // пробки TomTom — поверх дорог, под подписями
        o.traffic?.let { k ->
            sources.put("tt-flow", JSONObject().put("type", "raster").put("tileSize", 256).put("minzoom", 6).put("maxzoom", 18).put("attribution", "© TomTom")
                .put("tiles", JSONArray().put(com.pank.vsevodnom.data.TomTom.flowTiles(k))))
            sources.put("tt-inc", JSONObject().put("type", "raster").put("tileSize", 256).put("minzoom", 8).put("maxzoom", 18)
                .put("tiles", JSONArray().put(com.pank.vsevodnom.data.TomTom.incidentTiles(k))))
            layers.put(JSONObject().put("id", "tt-flow").put("type", "raster").put("source", "tt-flow").put("minzoom", 6).put("paint", JSONObject().put("raster-opacity", 0.85)))
            layers.put(JSONObject().put("id", "tt-inc").put("type", "raster").put("source", "tt-inc").put("minzoom", 9))
        }
        // мир: границы
        val wl = input.worldLabels
        val within = if (shapeGeoms.isNotEmpty()) JSONArray().put("!").put(JSONArray().put("within").put(shapesGeo(shapeGeoms))) else null
        if (wl != null && o.worldLabels) {
            sources.put("wl", JSONObject().put("type", "vector").put("url", "mbtiles://" + wl))
            fun twice(base: JSONObject, extraFilter: JSONArray?) {
                layers.put(JSONObject(base.toString()).apply { if (within != null) put("maxzoom", HATCH_MIN_ZOOM) })
                if (within != null) layers.put(JSONObject(base.toString()).apply {
                    put("id", getString("id") + "-o"); put("minzoom", HATCH_MIN_ZOOM)
                    put("filter", if (extraFilter != null) JSONArray().put("all").put(extraFilter).put(within) else within)
                })
            }
            twice(JSONObject("""{"id":"wl-b4","type":"line","source":"wl","source-layer":"boundary","minzoom":3.5,"filter":["==",["get","admin_level"],4],
                "paint":{"line-color":"#8d8a95","line-width":0.6,"line-dasharray":[3,2],"line-opacity":0.6}}"""), JSONArray("""["==",["get","admin_level"],4]"""))
            twice(JSONObject("""{"id":"wl-b2","type":"line","source":"wl","source-layer":"boundary","filter":["==",["get","admin_level"],2],
                "paint":{"line-color":"#7d6f86","line-width":["interpolate",["linear"],["zoom"],1,0.6,6,1.6],"line-opacity":0.85}}"""), JSONArray("""["==",["get","admin_level"],2]"""))
        }

        if (o.online == null) {
            sources.put("hatch", JSONObject().put("type", "geojson").put("data", hatchGeo(shapeGeoms)))
            layers.put(JSONObject("""{"id":"hatch","type":"fill","source":"hatch","minzoom":$HATCH_MIN_ZOOM,
                "paint":{"fill-pattern":"hatch","fill-opacity":["interpolate",["linear"],["zoom"],$HATCH_MIN_ZOOM,0,6.5,1]}}"""))
            if (shapeGeoms.isNotEmpty()) layers.put(JSONObject("""{"id":"shape-edge","type":"line","source":"shapes","minzoom":$HATCH_MIN_ZOOM,
                "paint":{"line-color":"#9a96a3","line-width":1,"line-opacity":0.5}}"""))
            symbol.forEach { l -> countries.forEach { c -> layers.put(copyFor(l, c)) } }
        }

        if (wl != null && o.worldLabels) {
            val wn = worldNameExpr(o.lang)
            val city = JSONObject("""{"id":"wl-city","type":"symbol","source":"wl","source-layer":"city",
                "layout":{"text-font":["Noto Sans Regular"],"text-size":["interpolate",["linear"],["zoom"],2,["case",["==",["get","capital"],1],12,10],7,["case",["==",["get","capital"],1],16,13]],
                  "text-anchor":"top","text-offset":[0,0.4],"icon-image":["case",["==",["get","capital"],1],"star_11","dot_9"],"icon-size":0.8,"text-max-width":8,
                  "symbol-sort-key":["get","rank"],"text-optional":true},
                "paint":{"text-color":"#333038","text-halo-color":"rgba(255,255,255,0.85)","text-halo-width":1.3}}""")
            city.getJSONObject("layout").put("text-field", wn)
            val country = JSONObject("""{"id":"wl-country","type":"symbol","source":"wl","source-layer":"country","maxzoom":7,
                "layout":{"text-font":["Noto Sans Bold"],"text-size":["interpolate",["linear"],["zoom"],1,10,5,16],"text-max-width":7,"text-transform":"uppercase","text-letter-spacing":0.08,
                  "symbol-sort-key":["get","rank"]},
                "paint":{"text-color":"#4d4458","text-halo-color":"rgba(255,255,255,0.8)","text-halo-width":1.4}}""")
            country.getJSONObject("layout").put("text-field", wn)
            if (within != null) {
                layers.put(JSONObject(city.toString()).put("maxzoom", HATCH_MIN_ZOOM))
                layers.put(JSONObject(city.toString()).put("id", "wl-city-o").put("minzoom", HATCH_MIN_ZOOM).put("filter", within))
                layers.put(JSONObject(country.toString()).put("maxzoom", HATCH_MIN_ZOOM))
                layers.put(JSONObject(country.toString()).put("id", "wl-country-o").put("minzoom", HATCH_MIN_ZOOM).put("filter", within))
            } else { layers.put(city); layers.put(country) }
        }

        return JSONObject().put("version", 8).put("name", "vse")
            .put("glyphs", "asset://map/glyphs/{fontstack}/{range}.pbf")
            .put("sprite", "asset://map/sprite/osm-liberty")
            .put("sources", sources).put("layers", layers).toString()
    }

    /** Тени рельефа и линии высот скачанных стран. */
    private fun terrain(o: Opts, files: Map<String, Map<String, String>>, sources: JSONObject, layers: JSONArray) {
        files.forEach { (c, v) ->
            if (o.hillshade && "dem" in v) {
                sources.put("dem_$c", JSONObject().put("type", "raster-dem").put("url", "mbtiles://" + v["dem"]).put("encoding", "terrarium").put("tileSize", 256))
                layers.put(JSONObject("""{"id":"hill@$c","type":"hillshade","source":"dem_$c","minzoom":4,
                    "paint":{"hillshade-exaggeration":["interpolate",["linear"],["zoom"],5,0.45,10,0.32,14,0.22],"hillshade-shadow-color":"#5b5346",
                    "hillshade-highlight-color":"#ffffff","hillshade-accent-color":"#6f6556","hillshade-illumination-direction":315}}"""))
            }
        }
        files.forEach { (c, v) ->
            if (o.contours && "contours" in v) {
                sources.put("ct_$c", JSONObject().put("type", "vector").put("url", "mbtiles://" + v["contours"]))
                layers.put(JSONObject("""{"id":"ct@$c","type":"line","source":"ct_$c","source-layer":"contour","minzoom":11,
                    "paint":{"line-color":"#a4865f","line-opacity":["case",["==",["get","major"],1],0.55,0.3],
                    "line-width":["case",["==",["get","major"],1],0.9,0.5]}}"""))
                layers.put(JSONObject("""{"id":"ctl@$c","type":"symbol","source":"ct_$c","source-layer":"contour","minzoom":12,"filter":["==",["get","major"],1],
                    "layout":{"symbol-placement":"line","text-field":["concat",["to-string",["get","ele"]]," м"],"text-font":["Noto Sans Regular"],"text-size":10},
                    "paint":{"text-color":"#8a6c46","text-halo-color":"rgba(255,255,255,0.8)","text-halo-width":1}}"""))
            }
        }
    }

    /** Узор штриховки для нескачанных территорий. */
    fun hatchBitmap(density: Float): Bitmap {
        val s = (16 * density).toInt().coerceAtLeast(8)
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0x9EEAE8E3.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0A3A0AA.toInt(); strokeWidth = 1.3f * density }
        c.drawLine(0f, s.toFloat(), s.toFloat(), 0f, p)
        c.drawLine(-s / 2f, s / 2f, s / 2f, -s / 2f, p)
        c.drawLine(s / 2f, s * 1.5f, s * 1.5f, s / 2f, p)
        return b
    }
}
