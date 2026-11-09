package com.pank.vsevodnom

import com.pank.vsevodnom.data.OnlineMaps
import com.pank.vsevodnom.ui.map.MapStyle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Стиль офлайн-карты: собирается без ошибок; файлы для проверки валидатором MapLibre — в build/map-style/. */
class MapStyleTest {
    // для стенда можно подставить настоящую границу: UA_SHAPE=путь/к/ukraine.json
    private val shape = System.getenv("UA_SHAPE")?.let { JSONObject(File(it).readText()).getJSONObject("geometry") } ?: JSONObject("""{"type":"MultiPolygon","coordinates":[[[[22.1,48.4],[40.2,48.4],[40.2,52.4],[22.1,52.4],[22.1,48.4]]],[[[32.5,44.4],[36.6,44.4],[36.6,46.2],[32.5,46.2],[32.5,44.4]]]]}""")

    private fun input(v: String, countries: Boolean) = MapStyle.Input(
        File("src/main/assets/map/$v.json").readText(), "/data/world-z4.mbtiles", if (countries) "/data/world-labels.mbtiles" else null,
        if (countries) mapOf("ukraine" to mapOf("map" to "/m/ukraine.mbtiles", "dem" to "/m/ukraine-dem.mbtiles", "contours" to "/m/ukraine-contours.mbtiles")) else emptyMap(),
        if (countries) listOf(shape) else emptyList(),
        metro = System.getenv("UA_METRO")?.takeIf { countries }?.let { mapOf("ukraine" to File(it).readText()) } ?: emptyMap(),
        lines = if (countries) mapOf("ukraine" to "/m/ukraine-lines.mbtiles") else emptyMap())

    /** Как MapLibre решает, выражение ли фильтр (isExpressionFilter из style-spec). Старый синтаксис внутри «all» ломает весь фильтр. */
    private fun isExpr(f: Any?): Boolean {
        if (f == true || f == false) return true
        val a = f as? org.json.JSONArray ?: return false
        if (a.length() == 0) return false
        return when (a.opt(0)) {
            "has" -> a.length() >= 2 && a.opt(1) != "\$id" && a.opt(1) != "\$type"
            "in" -> a.length() >= 3 && (a.opt(1) !is String || a.opt(2) is org.json.JSONArray)
            "!in", "!has", "none" -> false
            "==", "!=", ">", ">=", "<", "<=" -> a.length() != 3 || a.opt(1) is org.json.JSONArray || a.opt(2) is org.json.JSONArray
            "any", "all" -> (1 until a.length()).all { isExpr(a.opt(it)) || a.opt(it) is Boolean }
            else -> true
        }
    }

    @Test fun styles() {
        val out = File("build/map-style").apply { mkdirs() }
        for (v in MapStyle.Variant.entries) for (c in listOf(false, true)) for (lang in listOf("local", "ru")) {
            val s = JSONObject(MapStyle.compose(input(v.key, c), MapStyle.Opts(variant = v, lang = lang, hiddenPoi = if (lang == "ru") MapStyle.DEFAULT_HIDDEN else setOf("food", "transport"))))
            val ids = (0 until s.getJSONArray("layers").length()).map { s.getJSONArray("layers").getJSONObject(it).getString("id") }
            assertEquals("дубли id слоёв", ids.size, ids.toSet().size)
            assertTrue(ids.contains("world"))
            if (c) { assertTrue(ids.any { it.endsWith("@ukraine") }); assertTrue(ids.contains("hill@ukraine")) }
            File(out, "${v.key}-${if (c) "ua" else "world"}-$lang.json").writeText(s.toString(1))
        }
        val online = MapStyle.compose(input("car", true), MapStyle.Opts(online = OnlineMaps.BUILT_IN[1]))
        File(out, "online.json").writeText(JSONObject(online).toString(1))
    }

