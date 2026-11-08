package com.pank.vsevodnom

import com.pank.vsevodnom.data.IncomingGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoLinkTest {
    @Test fun parse() {
        val a = IncomingGeo.parse("https://www.google.com/maps/place/%D0%9F%D1%83%D0%B7%D0%B0%D1%82%D0%B0+%D0%A5%D0%B0%D1%82%D0%B0/@50.4470,30.5200,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d50.4473!4d30.5218")!!
        assertEquals("Пузата Хата", a.first); assertEquals(50.4473, a.second, 1e-6); assertEquals(30.5218, a.third, 1e-6)
        val b = IncomingGeo.parse("https://maps.google.com/?q=50.45,30.52")!!
        assertEquals(50.45, b.second, 1e-6)
        val c = IncomingGeo.parse("geo:50.1,30.2?q=50.1,30.2(Дом)")!!
        assertEquals("Дом", c.first)
        val d = IncomingGeo.parse("https://www.google.com/maps/@49.84,24.03,15z")!!
        assertEquals(24.03, d.third, 1e-6)
        assertTrue(IncomingGeo.isMapLink("Пузата Хата\nhttps://maps.app.goo.gl/VT38PRpTiASc4m627?g_st=ac"))
    }
}
