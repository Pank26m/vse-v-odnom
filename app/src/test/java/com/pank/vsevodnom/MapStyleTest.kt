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
        if (countries) listOf(shape) else emptyList())

    @Test fun styles() {
        val out = File("build/map-style").apply { mkdirs() }
        for (v in MapStyle.Variant.entries) for (c in listOf(false, true)) for (lang in listOf("local", "ru")) {
            val s = JSONObject(MapStyle.compose(input(v.key, c), MapStyle.Opts(variant = v, lang = lang)))
            val ids = (0 until s.getJSONArray("layers").length()).map { s.getJSONArray("layers").getJSONObject(it).getString("id") }
            assertEquals("дубли id слоёв", ids.size, ids.toSet().size)
            assertTrue(ids.contains("world"))
            if (c) { assertTrue(ids.any { it.endsWith("@ukraine") }); assertTrue(ids.contains("hatch")); assertTrue(ids.contains("hill@ukraine")) }
            File(out, "${v.key}-${if (c) "ua" else "world"}-$lang.json").writeText(s.toString(1))
        }
        val online = MapStyle.compose(input("car", true), MapStyle.Opts(online = OnlineMaps.BUILT_IN[1]))
        File(out, "online.json").writeText(JSONObject(online).toString(1))
    }
}