    @Test fun viewInsideBorder() {
        val r = MapStyle.rings(listOf(shape))
        // центр Киева — внутри, онлайн-мир не нужен
        assertTrue(MapStyle.viewInside(r, 30.40, 50.40, 30.65, 50.52))
        // вид через западную границу (Ужгород — Словакия) и над морем — снаружи
        assertTrue(!MapStyle.viewInside(r, 21.0, 48.3, 23.0, 49.0))
        assertTrue(!MapStyle.viewInside(r, 30.0, 42.0, 31.0, 43.0))
        assertTrue(!MapStyle.viewInside(emptyList(), 30.40, 50.40, 30.65, 50.52))
        assertTrue(com.pank.vsevodnom.ui.map.nearNorth(354.0) && com.pank.vsevodnom.ui.map.nearNorth(4.0) && !com.pank.vsevodnom.ui.map.nearNorth(25.0) && !com.pank.vsevodnom.ui.map.nearNorth(0.0))
    }

    @Test fun labelsAndMetro() {
        val hidden = MapStyle.DEFAULT_HIDDEN + setOf("l_street", "l_city", "l_poitext")
        val st = JSONObject(MapStyle.compose(input("bike", true), MapStyle.Opts(hiddenPoi = hidden)))
        val ids = (0 until st.getJSONArray("layers").length()).map { st.getJSONArray("layers").getJSONObject(it).getString("id") }
        assertTrue(ids.none { it.startsWith("road_label") || it.startsWith("place_city") || it.startsWith("wl-city") })
        assertTrue(ids.any { it.startsWith("place_town") })
        assertTrue(ids.none { it == "land" })                 // маски страны больше нет
        assertTrue(ids.none { it == "wl-b2-o" || it == "wl-b4-o" })   // грубые границы — только издалека
        File("build/map-style").also { it.mkdirs() }.resolve("labels-hidden.json").writeText(st.toString(1))
        val gj = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[30.469092,50.376487]},"properties":{"kind":"station","name":"Іподром"}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[30.469167,50.376714]},"properties":{"kind":"station","name":"Іподром"}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[35.0,48.47]},"properties":{"kind":"station","name":"Вокзальна"}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[30.48,50.44]},"properties":{"kind":"station","name":"Вокзальна"}},
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[30,50],[30.1,50.1]]},"properties":{"kind":"line"}}]}"""
        val out = JSONObject(MapStyle.dedupeStations(gj)).getJSONArray("features")
        assertEquals(4, out.length())   // линия + Іподром (одна) + две разные «Вокзальні»
    }

    /** 0.23: скрыть метро, подписи или подъезды — пропадали ВСЕ значки (в фильтр попадало ["==",1,1] — старый синтаксис). */
    @Test fun poiFilterStaysExpression() {
        val cases = listOf(setOf("metro"), setOf("l_street", "l_water"), setOf("entrances"), setOf("l_poitext"), emptySet(), MapStyle.DEFAULT_HIDDEN, setOf("other", "food"), setOf("metro", "other"))
        for (h in cases) for (v in MapStyle.Variant.entries) {
            val st = JSONObject(MapStyle.compose(input(v.key, true), MapStyle.Opts(variant = v, hiddenPoi = h, metro = "metro" !in h)))
            val ls = st.getJSONArray("layers")
            for (i in 0 until ls.length()) {
                val l = ls.getJSONObject(i)
                if (l.has("filter")) assertTrue("${l.getString("id")} при скрытых $h: фильтр не выражение ${l.get("filter")}", isExpr(l.get("filter")))
            }
            val poi = (0 until ls.length()).map { ls.getJSONObject(it) }.filter { it.optString("source-layer") == "poi" }
            assertTrue(poi.isNotEmpty())
            poi.forEach { assertTrue(it.getJSONObject("layout").has("symbol-sort-key")) }
        }
        // морские границы не рисуются, заборы и тропы есть
        val st = JSONObject(MapStyle.compose(input("foot", true), MapStyle.Opts(variant = MapStyle.Variant.FOOT)))
        val ls = (0 until st.getJSONArray("layers").length()).map { st.getJSONArray("layers").getJSONObject(it) }
        ls.filter { it.optString("source-layer") == "boundary" && it.optString("source") != "wl" }.forEach { assertTrue(it.get("filter").toString().contains("maritime")) }
        assertTrue(ls.any { it.getString("id") == "fence@ukraine" } && ls.any { it.getString("id") == "route@ukraine" } && ls.any { it.getString("id") == "route-n@ukraine" })
        val car = JSONObject(MapStyle.compose(input("car", true), MapStyle.Opts()))
        val carIds = (0 until car.getJSONArray("layers").length()).map { car.getJSONArray("layers").getJSONObject(it).getString("id") }
        assertTrue(carIds.contains("fence@ukraine") && !carIds.contains("route@ukraine"))
        File("build/map-style").also { it.mkdirs() }.resolve("foot-lines.json").writeText(st.toString(1))
    }

    @Test fun openingHours() {
        val oh = com.pank.vsevodnom.data.OpeningHours
        val w = oh.parse("Mo-Fr 08:00-21:00; Sa-Su 09:00-19:00")!!
        assertEquals(listOf(480..1260), w[0]); assertEquals(listOf(540..1140), w[6])
        assertEquals("Открыто · до 21:00", oh.status(w, 2, 12 * 60).text)
        assertEquals("Скоро закроется · в 21:00", oh.status(w, 2, 20 * 60 + 30).text)
        assertEquals("Скоро откроется · в 08:00", oh.status(w, 2, 7 * 60 + 15).text)
        assertTrue(oh.status(w, 4, 22 * 60).text.startsWith("Закрыто · откроется завтра в 09:00"))
        val off = oh.parse("Mo-Sa 10:00-20:00; Su off")!!
        assertTrue(off[6].isEmpty()); assertEquals("Выходной", oh.dayText(off[6]))
        assertEquals("Круглосуточно", oh.dayText(oh.parse("24/7")!![3]))
        val night = oh.parse("Mo-Su 18:00-02:00")!!
        assertTrue(oh.status(night, 3, 60).open)      // после полуночи — ещё открыто (хвост вчерашнего)
        val lunch = oh.parse("Mo-Fr 09:00-13:00,14:00-18:00")!!
        assertEquals(2, lunch[1].size); assertTrue(!oh.status(lunch, 1, 13 * 60 + 30).open)
        assertEquals(null, oh.parse("by appointment"))
    }

    @Test fun demMath() {
        val d = com.pank.vsevodnom.data.Dem
        // Terrarium: 0 м = (128, 0, 0)
        assertEquals(0.0, d.terrarium((128 shl 16)), 0.01)
        assertEquals(256.0 + 10, d.terrarium((129 shl 16) or (10 shl 8)), 0.01)
        // Киев на z11: тайл 1197/690
        val p = d.pixel(50.45, 30.52, 11)
        assertEquals(1197.0, p[0], 0.0); assertEquals(690.0, p[1], 0.0)
        assertTrue(p[2] in 0.0..256.0 && p[3] in 0.0..256.0)
    }

    /** 0.24: нет файла букв (арабские, грузинские названия…) — MapLibre не рисовал весь кусок карты. Все диапазоны должны лежать в assets. */
    @Test fun glyphRangesComplete() {
        val root = File("src/main/assets/map/glyphs")
        val st = JSONObject(MapStyle.compose(input("car", true), MapStyle.Opts()))
        val fonts = HashSet<String>()
        val ls = st.getJSONArray("layers")
        for (i in 0 until ls.length()) ls.getJSONObject(i).optJSONObject("layout")?.optJSONArray("text-font")?.let { a -> for (j in 0 until a.length()) fonts += a.getString(j) }
        assertTrue(fonts.isNotEmpty())
        for (f in fonts) for (start in 0 until 65536 step 256) assertTrue("$f $start", File(root, "$f/$start-${start + 255}.pbf").exists())
    }

    @Test fun controlPointsOfTrack() {
        // зигзаг из 500 точек → не больше 12 контрольных, начало и конец на месте
        val pts = (0 until 500).map { i -> com.pank.vsevodnom.data.GeoPt(50.0 + i * 0.0002, 30.0 + (if ((i / 50) % 2 == 0) i % 50 else 50 - i % 50) * 0.0004, 0) }
        val cp = com.pank.vsevodnom.ui.map.controlPoints(pts, 12)
        assertTrue(cp.size in 3..12)
        assertEquals(pts.first(), cp.first()); assertEquals(pts.last(), cp.last())
    }
}
