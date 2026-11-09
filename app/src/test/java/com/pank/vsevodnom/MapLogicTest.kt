package com.pank.vsevodnom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Логика карты без движка: часы работы, контрольные точки трека. */
class MapLogicTest {
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


    @Test fun controlPointsOfTrack() {
        // зигзаг из 500 точек → не больше 12 контрольных, начало и конец на месте
        val pts = (0 until 500).map { i -> com.pank.vsevodnom.data.GeoPt(50.0 + i * 0.0002, 30.0 + (if ((i / 50) % 2 == 0) i % 50 else 50 - i % 50) * 0.0004, 0) }
        val cp = com.pank.vsevodnom.ui.map.controlPoints(pts, 12)
        assertTrue(cp.size in 3..12)
        assertEquals(pts.first(), cp.first()); assertEquals(pts.last(), cp.last())
        // кольцо: конец рядом с началом — без точек-дублей ближе 150 м (номера ложились друг на друга)
        val ring = (0..400).map { i -> val a = Math.toRadians(i * 0.9); com.pank.vsevodnom.data.GeoPt(50.0 + 0.03 * Math.sin(a), 30.0 + 0.045 * Math.cos(a), 0) }
        val rc = com.pank.vsevodnom.ui.map.controlPoints(ring, 20)
        for (i in rc.indices) for (j in i + 1 until rc.size - 1) assertTrue(com.pank.vsevodnom.data.Osm.distance(rc[i], rc[j]) >= 150)
        assertTrue(rc.size >= 15)
    }
}
