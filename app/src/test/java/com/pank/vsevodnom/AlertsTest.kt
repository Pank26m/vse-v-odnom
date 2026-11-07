package com.pank.vsevodnom

import com.pank.vsevodnom.data.AlertGeo
import com.pank.vsevodnom.data.AlertLevel
import com.pank.vsevodnom.data.AlertsApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AlertsTest {
    private val geo by lazy { AlertGeo.parseMap(File("src/main/assets/geo/raions.txt").readText()) }
    private val hromady by lazy { AlertGeo.parseHromady(File("src/main/assets/geo/hromady.txt").readText()) }

    private fun alert(type: String, title: String, oblast: String, raion: String? = null, level: String = "red", threat: String? = null) = JSONObject().apply {
        put("location_type", type); put("location_title", title); put("location_oblast", oblast)
        put("location_oblast_uid", "999") // у районов это поле врёт — разбор не должен на него опираться
        raion?.let { put("location_raion", it) }
        put("started_at", "2026-11-07T08:00:00.000Z"); put("alert_type", "air_raid"); put("alert_level", level)
        threat?.let { put("threats", JSONArray().put(JSONObject().put("threat_type", it).put("level", level).put("source_message", "Дронова загроза"))) }
    }
    private fun state(vararg a: JSONObject) = AlertsApi.parseOfficial(JSONObject().put("alerts", JSONArray(a.toList())).toString(), 0)

    @Test fun chernihivIsNotChernivtsi() {
        assertEquals("Чернігівська область", AlertsApi.canonical("Чернігівська область"))
        assertEquals("Чернівецька область", AlertsApi.canonical("Чернівецька область"))
        assertEquals("Чернігівська область", AlertsApi.canonical("Чернігівська"))
        assertEquals("Чернівецька область", AlertsApi.canonical("Чернівецька"))
        assertEquals(AlertsApi.KYIV_CITY, AlertsApi.canonical("м. Київ"))
        assertEquals("Київська область", AlertsApi.canonical("Київська область"))
        AlertsApi.OBLASTS.forEach { assertEquals(it, AlertsApi.canonical(it)) }
        val s = state(alert("raion", "Чернігівський район", "Чернігівська область", level = "yellow", threat = "drones"),
            alert("raion", "Ніжинський район", "Чернігівська область"))
        assertEquals(AlertLevel.PARTIAL, s.of("Чернігівська область"))
        assertEquals(AlertLevel.NONE, s.of("Чернівецька область"))
        assertEquals(listOf("Чернігівський район", "Ніжинський район"), s.districts["Чернігівська область"])
        assertEquals("drones", s.items[0].threats.single().type)
        assertTrue(!s.items[0].red && s.items[1].red)
    }

    @Test fun geoCoversEverything() {
        assertEquals(136, geo.raions.size)
        val obl = geo.oblasts.map { it.name }.toSet()
        AlertsApi.OBLASTS.forEach { assertTrue(it in obl, "нет контура: $it") }
        geo.raions.forEach { assertTrue(it.oblast in AlertsApi.OBLASTS, "район ${it.name}: ${it.oblast}"); assertTrue(it.name.endsWith("район")) }
        // Киев — отдельный контур (дырка в области); точка центра Киева попадает в него, а не в район
        val kyiv = geo.oblasts.first { it.name == AlertsApi.KYIV_CITY }
        val (kx, ky) = kyiv.center
        assertTrue(kyiv.contains(kx, ky)); assertTrue(geo.raions.none { it.contains(kx, ky) })
        assertTrue(hromady.size > 1400)
    }

    @Test fun raionAlertPaintsOnlyRaion() {
        val s = state(alert("raion", "Броварський район", "Київська область", level = "yellow"),
            alert("hromada", "Вовчанська територіальна громада", "Харківська область", raion = "Чугуївський район"),
            alert("oblast", "Луганська область", "Луганська область"))
        val p = AlertGeo.paint(s, geo, hromady)
        assertEquals(1, p.raion["Київська область|Броварський район"])
        assertEquals(null, p.oblast["Київська область"])
        assertEquals(2, p.oblast["Луганська область"])
        val h = p.hromada.keys.single(); assertTrue(h.startsWith("Харківська область|Вовчанська"), h)
        assertTrue("Харківська область|Чугуївський район" in p.raionPartial)
        assertEquals(null, p.raion["Харківська область|Чугуївський район"])
    }

    @Test fun citiesFindTheirHromada() {
        fun city(t: String, o: String) = state(alert("city", t, o)).items.single().let { AlertGeo.findHromada(it, hromady) }
        listOf("м. Марганець", "м. Кривий Ріг", "м. Дніпро", "м. Нікополь").forEach { assertNotNull(city(it, "Дніпропетровська область"), it) }
        assertNotNull(city("м. Запоріжжя", "Запорізька область"))
    }

    /** Живая проверка: ALERTS_JSON — сохранённый ответ active.json; все районы и громады должны найтись на карте. */
    @Test fun liveSample() {
        val path = System.getenv("ALERTS_JSON") ?: return
        val s = AlertsApi.parseOfficial(File(path).readText(), 0)
        val keys = geo.raions.map { it.key }.toSet()
        s.items.filter { it.kind == "raion" }.forEach { assertTrue("${it.oblast}|${it.title}" in keys, it.title) }
        s.items.filter { it.kind == "hromada" }.forEach { assertNotNull(AlertGeo.findHromada(it, hromady), it.title) }
        println("live: ${s.items.size} тревог, " + s.level.filterValues { it != AlertLevel.NONE }.keys)
    }
}
