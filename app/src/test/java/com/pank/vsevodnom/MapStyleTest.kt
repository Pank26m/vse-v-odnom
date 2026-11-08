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
        metro = System.getenv("UA_METRO")?.takeIf { countries }?.let { mapOf("ukraine" to File(it).readText()) } ?: emptyMap())

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
}
