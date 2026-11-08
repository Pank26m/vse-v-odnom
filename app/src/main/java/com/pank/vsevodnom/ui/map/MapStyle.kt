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
        val buildings3d: Boolean = false,
        val hiddenPoi: Set<String> = emptySet(),   // скрытые группы значков (POI_GROUPS)
        val houseNumbers: Boolean = true,
        val stopNames: Boolean = false,
        val worldOnline: Boolean = true,      // за границей скачанных стран — онлайн-карта (OpenFreeMap)
        val metro: Boolean = true,
    )

    /** Группа значков: название, цвет кружка, иконка по умолчанию (Tabler), классы OpenMapTiles. */
    class PoiGroup(val title: String, val color: Int, val icon: String, val classes: List<String>)

    /** Фильтр «Что показывать на карте» (как в Guru). Особые ключи: housenumbers, stopnames, other. */
    val POI_GROUPS = linkedMapOf(
        "bank" to PoiGroup("Банки, обменники, банкоматы", 0xFF43A047.toInt(), "building-bank", listOf("bank", "atm", "bureau_de_change", "money_transfer")),
        "health" to PoiGroup("Больницы, поликлиники, аптеки", 0xFFE53935.toInt(), "medical-cross", listOf("hospital", "pharmacy", "dentist", "doctors", "clinic", "veterinary", "chemist")),
        "edu" to PoiGroup("Вузы, колледжи, школы", 0xFF43A047.toInt(), "school", listOf("school", "college", "university", "kindergarten", "library")),
        "ski" to PoiGroup("Горнолыжные трассы и подъёмники", 0xFF6D4C41.toInt(), "mountain", listOf("aerialway", "skiing")),
        "rail" to PoiGroup("ЖД-транспорт и метро", 0xFF78909C.toInt(), "train", listOf("railway", "subway", "station")),
        "arts" to PoiGroup("Кино, театры, арт-центры", 0xFFFB8C00.toInt(), "movie", listOf("cinema", "theatre", "art_gallery", "music", "arts_centre", "nightclub")),
        "museum" to PoiGroup("Музеи, памятники, церкви", 0xFF26A69A.toInt(), "building-castle", listOf("museum", "monument", "castle", "attraction", "place_of_worship", "memorial", "historic", "ruins")),
        "housenumbers" to PoiGroup("Номера и названия зданий", 0xFF6D4C41.toInt(), "home", emptyList()),
        "transit" to PoiGroup("Общественный транспорт", 0xFF78909C.toInt(), "bus", listOf("bus", "tram", "ferry_terminal", "bus_stop", "bus_station", "harbor")),
        "entrances" to PoiGroup("Подъезды и входы", 0xFF8D6E63.toInt(), "home-2", emptyList()),
        "stopnames" to PoiGroup("Названия остановок", 0xFF78909C.toInt(), "tag", emptyList()),
        "post" to PoiGroup("Отделения почты, почтоматы", 0xFF43A047.toInt(), "package", listOf("post", "parcel_locker", "post_office")),
        "hotel" to PoiGroup("Отели, хостелы, кемпинги", 0xFF7E57C2.toInt(), "bed", listOf("lodging", "campsite", "hotel", "hostel", "camp_site", "guest_house")),
        "bars" to PoiGroup("Пабы и бары", 0xFFFB8C00.toInt(), "glass-cocktail", listOf("bar", "pub", "beer", "biergarten")),
        "police" to PoiGroup("Полиция, посольства", 0xFF43A047.toInt(), "shield", listOf("police", "embassy", "fire_station", "town_hall", "courthouse", "prison")),
        "shop" to PoiGroup("Прочие магазины", 0xFF42A5F5.toInt(), "shopping-bag", listOf("shop", "clothing_store", "jewelry", "florist", "furniture", "hardware", "mobile_phone", "shoe", "gift", "books", "pet", "optician", "alcohol_shop", "electronics", "toys", "stationery", "doityourself", "garden_centre", "laundry", "dry_cleaning")),
        "food" to PoiGroup("Рестораны, кофейни, бистро", 0xFFFB8C00.toInt(), "tools-kitchen-2", listOf("restaurant", "fast_food", "cafe", "ice_cream", "bakery", "food_court", "confectionery")),
        "beauty" to PoiGroup("Салоны красоты, парикмахерские", 0xFFEC407A.toInt(), "brush", listOf("hairdresser", "beauty", "cosmetics", "massage")),
        "sport" to PoiGroup("Спорткомплексы и бассейны", 0xFFFF7043.toInt(), "barbell", listOf("stadium", "swimming", "sports_centre", "fitness_centre", "pitch", "golf", "swimming_pool")),
        "mall" to PoiGroup("ТЦ, супермаркеты, магазины", 0xFF42A5F5.toInt(), "shopping-cart", listOf("grocery", "supermarket", "convenience", "mall", "marketplace", "department_store")),
        "car" to PoiGroup("АЗС и автосервисы", 0xFF5C6BC0.toInt(), "gas-station", listOf("fuel", "car", "car_repair", "car_wash", "charging_station", "bicycle", "bicycle_rental", "motorcycle")),
        "park" to PoiGroup("Парки, площадки, смотровые", 0xFF66BB6A.toInt(), "trees", listOf("park", "garden", "playground", "zoo", "viewpoint", "picnic_site", "dog_park")),
        "parking" to PoiGroup("Парковки", 0xFF5C6BC0.toInt(), "parking", listOf("parking", "bicycle_parking", "motorcycle_parking")),
        "other" to PoiGroup("Остальное (туалеты, урны, доступность…)", 0xFF90A4AE.toInt(), "map-pin", emptyList()),
    )
    val DEFAULT_HIDDEN = setOf("parking", "other", "stopnames")

    /** Иконка по классу (Tabler), если у класса своя — иначе иконка группы. */
    val CLASS_ICON = mapOf(
        "pharmacy" to "pill", "dentist" to "dental", "doctors" to "stethoscope", "veterinary" to "paw", "atm" to "cash", "bureau_de_change" to "currency-hryvnia",
        "cafe" to "coffee", "fast_food" to "burger", "ice_cream" to "ice-cream", "bakery" to "bread", "pub" to "beer", "beer" to "beer", "bar" to "glass-cocktail",
        "library" to "book", "railway" to "train", "subway" to "train", "bus" to "bus", "tram" to "train", "ferry_terminal" to "ship", "harbor" to "anchor",
        "cinema" to "movie", "theatre" to "ticket", "music" to "music", "art_gallery" to "palette", "place_of_worship" to "building-church", "monument" to "flag",
        "attraction" to "star", "lodging" to "bed", "campsite" to "tent", "fire_station" to "flame", "town_hall" to "building", "hairdresser" to "brush",
        "stadium" to "building-stadium", "swimming" to "swimming", "golf" to "flag", "car_repair" to "tools", "car" to "car", "car_wash" to "droplet", "charging_station" to "plug",
        "bicycle" to "bike", "bicycle_rental" to "bike", "parking" to "parking", "clothing_store" to "shirt", "jewelry" to "diamond", "florist" to "flower",
        "mobile_phone" to "phone", "shoe" to "shoe", "gift" to "gift", "books" to "book", "pet" to "paw", "optician" to "eyeglass", "alcohol_shop" to "bottle",
        "laundry" to "wash-machine", "playground" to "mood-kid", "zoo" to "paw", "viewpoint" to "telescope", "dog_park" to "dog", "toilets" to "toilet-paper",
        "waste_basket" to "trash", "drinking_water" to "droplet", "information" to "help-circle", "post" to "package", "grocery" to "shopping-cart",
        "fuel" to "gas-station", "school" to "school", "college" to "school", "university" to "school", "kindergarten" to "mood-kid", "hospital" to "first-aid-kit",
    )
    fun groupOf(cls: String): PoiGroup = POI_GROUPS.values.firstOrNull { cls in it.classes } ?: POI_GROUPS["other"]!!

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
            buildings3d = p.getBoolean("buildings3d", false),
            hiddenPoi = p.getStringSet("hidden_poi", DEFAULT_HIDDEN) ?: emptySet(),
            houseNumbers = "housenumbers" !in (p.getStringSet("hidden_poi", DEFAULT_HIDDEN) ?: emptySet()),
            stopNames = "stopnames" !in (p.getStringSet("hidden_poi", DEFAULT_HIDDEN) ?: emptySet()),
            worldOnline = p.getBoolean("world_online", true),
            metro = p.getBoolean("metro", true),
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
    class Input(val layersJson: String, val worldRelief: String, val worldLabels: String?, val files: Map<String, Map<String, String>>, val shapes: List<JSONObject>,
                val metro: Map<String, String> = emptyMap(), val entrances: Map<String, String> = emptyMap())

    fun build(ctx: Context, o: Opts): String {
        val inst = OfflineMaps.installed(ctx)
        val files = inst.mapValues { (id, v) -> v.second.associateWith { OfflineMaps.file(ctx, id, it).absolutePath } }
        val input = Input(layerCache.getOrPut(o.variant) { ctx.assets.open("map/${o.variant.key}.json").bufferedReader().use { it.readText() } },
            OfflineMaps.worldRelief(ctx).absolutePath, OfflineMaps.worldLabels(ctx)?.absolutePath, files, OfflineMaps.shapes(ctx).map { it.second },
            inst.keys.mapNotNull { id -> OfflineMaps.metroFile(ctx, id).takeIf { it.exists() }?.let { id to it.readText() } }.toMap(),
            inst.keys.mapNotNull { id -> OfflineMaps.entrancesFile(ctx, id).takeIf { it.exists() && it.length() > 0 }?.let { id to it.absolutePath } }.toMap())
        return compose(input, o)
    }

    fun compose(input: Input, o: Opts): String {
        val sources = JSONObject()
        val layers = JSONArray()
        val shapeGeoms = input.shapes
        val name = nameExpr(o.lang)
        val hasWorld = o.worldLabels && input.worldLabels != null

        layers.put(JSONObject("""{"id":"bg","type":"background","paint":{"background-color":"#e6e4de"}}"""))
        var worldOnlineAt = -1

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
            worldOnlineAt = layers.length()
            if (shapeGeoms.isNotEmpty()) {
                sources.put("shapes", JSONObject().put("type", "geojson").put("data", JSONObject().put("type", "Feature").put("properties", JSONObject()).put("geometry", shapesGeo(shapeGeoms))))
                layers.put(JSONObject("""{"id":"land","type":"fill","source":"shapes","paint":{"fill-color":"#f3f1ec","fill-antialias":false,
                    "fill-opacity":["interpolate",["linear"],["zoom"],4,0,6.5,1]}}"""))
            }
        }

        val countries = input.files.filterValues { "map" in it }.keys.toList()
        val onlineWorld = o.online == null && o.worldOnline
        val base = JSONArray(input.layersJson)
        val nonSymbol = ArrayList<JSONObject>(); val symbol = ArrayList<JSONObject>()
        for (i in 0 until base.length()) base.getJSONObject(i).let {
            if (it.getString("type") == "fill-extrusion") {
                // 3D-дома — тяжёлые для GPU; включаются в «Слоях», и только вблизи
                if (!o.buildings3d) return@let
                it.put("minzoom", 15.5)
            }
            // плоские дома в Liberty только до z14 (дальше их сменяли 3D) — без 3D рисуем их на всех зумах, с 3D — до 15.5
            if (it.getString("id") == "building") { it.remove("maxzoom"); if (o.buildings3d) it.put("maxzoom", 15.5) }
            if (it.getString("type") == "symbol") symbol += it else nonSymbol += it
        }

        val withinOut = if (shapeGeoms.isNotEmpty()) JSONArray().put("!").put(JSONArray().put("within").put(shapesGeo(shapeGeoms))) else null
        fun copyFor(l: JSONObject, c: String, src: String = "omt_$c", cw: JSONArray? = null): JSONObject {
            val n = JSONObject(l.toString())
            n.put("id", l.getString("id") + "@" + c); n.put("source", src)
            n.optJSONObject("layout")?.let { lay -> if (lay.optString("text-field") == "__NAME__") lay.put("text-field", name) }
            // подписи стран/городов на мелких зумах даёт слой мира — чтобы не двоились
            if (hasWorld && n.optString("source-layer") == "place" && n.optDouble("minzoom", 0.0) < HATCH_MIN_ZOOM) n.put("minzoom", HATCH_MIN_ZOOM)
            // на мелких зумах в тайлах страны лежит океан Natural Earth для всего мира — давал синие прямоугольники поверх рельефа
            if (l.getString("type") != "symbol" && n.optDouble("minzoom", 0.0) < 6.0) n.put("minzoom", 6.0)
            // фильтр значков
            if (n.optString("source-layer") == "poi") {
                if (o.hiddenPoi.isNotEmpty()) {
                    val f = n.optJSONArray("filter") ?: JSONArray().put("all")
                    val all = if (f.optString(0) == "all") f else JSONArray().put("all").put(f)
                    val hidden = POI_GROUPS.filterKeys { it in o.hiddenPoi }.values.flatMap { it.classes }
                    val shown = POI_GROUPS.filterKeys { it !in o.hiddenPoi }.values.flatMap { it.classes }
                    // выражения (не старый синтаксис): ["match", класс, [список], да, нет]
                    fun match(list: List<String>, yes: Boolean) = if (list.isEmpty()) JSONArray().put("literal").put(!yes).let { JSONArray().put("==").put(1).put(if (yes) 0 else 1) }
                        else JSONArray().put("match").put(JSONArray("[\"get\",\"class\"]")).put(JSONArray(list.distinct())).put(yes).put(!yes)
                    all.put(if ("other" in o.hiddenPoi) match(shown, true) else match(hidden, false))
                    n.put("filter", all)
                }
                // единый стиль значков: цветной кружок группы с белой иконкой (картинки рисует экран карты по запросу «p:класс»)
                n.optJSONObject("layout")?.let { lay ->
                    lay.put("icon-image", JSONArray("""["concat","p:",["get","class"]]"""))
                    lay.put("icon-size", 1.0); lay.put("text-size", 11); lay.put("text-offset", JSONArray("[0,1.1]"))
                    if (l.getString("id") == "poi_transit" && !o.stopNames) lay.put("text-field", "")
                }
                n.optJSONObject("paint")?.put("text-color", "#5b5866")
            }
            if (cw != null && l.getString("type") == "symbol") {
                val f = n.optJSONArray("filter")
                n.put("filter", if (f == null) cw else JSONArray().put("all").put(f).put(cw))
            }
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
            if (o.houseNumbers) countries.forEach { c ->
                layers.put(JSONObject("""{"id":"housenum@$c","type":"symbol","source":"omt_$c","source-layer":"housenumber","minzoom":16.5,
                    "layout":{"text-field":["get","housenumber"],"text-font":["Noto Sans Regular"],"text-size":11,"text-padding":2},
                    "paint":{"text-color":"#6f6a63","text-halo-color":"rgba(255,255,255,0.8)","text-halo-width":1}}"""))
            }
            symbol.forEach { l -> countries.forEach { c -> layers.put(copyFor(l, c)) } }
            // подъезды: номер в рамке у входа, только вблизи
            if ("entrances" !in o.hiddenPoi) input.entrances.forEach { (c, path) ->
                sources.put("entr_$c", JSONObject().put("type", "vector").put("url", "mbtiles://$path"))
                layers.put(JSONObject("""{"id":"entr@$c","type":"symbol","source":"entr_$c","source-layer":"entrance","minzoom":17,
                    "layout":{"text-field":["coalesce",["get","ref"],"⇥"],"text-font":["Noto Sans Bold"],"text-size":10,"text-padding":1,"text-allow-overlap":false},
                    "paint":{"text-color":"#ffffff","text-halo-color":"#8d6e63","text-halo-width":3}}"""))
            }
            // метро: линии цветом линий, станции кружками
            if (o.metro) input.metro.forEach { (c, gj) ->
                runCatching { sources.put("metro_$c", JSONObject().put("type", "geojson").put("data", JSONObject(gj))) }.onFailure { return@forEach }
                layers.put(JSONObject("""{"id":"metro-l@$c","type":"line","source":"metro_$c","minzoom":9,"filter":["==",["get","kind"],"line"],
                    "layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":["to-color",["get","colour"],"#888888"],"line-width":["interpolate",["linear"],["zoom"],9,1.6,13,3.5,17,6],"line-opacity":0.9}}"""))
                layers.put(JSONObject("""{"id":"metro-s@$c","type":"circle","source":"metro_$c","minzoom":10.5,"filter":["==",["get","kind"],"station"],
                    "paint":{"circle-radius":["interpolate",["linear"],["zoom"],11,3,16,6],"circle-color":"#ffffff","circle-stroke-color":["to-color",["get","colour"],"#555555"],"circle-stroke-width":2.2}}"""))
                layers.put(JSONObject("""{"id":"metro-n@$c","type":"symbol","source":"metro_$c","minzoom":12.5,"filter":["==",["get","kind"],"station"],
                    "layout":{"text-field":["get","name"],"text-font":["Noto Sans Bold"],"text-size":11,"text-anchor":"left","text-offset":[0.8,0],"text-optional":true},
                    "paint":{"text-color":["to-color",["get","colour"],"#444444"],"text-halo-color":"#ffffff","text-halo-width":1.4}}"""))
            }
            // онлайн-мир: подписи и значки только за границей скачанного
            if (onlineWorld) symbol.forEach { l -> layers.put(copyFor(l, "w", "ofm", withinOut).also { if (it.optDouble("minzoom", 0.0) < HATCH_MIN_ZOOM) it.put("minzoom", HATCH_MIN_ZOOM) }) }
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

        val finalLayers = if (onlineWorld && worldOnlineAt >= 0) {
            sources.put("ofm", JSONObject().put("type", "vector").put("url", "https://tiles.openfreemap.org/planet"))
            val out = JSONArray()
            for (i in 0 until worldOnlineAt) out.put(layers.get(i))
            // вблизи рельеф мира уходит под светлый фон — дальше рисует онлайн-карта (без интернета — только то, что в кэше)
            out.put(JSONObject("""{"id":"bg-world","type":"background","minzoom":5.5,"paint":{"background-color":"#f3f1ec","background-opacity":["interpolate",["linear"],["zoom"],5.5,0,6.5,1]}}"""))
            nonSymbol.forEach { l -> out.put(copyFor(l, "w", "ofm").also { if (it.optDouble("minzoom", 0.0) < HATCH_MIN_ZOOM) it.put("minzoom", HATCH_MIN_ZOOM) }) }
            for (i in worldOnlineAt until layers.length()) out.put(layers.get(i))
            out
        } else layers
        return JSONObject().put("version", 8).put("name", "vse")
            .put("glyphs", "asset://map/glyphs/{fontstack}/{range}.pbf")
            .put("sprite", "asset://map/sprite/osm-liberty")
            .put("sources", sources).put("layers", finalLayers).toString()
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
