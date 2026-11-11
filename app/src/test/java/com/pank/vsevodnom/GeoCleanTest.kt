package com.pank.vsevodnom

import com.pank.vsevodnom.data.GeoFiles
import com.pank.vsevodnom.data.GeoPt
import com.pank.vsevodnom.data.Osm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Импорт треков Guru: одна и та же линия дважды (LineString + gx:Track), выбросы, нули. */
class GeoCleanTest {
    // подъём на Пику (Азоры): ~1,2 км по прямой, 40 точек
    private val line = (0 until 40).map { GeoPt(38.47 + it * 0.0003, -28.40 + it * 0.0002, 0) }
    private fun len(p: List<GeoPt>) = p.zipWithNext { a, b -> Osm.distance(a, b) }.sum()

    @Test fun duplicateGeometryIsDropped() {
        val out = GeoFiles.joinParts(listOf(line, line))
        assertEquals(line.size, out.size)
        // без прямой «назад к старту»
        assertTrue(Osm.distance(out.last(), line.last()) < 1)
    }

    @Test fun spikeAndZerosRemoved() {
        val bad = line.toMutableList()
        bad.add(20, GeoPt(62.0, 6.0, 0))       // «точка у Норвегии»
        bad.add(5, GeoPt(0.0, 0.0, 0))         // нули
        bad.add(GeoPt(-85.0, -180.0, 0))        // «Антарктида» в конце
        val out = GeoFiles.joinParts(listOf(bad))
        assertEquals(line.size, out.size)
        assertTrue(len(out) < len(line) * 1.01)
    }

    @Test fun segmentsJoinInOrder() {
        val a = line.take(20); val b = line.drop(20)
        val out = GeoFiles.joinParts(listOf(b.asReversed(), a))
        assertEquals(line.size, out.size)
        assertTrue(len(out) < len(line) * 1.01)
    }
}

/** Улица из Nominatim берётся, только если нажатие на её линии (а не на соседнем безымянном проезде). */
class StreetDistanceTest {
    private val street = listOf(listOf(GeoPt(50.4000, 30.5000, 0), GeoPt(50.4000, 30.5100, 0)))   // с запада на восток ~710 м
    @Test fun onLine() { assertTrue(com.pank.vsevodnom.data.Streets.distanceTo(GeoPt(50.40005, 30.505, 0), street) < 7) }
    @Test fun yardFarAway() { assertTrue(com.pank.vsevodnom.data.Streets.distanceTo(GeoPt(50.4006, 30.505, 0), street) > 60) }
}
