package com.pank.vsevodnom

import com.pank.vsevodnom.data.GeoPt
import com.pank.vsevodnom.data.Osm
import com.pank.vsevodnom.ui.map.TrackMath
import com.pank.vsevodnom.ui.map.RoutePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMathTest {
    @Test fun stats() {
        // 10 км на север за 30 минут (20 км/ч), 5 минут стоим, подъём на 50 м и спуск на 20 м
        val pts = ArrayList<GeoPt>()
        val t0 = 1_700_000_000_000L
        for (i in 0..100) pts += GeoPt(50.0 + i * 0.0008993, 30.0, t0 + i * 18_000L, 100.0 + (if (i <= 60) i * 50.0 / 60 else 50 - (i - 60) * 20.0 / 40), -1f)
        for (k in 1..5) pts += GeoPt(pts.last().lat, 30.0, pts.last().t + 60_000, pts.last().alt, -1f)
        val s = TrackMath.stats(pts)
        assertEquals(10_000.0, s.dist, 150.0)
        assertTrue(kotlin.math.abs(s.movingMs - 30 * 60_000L) <= 30_000L)
        assertEquals(35 * 60_000L, s.totalMs)
        assertEquals(20.0, s.movingAvg, 1.0)
        assertEquals(50.0, s.ascent, 6.0); assertEquals(20.0, s.descent, 6.0)
        assertTrue(s.max in 18.0..22.0)
        // кодирование с высотой и скоростью и чтение старого формата
        val enc = Osm.encode(listOf(GeoPt(50.1, 30.2, 1_000_000L, 123.4, 12.5f)))
        val back = Osm.decode(enc).single(); assertEquals(123.4, back.alt, 0.05); assertEquals(12.5f, back.spd, 0.05f)
        assertTrue(Osm.decode("50.1,30.2,1000").single().alt.isNaN())
        // метки остановок
        assertEquals("1", RoutePlan.label(0)); assertEquals("99", RoutePlan.label(98)); assertEquals("А", RoutePlan.label(99)); assertEquals("Б", RoutePlan.label(100)); assertEquals("АА", RoutePlan.label(99 + 27))
    }
}
