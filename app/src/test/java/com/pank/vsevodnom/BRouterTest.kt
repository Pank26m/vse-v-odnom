package com.pank.vsevodnom

import btools.router.HintAccess
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Офлайн-маршрут BRouter на настоящих сегментах: BROUTER_SEG=папка с E30_N50.rd5 (иначе тест пропускается). */
class BRouterTest {
    @Test fun kyivRoute() {
        val seg = System.getenv("BROUTER_SEG")?.let(::File)
        assumeTrue(seg != null && File(seg, "E30_N50.rd5").exists())
        val prof = File("build/brouter-profiles").apply { mkdirs() }
        File("src/main/assets/brouter").listFiles()!!.forEach { it.copyTo(File(prof, it.name), true) }
        for (p in listOf("car-vario.brf", "trekking.brf", "hiking-mountain.brf")) {
            val rc = RoutingContext(); rc.localFunction = File(prof, p).absolutePath; rc.turnInstructionMode = 9
            fun node(lat: Double, lon: Double, n: String) = OsmNodeNamed().apply { name = n; ilon = ((lon + 180) * 1e6).toInt(); ilat = ((lat + 90) * 1e6).toInt() }
            val e = RoutingEngine(null, null, seg, arrayListOf(node(50.4501, 30.5234, "Майдан"), node(50.4020, 30.6170, "Позняки")), rc)
            e.quite = true
            val t0 = System.currentTimeMillis(); e.doRun(60_000)
            val t = e.foundTrack
            e.lastError?.printStackTrace()
            println("$p: ${t?.distance} м, ${t?.totalSeconds} с, точек ${t?.nodes?.size}, подсказок ${HintAccess.hints(t).size}, ${System.currentTimeMillis() - t0} мс, ошибка=${e.errorMessage}")
            println("  " + HintAccess.hints(t).take(8).joinToString { "cmd${it[0]}@${it[1]}" })
            assertTrue("маршрут $p не найден: ${e.errorMessage}", t != null && t.distance in 8_000..25_000)
            assertTrue(HintAccess.hints(t).isNotEmpty())
        }
    }
}

class NavSimTest {
    /** Едем по настоящему офлайн-маршруту: шаги идут по порядку, расстояние до манёвра убывает, в конце «прибыли»; съезд в сторону → нужен пересчёт. */
    @Test fun drive() {
        val seg = System.getenv("BROUTER_SEG")?.let(::File)
        assumeTrue(seg != null && File(seg, "E30_N50.rd5").exists())
        val prof = File("build/brouter-profiles").apply { mkdirs() }
        File("src/main/assets/brouter").listFiles()!!.forEach { it.copyTo(File(prof, it.name), true) }
        val rc = RoutingContext(); rc.localFunction = File(prof, "car-vario.brf").absolutePath; rc.turnInstructionMode = 9
        fun node(lat: Double, lon: Double) = OsmNodeNamed().apply { name = "p"; ilon = ((lon + 180) * 1e6).toInt(); ilat = ((lat + 90) * 1e6).toInt() }
        val e = RoutingEngine(null, null, seg, arrayListOf(node(50.4501, 30.5234), node(50.4020, 30.6170)), rc); e.quite = true; e.doRun(60_000)
        val t = e.foundTrack!!
        val pts = t.nodes.map { com.pank.vsevodnom.data.GeoPt(it.iLat / 1e6 - 90, it.iLon / 1e6 - 180, 0) }
        val steps = HintAccess.hints(t).map { com.pank.vsevodnom.data.Routing.Step(it[1], it[0], it[2]) } + com.pank.vsevodnom.data.Routing.Step(pts.size - 1, com.pank.vsevodnom.data.Nav.END)
        val route = com.pank.vsevodnom.data.Routing.Route(pts, t.distance.toDouble(), t.totalSeconds.toDouble(), steps = steps)
        val nav = com.pank.vsevodnom.data.Nav
        nav.session = com.pank.vsevodnom.data.Nav.Session(route, "car", "Позняки")
        val seen = ArrayList<Int>()
        var lastIdx = -1
        for (p in pts) {
            assertTrue("на маршруте не должно быть пересчёта", !nav.update(p, 10f))
            val st = nav.status!!
            st.step?.let { if (it.index != lastIdx) { seen += it.cmd; lastIdx = it.index } }
        }
        println("манёвры по ходу: $seen; итог: arrived=${nav.status!!.arrived}, осталось ${nav.status!!.left.toInt()} м")
        assertTrue(nav.status!!.arrived)
        // уехали в сторону на ~300 м — после трёх обновлений нужен пересчёт
        nav.session = com.pank.vsevodnom.data.Nav.Session(route, "car", "Позняки")
        val off = com.pank.vsevodnom.data.GeoPt(pts[50].lat + 0.003, pts[50].lon, 0)
        val r = (1..3).map { nav.update(off, 10f) }
        assertTrue(r.last())
        println("фразы: " + listOf(nav.phrase(5, 0), nav.phrase(13, 2), nav.distText(340.0), nav.distText(1260.0)))
    }
}
